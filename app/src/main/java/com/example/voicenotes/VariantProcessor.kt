package com.example.voicenotes

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Живучий фоновый расчёт вариантов (ступень × тон).
 * - Живёт на уровне приложения: продолжает работу при закрытой заметке,
 *   свёрнутом приложении, работе с другой заметкой.
 * - Сохраняет каждый готовый вариант на диск сразу (persist).
 * - Агрессивно повторяет неудачи (сбой ИИ) с паузами, не превышая лимит.
 * - Проверяет длину: сжатый результат не длиннее оригинала.
 */
class VariantProcessor(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val context: android.content.Context,   // для локального ИИ
    private val notesProvider: () -> List<Note>,   // доступ к актуальным заметкам
    private val persist: () -> Unit                 // сохранить всё на диск
) {
    enum class State { QUEUED, RUNNING, DONE, FAILED }

    private val states = mutableStateMapOf<String, State>()
    private val jobs = mutableMapOf<Long, Job>()

    /**
     * Отменяет обработку заметки (кнопка отмены).
     *
     * Отмена срабатывала «через несколько секунд»: экран продолжал показывать работу, пока
     * не завершится текущий вызов модели (нативную генерацию прервать нельзя). Теперь все
     * признаки работы гаснут СРАЗУ — интерфейс отвечает мгновенно, а фоновая корутина
     * доигрывает текущий кусок и выходит на ближайшей проверке отмены.
     */
    fun cancel(noteId: Long) {
        jobs[noteId]?.cancel()
        jobs.remove(noteId)
        activeNote[noteId] = false
        activeEngineOf.remove(noteId); stageOf.remove(noteId)
        partDone.remove(noteId); partTotal.remove(noteId)
        val prefix = "$noteId:"
        oneJobs.keys.filter { it.startsWith(prefix) }.toList()
            .forEach { oneJobs.remove(it)?.cancel() }
        updating.keys.filter { it.startsWith(prefix) }.toList().forEach { updating.remove(it) }
        states.keys.filter { it.startsWith(prefix) && states[it] == State.RUNNING }.toList()
            .forEach { states[it] = State.QUEUED }
        Diagnostics.action("Обработка ИИ отменена пользователем (индикация снята сразу)")
    }
    // ПРОГРЕСС в два уровня (v118). Раньше и «готово вариантов», и «кусок N из M»
    // писались в ОДНУ пару счётчиков и затирали друг друга: куски добегали до 100%,
    // следом счётчик вариантов сбрасывал полосу к 20% — она мигала на каждом варианте.
    private val progressDone = mutableStateMapOf<Long, Int>()    // готовых вариантов
    private val progressTotal = mutableStateMapOf<Long, Int>()
    private val partDone = mutableStateMapOf<Long, Int>()        // кусков внутри текущей ступени
    private val partTotal = mutableStateMapOf<Long, Int>()
    private val activeNote = mutableStateMapOf<Long, Boolean>()

    // Пауза между ИИ-запросами, чтобы не упереться в лимит (20/мин → ~3.2с).
    private val requestSpacingMs = 3500L
    private val maxRetries = 4

    // Последняя ошибка ИИ (для показа причины пользователю).
    var lastAiError: String? = null
        private set

    fun isActive(noteId: Long): Boolean = activeNote[noteId] == true
    // Каким движком РЕАЛЬНО идёт пакетный расчёт ("local"/"cloud"/"rules"/"").
    private val activeEngineOf = mutableStateMapOf<Long, String>()
    fun activeEngine(noteId: Long): String = activeEngineOf[noteId] ?: ""
    // Текущий этап пакетной обработки для экрана ожидания («Чисто: часть 3 из 7»).
    private val stageOf = mutableStateMapOf<Long, String>()
    fun stage(noteId: Long): String = stageOf[noteId] ?: ""
    fun doneCount(noteId: Long): Int = progressDone[noteId] ?: 0
    fun totalCount(noteId: Long): Int = progressTotal[noteId] ?: 0
    /** Кусков обработано / всего внутри текущей ступени (0 — ступень не делится). */
    fun partDoneCount(noteId: Long): Int = partDone[noteId] ?: 0
    fun partTotalCount(noteId: Long): Int = partTotal[noteId] ?: 0

    private fun k(noteId: Long, l: Level, t: Tone) = "$noteId:${l.ordinal}:${t.ordinal}"
    fun stateOf(noteId: Long, l: Level, t: Tone): State? = states[k(noteId, l, t)]

    private fun allCombos(lecture: Boolean = false): List<Pair<Level, Tone>> = buildList {
        for (l in Level.entries) {
            if (l == Level.VERBATIM) continue
            if (lecture) {
                // Лекция: тон не используется, только NEUTRAL (3 варианта вместо 9).
                add(l to Tone.NEUTRAL)
            } else {
                for (t in Tone.entries) add(l to t)
            }
        }
    }

    // Роутинг: локальный ИИ (если выбран и модель готова) или облачный.
    private suspend fun processAllRouted(note: Note, text: String): Map<String, String> {
        if (settings.localAi) {
            if (!LocalAiModelManager.isReady(context, settings.localAiModel)) {
                // Модели нет — работаем на надёжных правилах (без ИИ, но всегда результат).
                Diagnostics.engine("Офлайн без модели: обработка правилами")
                lastEngine = "правила"
                return rulesBasedAll(text, note.recordMode == "google")
            }
            Diagnostics.engine("Обработка вариантов: ЛОКАЛЬНЫЙ ИИ (+ правила как запас)")
            return localProcessAll(note, text)  // внутри есть fallback на правила
        }
        Diagnostics.engine("Обработка вариантов: ОБЛАЧНЫЙ ИИ")
        lastEngine = "облако"
        stageOf[note.id] = "Запрос облаку (все 9 вариантов одним запросом)…"
        val all = cleanupMeta(AiClient.processAll(text, settings.apiKey)).toMutableMap()
        // Облачные результаты проходят ТУ ЖЕ проверку, что и локальные: качество не
        // должно зависеть от того, какую бесплатную модель выбрал роутер в этот раз.
        for (tn in Tone.entries) {
            val cKey = "${Level.CLEAN.ordinal}:${tn.ordinal}"
            all[cKey]?.let { cand ->
                val ok = verifyClean(text, cand, "облако")
                all[cKey] = ok ?: CleanProcessor.clean(text, note.recordMode == "google")
                if (ok == null) engineOf["${Level.CLEAN.ordinal}"] = "правила (облако исказило)"
            }
            val cleanSrc = all[cKey] ?: text
            for (l in listOf(Level.BRIEF, Level.GIST)) {
                val sKey = "${l.ordinal}:${tn.ordinal}"
                all[sKey]?.let { cand ->
                    val ok = verifySummary(cleanSrc, cand, "облако")
                    all[sKey] = ok ?: TextCondenser.condense(cleanSrc, l)
                    if (ok == null) engineOf["${l.ordinal}"] = "правила (облако не пересказало)"
                }
            }
        }
        return all
    }

    /**
     * ВЕРИФИКАЦИЯ «ЧИСТО» — одна и та же для облака и для модели на устройстве (v127).
     *
     * Раньше защита слов и проверка потерь стояли только на локальном пути. Но облако
     * ошибается ровно так же: в архиве одна бесплатная модель восстановила текст почти
     * идеально, а другая на том же тексте перефразировала начало и выбросила слова.
     * Какая модель ответит — лотерея, поэтому гарантии перенесены из промпта в КОД:
     *   • несозвучные замены откатываются к исходным словам;
     *   • выброшенный фрагмент (6+ слов подряд) — результат не принимается.
     * Так качество перестаёт зависеть от того, кто именно ответил.
     */
    private fun verifyClean(source: String, candidate: String, who: String): String? {
        val (fixed, rolled) = restoreWords(candidate, source)
        if (rolled > 0) Diagnostics.info("Защита слов ($who): откачено $rolled замен(ы)")
        val (lost, lostTxt) = longestLostRun(source, fixed)
        if (lost >= 6) {
            Diagnostics.error("Чисто ($who): выброшен фрагмент из $lost слов — «${lostTxt.take(60)}» → отклонено")
            return null
        }
        return fixed
    }

    /**
     * ВЕРИФИКАЦИЯ «КРАТКО»/«СУТЬ» — тоже общая для облака и модели на устройстве (v127).
     *
     * Измерение по архивам показало: детектор копирования и фильтр мета-речи стояли
     * только на локальном пути, и облако их обходило. В последнем тесте облачное
     * «Кратко» на короткой заметке оказалось дословной копией (совпадение 1.00), а на
     * лекции начиналось с «Лекция охватывает две темы» — описание со стороны вместо
     * изложения. Возвращает null, если результат не годится: вызывающий берёт правила.
     */
    private fun verifySummary(source: String, candidate: String, who: String): String? {
        val t = LocalAiEngine.cutSecondVariant(stripMetaPreamble(candidate)).trim()
        if (t.isBlank()) return null
        if (isMetaTalk(t)) {
            Diagnostics.error("Пересказ ($who): рассуждение О тексте («${t.take(40)}…») → отклонено")
            return null
        }
        if (LocalAiEngine.isCopyNotSummary(source, t)) {
            Diagnostics.error("Пересказ ($who): это копия источника, а не изложение → отклонено")
            return null
        }
        return t
    }

    /**
     * Срезает у облачных Кратко/Суть зачины «Вот краткий пересказ:» (v118).
     * Рассуждение О тексте («Автор сравнивает Россию с умом…») в облаке встречается реже,
     * чем у локальной модели, но зачин-предисловие бывает — убираем его молча.
     */
    private fun cleanupMeta(src: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>(src.size)
        var cut = 0
        for ((k2, v) in src) {
            val lvl = k2.substringBefore(':').toIntOrNull()
            val isSummary = lvl == Level.BRIEF.ordinal || lvl == Level.GIST.ordinal
            // Для Кратко/Суть: убираем зачин-предисловие и «второй вариант» пересказа
            // (облако по лекции выдало два пересказа подряд — «…Второй вариант: Лекция про…»).
            val nv = if (isSummary) LocalAiEngine.cutSecondVariant(stripMetaPreamble(v)) else v
            if (nv != v) cut++
            out[k2] = nv
        }
        if (cut > 0) Diagnostics.info("Облако: убран зачин-предисловие в $cut вариант(ах)")
        return out
    }

    // Метка движка, которым получен последний результат (для истории версий).
    @Volatile private var lastEngine: String = ""
    private fun localLabel() = "на устройстве (${settings.localAiModel})"

    // ── Промпты локальной модели: КОРОТКИЕ и КОНКРЕТНЫЕ (1.5B теряется в длинных).
    //
    // «Чисто» — это НЕ улучшение текста, а ВОССТАНОВЛЕНИЕ речи. На вход приходит машинная
    // расшифровка звука: программа разбирала запись слово за словом, подменяла созвучные
    // слова («робуста»→«работа», «Машу и Петю»→«какой машине и плетью») и расставила знаки
    // формально. Модель должна восстановить, что человек сказал на самом деле, и разбить
    // на предложения ПО СМЫСЛУ, а не по машинным точкам.
    // v120: добавлена одна фраза про происхождение точек. Пользователь: «Он как будто бы не
    // понимает, что исходная пунктуация — это предложение от программы, которая глупее ИИ».
    // Формулировка короткая намеренно: на длинных инструкциях Qwen 1.5B теряется (урок v106).
    private val LOCAL_CLEAN = "Программа распознала речь с ошибками. Восстанови, что человек сказал. " +
        "Точки в тексте поставила программа — не верь им, расставь заново по смыслу речи. " +
        "Бессмысленное слово замени похожим по звучанию. Понятные слова не трогай. " +
        "Ничего не сокращай и не добавляй. В ответе только текст."
    // «Кратко» — то же самое вдвое короче, БЕЗ анализа и пересказа со стороны.
    // v122: добавлены два запрета по итогам теста — «не переписывай теми же словами»
    // (модель копировала вход вместо пересказа) и «пиши полными предложениями»
    // (облако выдавало телеграф: «куплен чай бергамотом и виолончель дочка»).
    private val LOCAL_BRIEF = "Перескажи этот текст СВОИМИ словами вдвое короче. " +
        "Не переписывай теми же словами. Полными предложениями, от того же лица, в том же порядке. " +
        "Не анализируй и не объясняй. Смысл не меняй. В ответе только текст."
    private val LOCAL_GIST = "Скажи своими словами, о чём главное в этом тексте — одним-двумя предложениями. " +
        "Полными предложениями, от того же лица. Не пиши «в тексте говорится». В ответе только текст."
    private val LOCAL_LECTURE_BRIEF = "Это запись лекции. Перескажи её своими словами вдвое короче, " +
        "сохранив все темы и их порядок. Полными предложениями. Не анализируй. В ответе только текст."
    private val LOCAL_LECTURE_GIST = "Это запись лекции. Назови своими словами главное по каждой её теме, " +
        "коротко и полными предложениями. Не анализируй. В ответе только текст."

    /**
     * Доля от длины источника: Кратко ≈ половина, Суть ≈ четверть.
     *
     * v122: для «Сути» доля зависит от длины входа. Источник «Сути» — уже сжатое «Кратко»,
     * и на короткой заметке выходило «сожми 148 символов до 37» — невыполнимая задача, из
     * которой рождался слипшийся мусор. Если входа мало, сжимаем мягче: одно-два
     * нормальных предложения полезнее обрубка.
     */
    private fun ratioOf(l: Level, srcLen: Int = 0): Double = when {
        l != Level.GIST -> 0.5
        srcLen in 1 until 250 -> 0.6
        srcLen in 250 until 600 -> 0.4
        else -> 0.25
    }

    /** Локальное «Чисто»: модель по кускам; кусок, который модель исказила, — правилами. */
    private suspend fun localClean(note: Note, text: String, prompt: String = LOCAL_CLEAN): String {
        val googleCaps = note.recordMode == "google"
        stageOf[note.id] = "Чисто: модель на устройстве…"
        var rolledTotal = 0
        val res = LocalAiEngine.processLong(context, prompt, text, settings.localAiModel, noteId = note.id,
            onProgress = { d, t, _ -> partDone[note.id] = d; partTotal[note.id] = t
                stageOf[note.id] = "Чисто: часть $d из $t" },
            chunkOk = { chunk, r ->
                val (lost, lostTxt) = longestLostRun(chunk, r)
                if (lost >= 6) Diagnostics.error(
                    "Чисто: выброшен фрагмент из $lost слов подряд — «${lostTxt.take(60)}» → кусок не принят")
                !tooDistorted(r, chunk) && !isCopyOrTruncation(r, chunk) && lost < 6
            },
            chunkFix = { chunk, r ->
                val (fixed, rolled) = restoreWords(r, chunk)
                if (rolled > 0) {
                    rolledTotal += rolled
                    Diagnostics.info("Защита слов: откачено $rolled замен(ы) к исходным словам")
                }
                fixed
            })
        if (rolledTotal > 0) Diagnostics.engine("Чисто: защита слов вернула $rolledTotal слов(а) из Дословно")
        return if (!res.isNullOrBlank() && res != text) {
            // финальная косметика правилами (двойная пунктуация, заглавные)
            Diagnostics.engine("Чисто: локальная модель (${res.length} симв из ${text.length})")
            lastEngine = localLabel()
            Punctuator.capitalizeSentences(CleanProcessor.normalizePunct(res))
        } else {
            Diagnostics.engine("Чисто: модель не справилась → правила")
            lastEngine = "правила"
            CleanProcessor.clean(text, googleCaps)
        }
    }

    /**
     * Локальное сжатие (Кратко/Суть). Источник — уже ВОССТАНОВЛЕННЫЙ текст «Чисто»
     * (каскад), а не сырая расшифровка: модели больше не приходится одновременно чинить
     * распознавание и сокращать. Сжатие однопроходное, в заданную долю — темы не
     * перемешиваются (см. LocalAiEngine.condense).
     */
    private suspend fun localSummary(note: Note, text: String, l: Level, lecture: Boolean): String {
        val prompt = when {
            lecture && l == Level.BRIEF -> LOCAL_LECTURE_BRIEF
            lecture -> LOCAL_LECTURE_GIST
            l == Level.BRIEF -> LOCAL_BRIEF
            else -> LOCAL_GIST
        }
        val name = if (l == Level.BRIEF) "Кратко" else "Суть"
        stageOf[note.id] = "$name: модель на устройстве…"
        val ratio = ratioOf(l, text.length)
        val raw = LocalAiEngine.condense(context, prompt, text, settings.localAiModel, ratio, note.id,
            onProgress = { d, t, _ -> partDone[note.id] = d; partTotal[note.id] = t
                stageOf[note.id] = "$name: часть $d из $t" })
        // Срезаем зачин «Вот краткий пересказ:» — сам текст после него обычно годный.
        val r: String = raw?.let { stripMetaPreamble(it) }.orEmpty()
        val meta = r.isNotBlank() && isMetaTalk(r)
        if (meta) Diagnostics.error("$name: ответ — рассуждение О тексте («${r.take(45)}…») → правила")
        val ok = r.isNotBlank() && !isLoopy(r) && !meta &&
            r.length >= (if (l == Level.BRIEF) 10 else 5) && r.length < text.length
        return if (ok) {
            Diagnostics.engine("$l: локальный ИИ, ${text.length}→${r.length} симв (цель ${(ratio * 100).toInt()}%)")
            lastEngine = localLabel()
            r
        } else {
            Diagnostics.engine("$l: локальный ИИ не дал результат → правила (${LocalAiEngine.lastStatus})")
            lastEngine = "правила"
            TextCondenser.condense(text, l)
        }
    }

    // Полностью офлайн-обработка на правилах (без ИИ) — гарантированный результат.
    private fun rulesBasedAll(text: String, googleCaps: Boolean): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val c = CleanProcessor.clean(text, googleCaps)
        val b = TextCondenser.condense(text, Level.BRIEF)
        val g = TextCondenser.condense(text, Level.GIST)
        // все тоны одинаково (правила тон не различают) — иначе Формально/Живой оставались пустыми
        for (tn in Tone.entries) {
            result["${Level.CLEAN.ordinal}:${tn.ordinal}"] = c
            result["${Level.BRIEF.ordinal}:${tn.ordinal}"] = b
            result["${Level.GIST.ordinal}:${tn.ordinal}"] = g
        }
        return result
    }

    private suspend fun processLectureRouted(note: Note, text: String): Map<String, String> {
        if (settings.localAi) {
            if (!LocalAiModelManager.isReady(context, settings.localAiModel)) {
                Diagnostics.error("Локальный ИИ (лекция): модель не скачана")
                throw RuntimeException("Локальный ИИ: модель не скачана")
            }
            Diagnostics.engine("Стенограмма: ЛОКАЛЬНЫЙ ИИ")
            val res = localProcessLecture(note, text)
            if (res.isNotEmpty()) { Diagnostics.engine("Локальный ИИ (лекция) вернул ${res.size}"); return res }
            Diagnostics.error("Локальный ИИ (лекция) не дал результат (${LocalAiEngine.lastStatus})")
            throw RuntimeException("Локальный ИИ не дал результат (${LocalAiEngine.lastStatus})")
        }
        Diagnostics.engine("Стенограмма: ОБЛАЧНЫЙ ИИ")
        lastEngine = "облако"
        return cleanupMeta(AiClient.processLecture(text, settings.apiKey))
    }

    // Локальная обработка: по одному варианту (маленькой модели проще).
    // Чисто — модель по кускам (при искажении куска — правила), Кратко/Суть — суммаризация.
    // Уровни, где все тоны уже есть, НЕ пересчитываем (фон добирает только недостающее).
    private suspend fun localProcessAll(note: Note, text: String): Map<String, String> =
        localBatch(note, text, lecture = false)

    private suspend fun localProcessLecture(note: Note, text: String): Map<String, String> =
        localBatch(note, text, lecture = true)

    /**
     * КАСКАД (v118): Дословно → Чисто → Кратко → Суть.
     *
     * Раньше все три уровня считались НЕЗАВИСИМО от сырой расшифровки — модель
     * пересказывала кашу («какой машине и плетью»), да ещё каждый уровень с нуля.
     * Теперь каждый следующий уровень работает с результатом предыдущего:
     *   • качество — сокращается уже восстановленный текст;
     *   • скорость — вместо ~19 800 символов генерации выходит ~10 300 (вдвое меньше).
     */
    private suspend fun localBatch(note: Note, text: String, lecture: Boolean): Map<String, String> {
        val result = mutableMapOf<String, String>()
        fun missing(l: Level) = Tone.entries.any { note.getVariant(l, it) == null }
        val combos = allCombos(lecture)
        // Локальная модель тон не различает — заполняем все тоны одинаково.
        //
        // ВАЖНО (v119): ступень записывается в заметку и сохраняется СРАЗУ, как только
        // готова. Раньше все три уровня отдавались одной пачкой в самом конце, и человек
        // ждал «Суть», хотя «Чисто» было готово минуту назад. Теперь «Чисто» можно читать,
        // пока считаются «Кратко» и «Суть».
        fun put(l: Level, v: String) {
            for (tn in Tone.entries) {
                result["${l.ordinal}:${tn.ordinal}"] = v
                if (note.getVariant(l, tn) == null) note.putVariant(l, tn, v, lastEngine)
                states[k(note.id, l, tn)] = State.DONE
            }
            engineOf["${l.ordinal}"] = lastEngine
            // Ступень закрыта — двигаем ОБЩИЙ прогресс (полоса растёт только здесь и
            // потому не откатывается), счётчик кусков обнуляем под следующую ступень.
            progressTotal[note.id] = combos.size
            progressDone[note.id] = combos.count { (lv, tn) -> note.getVariant(lv, tn) != null }
            partDone.remove(note.id); partTotal.remove(note.id)
            persist()
            Diagnostics.engine("Ступень $l готова и показана (${v.length} симв, движок: $lastEngine)")
        }

        val readyClean = note.getVariant(Level.CLEAN, Tone.NEUTRAL).orEmpty()
        val cleanText: String = if (missing(Level.CLEAN) || readyClean.isBlank()) {
            val c = localClean(note, text)
            put(Level.CLEAN, c)
            Diagnostics.info("Каскад: Чисто ← Дословно (${text.length}→${c.length} симв)")
            c
        } else readyClean

        val readyBrief = note.getVariant(Level.BRIEF, Tone.NEUTRAL).orEmpty()
        val briefText: String = if (missing(Level.BRIEF) || readyBrief.isBlank()) {
            val b = localSummary(note, cleanText, Level.BRIEF, lecture)
            put(Level.BRIEF, b)
            Diagnostics.info("Каскад: Кратко ← Чисто (${cleanText.length}→${b.length} симв)")
            b
        } else readyBrief

        if (missing(Level.GIST)) {
            val gist = localSummary(note, briefText, Level.GIST, lecture)
            put(Level.GIST, gist)
            Diagnostics.info("Каскад: Суть ← Кратко (${briefText.length}→${gist.length} симв)")
        }
        return result
    }
    // Метка движка по уровню для последнего пакетного результата (локальный режим).
    private val engineOf = HashMap<String, String>()

    /** Запускает/продолжает расчёт недостающих вариантов заметки. */
    fun ensureAll(note: Note, priorityLevel: Level, priorityTone: Tone) {
        if (note.original.isBlank()) return
        if (jobs[note.id]?.isActive == true) return

        val combos = allCombos(note.isLecture)
        for ((l, t) in combos) {
            val key = k(note.id, l, t)
            if (note.getVariant(l, t) == null) {
                if (states[key] != State.RUNNING) states[key] = State.QUEUED
            } else states[key] = State.DONE
        }
        progressTotal[note.id] = combos.size
        progressDone[note.id] = combos.count { (l, t) -> note.getVariant(l, t) != null }
        // Всё уже есть — ничего не запускаем. (Раньше при каждом открытии заметки шёл
        // полный повторный запрос и его результат ложился ПОВЕРХ готовых вариантов.)
        if (combos.all { (l, t) -> note.getVariant(l, t) != null }) return
        activeNote[note.id] = true

        engineOf.clear()
        jobs[note.id] = scope.launch {
          try {
            if (settings.useAI) {
                // Лекция: отдельный запрос стенограммы; иначе умный запрос всех вариантов.
                var attempt = 0
                while (attempt < maxRetries) {
                    // Помечаем недостающие: первый проход — RUNNING (идёт запрос),
                    // последующие — тоже RUNNING только на время запроса.
                    for ((l, t) in combos) {
                        if (note.getVariant(l, t) == null) states[k(note.id, l, t)] = State.RUNNING
                    }
                    try {
                        val srcText = verbatimShown(note)
                        Diagnostics.info("В обработку ушёл текст (${srcText.length} симв): \"${srcText.take(50)}...\"")
                        // Смена заметки = принудительная перезагрузка модели (изоляция).
                        LocalAiEngine.beginNote(note.id, srcText)
                        val all = if (note.isLecture)
                            processLectureRouted(note, srcText)
                        else
                            processAllRouted(note, srcText)
                        // Умный заголовок стенограммы от ИИ.
                        all["TITLE"]?.takeIf { it.isNotBlank() }?.let { note.title = it }
                        var filled = 0; var skipped = 0
                        for ((l, t) in combos) {
                            val key = "${l.ordinal}:${t.ordinal}"
                            val text = all[key]
                            if (text != null && text.isNotBlank()) {
                                // НЕ перезаписываем вариант, который появился, пока шёл пакетный
                                // запрос (например, пользователь нажал «Обновить» и получил
                                // результат раньше) — иначе хороший текст уезжает в историю.
                                if (note.getVariant(l, t) == null) {
                                    val eng = engineOf["${l.ordinal}"] ?: lastEngine
                                    note.putVariant(l, t, text, eng)
                                    filled++
                                } else skipped++
                                states[k(note.id, l, t)] = State.DONE
                            }
                        }
                        Diagnostics.engine("Пакет вариантов записан: $filled${if (skipped > 0) ", пропущено уже готовых: $skipped" else ""} (движок: $lastEngine)")
                        // «Дословно» (VERBATIM) НЕ трогаем — оно всегда исходный текст,
                        // не меняется после ИИ (требование пользователя).
                        progressTotal[note.id] = combos.size
                        progressDone[note.id] = combos.count { (l, t) -> note.getVariant(l, t) != null }
                        stageOf.remove(note.id)
                        persist()
                        val allDone = combos.all { (l, t) -> note.getVariant(l, t) != null }
                        if (allDone) break
                        // Пришло частично: недостающие ставим «в очередь» (не висящие часы),
                        // добор пойдёт следующим проходом.
                        for ((l, t) in combos) {
                            if (note.getVariant(l, t) == null) states[k(note.id, l, t)] = State.QUEUED
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e   // отмена — не ошибка
                        for ((l, t) in combos) {
                            if (note.getVariant(l, t) == null) states[k(note.id, l, t)] = State.FAILED
                        }
                        lastAiError = e.message ?: "Ошибка ИИ"
                        Diagnostics.error("ИИ обработка: ${e.message?.take(60)}")
                        // Локальный ИИ детерминирован: повтор даст тот же результат — не повторяем.
                        if (settings.localAi) { Diagnostics.info("Локальный ИИ: повторы отключены"); break }
                        // Облако: повторять есть смысл не всегда. Нет интернета, отклонённый
                        // ключ, исчерпанный лимит — повтор через 6 секунд ничего не изменит,
                        // а пользователь ждёт. Такие случаи прекращаем сразу.
                        if (isHopeless(lastAiError)) {
                            Diagnostics.error("Облако: повторы бессмысленны ($lastAiError) — прекращаю")
                            break
                        }
                    }
                    attempt++
                    if (attempt < maxRetries &&
                        combos.any { (l, t) -> note.getVariant(l, t) == null }) {
                        delay(6000)
                    }
                }
                // Облако не справилось совсем — не оставляем человека с пустым экраном.
                // Считаем правилами и ЧЕСТНО подписываем движок: пользователь видит, что
                // это запасной вариант, а не работа ИИ (в истории версий метка «правила»).
                if (!settings.localAi && combos.any { (l, t) -> note.getVariant(l, t) == null }) {
                    val src = verbatimShown(note)
                    var n = 0
                    for ((l, t) in combos) {
                        if (note.getVariant(l, t) == null) {
                            val txt = if (l == Level.CLEAN) CleanProcessor.clean(src, note.recordMode == "google")
                                      else TextCondenser.condense(src, l)
                            note.putVariant(l, t, txt, "правила (облако не ответило)")
                            states[k(note.id, l, t)] = State.DONE
                            n++
                        }
                    }
                    if (n > 0) {
                        progressDone[note.id] = combos.count { (l, t) -> note.getVariant(l, t) != null }
                        persist()
                        Diagnostics.engine("Облако не ответило → $n вариант(ов) заполнено правилами " +
                            "(причина: $lastAiError)")
                    }
                }
            } else {
                // Бесплатные правила: считаем каждый локально (мгновенно, без лимитов).
                for ((l, t) in combos) {
                    if (note.getVariant(l, t) == null) {
                        note.putVariant(l, t, TextCondenser.condense(note.original, l), "правила")
                        states[k(note.id, l, t)] = State.DONE
                    }
                }
                progressDone[note.id] = combos.size
                persist()
            }
          } finally {
            activeNote[note.id] = false   // и при отмене тоже (иначе «идёт обработка» навсегда)
            activeEngineOf.remove(note.id); stageOf.remove(note.id)
          }
        }
    }

    /** Ошибки, при которых повтор через 6 секунд заведомо бесполезен. */
    private fun isHopeless(msg: String?): Boolean {
        val m = (msg ?: "").lowercase()
        return listOf("нет интернета", "ключ отклонён", "закончились бесплатные",
            "сеть недоступна", "ключ openrouter не задан", "защищённого соединения",
            // 429: повтор через 6 секунд только глубже загоняет в лимит — ждать надо минуту
            "слишком часто", "лимит бесплатных запросов", "сеть не отвечает")
            .any { m.contains(it) }
    }

    // Идущие пересчёты одного варианта. Раньше признак «идёт обновление» жил В ЭКРАНЕ
    // (aiRunning в EditorScreen) и терялся при выходе из заметки: индикация гасла, кнопка
    // снова становилась доступной, второй тап запускал ВТОРУЮ обработку поверх первой
    // (в логе 13:42:43 и 13:43:04 — два параллельных запроса к облаку), а сама работа
    // обрывалась вместе с экраном. Теперь состояние живёт здесь, на уровне приложения.
    private val updating = mutableStateMapOf<String, Boolean>()
    private val oneJobs = mutableMapOf<String, Job>()   // корутины пересчёта одного варианта
    fun isUpdating(noteId: Long, l: Level, t: Tone): Boolean = updating[k(noteId, l, t)] == true
    /** Идёт ли пересчёт ЛЮБОГО варианта этой заметки (для индикации в шапке). */
    fun isUpdatingAny(noteId: Long): Boolean = updating.any { (key, v) -> v && key.startsWith("$noteId:") }

    /** Пересчитать ОДИН вариант заново («другой вариант» / не понравился). */
    fun regenerateOne(note: Note, l: Level, t: Tone, onDone: (Boolean) -> Unit) {
        if (note.original.isBlank() || l == Level.VERBATIM) return
        val key = k(note.id, l, t)
        // Повторный тап, пока идёт пересчёт этого же варианта, — игнорируем.
        if (updating[key] == true) {
            Diagnostics.action("Обновить ($l/$t): пересчёт уже идёт, повторный запуск отклонён")
            return
        }
        updating[key] = true
        oneJobs[key] = scope.launch {
            states[key] = State.RUNNING
            var ok = false
            try {
                LocalAiEngine.beginNote(note.id, verbatimShown(note))
                val text = computeOne(note, l, t, vary = true)
                note.putVariant(l, t, text, lastEngine)
                states[k(note.id, l, t)] = State.DONE
                clearStale(note.id, l, t)
                // Каскад: пересчитали «Чисто» — «Кратко» и «Суть» построены на прежнем
                // источнике. Помечаем их устаревшими, но НЕ трогаем: текст остаётся виден,
                // пересчитает пользователь кнопкой «Обновить» на нужном уровне.
                if (l == Level.CLEAN || l == Level.BRIEF) markStaleBelow(note, l)
                persist()
                ok = true
                Diagnostics.engine("Обновлён вариант ($l/$t): ${text.length} симв, движок: $lastEngine")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                states[key] = State.FAILED
                lastAiError = e.message?.take(50)
                Diagnostics.error("Обновление варианта ($l) не удалось: ${e.message?.take(50)}")
            } finally {
                // Снимаем признак ТОЛЬКО после того, как текст записан и сохранён —
                // иначе индикация гаснет раньше, чем пользователь видит новый текст.
                updating.remove(key)
                oneJobs.remove(key)
                stageOf.remove(note.id)
            }
            onDone(ok)
        }
    }

    // Ступени, чей ИСТОЧНИК изменился: текст остаётся на месте и виден пользователю,
    // но помечен как «построен на прежнем Чисто». Пересчёт — только по кнопке «Обновить».
    private val staleSet = mutableStateMapOf<String, Boolean>()
    fun isStale(noteId: Long, l: Level, t: Tone): Boolean = staleSet[k(noteId, l, t)] == true

    /**
     * Помечает ступени НИЖЕ указанной устаревшими — НЕ удаляя их (v119).
     *
     * В v118 они удалялись, и после «Обновить» в «Чисто» текст в «Кратко» и «Суть»
     * пропадал: пользователь не мог его увидеть, пока не нажмёт «Обновить» там же.
     * Требование пользователя: кнопка «Обновить» работает ТОЛЬКО со своим уровнем,
     * прежний вариант остаётся видимым и обновляется по желанию.
     */
    private fun markStaleBelow(note: Note, l: Level) {
        val below = when (l) {
            Level.CLEAN -> listOf(Level.BRIEF, Level.GIST)
            Level.BRIEF -> listOf(Level.GIST)
            else -> emptyList()
        }
        var n = 0
        for (lv in below) for (tn in Tone.entries) {
            if (note.getVariant(lv, tn) != null) { staleSet[k(note.id, lv, tn)] = true; n++ }
        }
        if (n > 0) Diagnostics.info("Каскад: $n вариант(ов) ниже $l помечены устаревшими (текст сохранён)")
    }

    /** Снять пометку «устарело» — вызывается, когда уровень пересчитан. */
    private fun clearStale(noteId: Long, l: Level, t: Tone) { staleSet.remove(k(noteId, l, t)) }

    /**
     * Источник уровня по КАСКАДУ: Чисто ← Дословно, Кратко ← Чисто, Суть ← Кратко.
     * Если предыдущая ступень ещё не посчитана, откатываемся к Дословно.
     */
    private fun sourceFor(note: Note, l: Level): String {
        val orig = verbatimShown(note)
        fun v(lv: Level) = note.getVariant(lv, Tone.NEUTRAL)?.takeIf { it.isNotBlank() }
        return when (l) {
            Level.BRIEF -> v(Level.CLEAN) ?: orig
            Level.GIST -> v(Level.BRIEF) ?: v(Level.CLEAN) ?: orig
            else -> orig
        }
    }

    /**
     * Текст «Дословно» ТОТ, ЧТО ВИДЕН НА ЭКРАНЕ (v126).
     *
     * Жалоба пользователя: «обрабатываться должен тот текст, который был выбран из
     * нескольких вариантов, тот который был виден на экране в Дословно». Так и было
     * задумано, но код брал note.original — исходную запись, игнорируя выбор версии
     * стрелками ‹ ›. Если человек листал историю «Дословно» и выбирал другую версию,
     * в обработку всё равно уходила первая. Теперь берём выбранную версию.
     */
    fun verbatimShown(note: Note): String =
        note.getVariant(Level.VERBATIM, Tone.NEUTRAL)?.takeIf { it.isNotBlank() }
            ?: note.refinedText ?: note.original

    /** Вычисление одного варианта с проверкой длины. */
    private suspend fun computeOne(note: Note, l: Level, t: Tone, vary: Boolean = false): String {
        val orig = verbatimShown(note)
        val src = sourceFor(note, l)
        if (src !== orig) Diagnostics.info("Каскад ($l): источник — ${if (l == Level.BRIEF) "Чисто" else "Кратко"} (${src.length} симв)")
        // Роутинг: локальный ИИ (если выбран офлайн) или облачный.
        if (settings.useAI && settings.localAi &&
            LocalAiModelManager.isReady(context, settings.localAiModel)) {
            return when (l) {
                Level.CLEAN -> {
                    // «Обновить» = другая формулировка ТОЙ ЖЕ задачи → другой результат.
                    // Задача везде одна: восстановить речь, а не улучшить текст.
                    val prompts = listOf(
                        LOCAL_CLEAN,
                        "Это машинная расшифровка речи с ошибками. Напиши, что человек сказал на самом деле. " +
                            "Непонятное слово замени созвучным. Знаки препинания — по смыслу. В ответе только текст.",
                        "Восстанови речь по этой расшифровке: исправь неверно распознанные слова на созвучные, " +
                            "раздели на предложения по смыслу. Слова не выбрасывай. В ответе только текст."
                    )
                    localClean(note, orig, if (vary) prompts.random() else prompts[0])
                }
                Level.VERBATIM -> { lastEngine = "правила"; Punctuator.punctuate(orig) }
                else -> localSummary(note, src, l, note.isLecture)
            }
        }
        val result = if (settings.useAI) {
            lastEngine = "облако"
            val r0 = AiClient.process(src, l, t, settings.apiKey, vary)
            when (l) {
                Level.BRIEF, Level.GIST -> verifySummary(src, r0, "облако")
                    ?: run { lastEngine = "правила"; TextCondenser.condense(src, l) }
                // «Чисто» из облака проходит ту же верификацию, что и локальное.
                Level.CLEAN -> verifyClean(src, r0, "облако")
                    ?: run { lastEngine = "правила"; CleanProcessor.clean(src, note.recordMode == "google") }
                else -> r0
            }
        } else { lastEngine = "правила"; TextCondenser.condense(src, l) }

        // Повтор «результат длиннее источника» — ТОЛЬКО для Кратко и Суть (v126).
        // Для «Чисто» ответ ДОЛЖЕН быть примерно равен входу и часто чуть длиннее:
        // добавляются знаки препинания и заглавные. В логе теста: облако вернуло 423
        // символа на 410 исходных — совершенно нормальный результат, но правило считало
        // его провалом, слало ВТОРОЙ запрос, тот упирался в лимит 429, и «Чисто»
        // молча подменялось правилами. Отсюда жалоба «при обновлении текст стал хуже».
        if ((l == Level.BRIEF || l == Level.GIST) && result.length > src.length) {
            return if (settings.useAI) {
                try {
                    val shorter = AiClient.process(src, l, t, settings.apiKey, vary = true)
                    if (shorter.length <= src.length) capLength(shorter, src, l)
                    else { lastEngine = "правила"; TextCondenser.condense(src, l) }
                } catch (_: Exception) {
                    // Запасной путь — правила. Метку движка ОБЯЗАТЕЛЬНО меняем: раньше
                    // оставалось «облако», и в истории версий текст правил значился
                    // как работа облачного ИИ.
                    lastEngine = "правила"; TextCondenser.condense(src, l)
                }
            } else { lastEngine = "правила"; TextCondenser.condense(src, l) }
        }
        return capLength(result, src, l)
    }

    /**
     * Жёсткий потолок длины для Кратко/Суть (облако при «Обновить» с высокой температурой
     * иногда выдавало для «Суть» два абзаца на 566 символов): Суть ≤ 3 предложений и
     * ≤ 35% оригинала, Кратко ≤ 65% оригинала (лишние предложения отрезаем с конца).
     */
    private fun capLength(result: String, orig: String, l: Level): String {
        if (l != Level.BRIEF && l != Level.GIST) return result
        val maxChars = (orig.length * (if (l == Level.GIST) 0.35 else 0.65)).toInt().coerceAtLeast(120)
        var r = if (l == Level.GIST) limitSentences(result, 3) else result
        if (r.length > maxChars) {
            val parts = r.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            val sb = StringBuilder()
            for (p in parts) { if (sb.isNotEmpty() && sb.length + p.length > maxChars) break; sb.append(p).append(" ") }
            if (sb.isNotBlank()) r = sb.toString().trim()
        }
        if (r != result) Diagnostics.engine("$l: результат укорочен ${result.length} → ${r.length} симв (потолок $maxChars)")
        return r
    }

    // Модель просто скопировала/обрезала вход — это не обработка (v115: «жилых» без «домов.»).
    private fun isCopyOrTruncation(result: String, source: String): Boolean {
        val r = result.trim(); val s = source.trim()
        if (r == s) return true
        if (r.length < s.length * 0.9 && s.startsWith(r.take(minOf(r.length, 40)))) {
            // начало совпадает, а конец потерян → обрезка
            val lostWords = s.split(Regex("\\s+")).size - r.split(Regex("\\s+")).size
            if (lostWords >= 2) return true
        }
        return false
    }

    // Оставляет первые N предложений (для ограничения длины суммаризации).
    private fun limitSentences(text: String, n: Int): String {
        val parts = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        return parts.take(n).joinToString(" ").trim()
    }

    // Детект ИСКАЖЕНИЯ: локальный ИИ выдумал слова не из исходника.
    // >40% незнакомых слов = искажение («Мумом», «греха через реху») → берём правила.
    private fun tooDistorted(result: String, source: String): Boolean {
        val srcWords = source.lowercase().split(Regex("[^а-яёa-z0-9]+")).filter { it.length > 2 }.toHashSet()
        val resWords = result.lowercase().split(Regex("[^а-яёa-z0-9]+")).filter { it.length > 2 }
        if (resWords.isEmpty()) return true
        val unknown = resWords.count { it !in srcWords }
        return unknown.toDouble() / resWords.size > 0.4
    }

    // ══ ЗАЩИТА ОТ ПОРЧИ СЛОВ (v118) ═══════════════════════════════════════════
    // Проверка «результат не длиннее оригинала» пропускала подмену смысла: в тесте
    // «погода» модель заменила ПРАВИЛЬНОЕ слово «дождь» на «бешен», длина совпала —
    // и это ушло пользователю. При этом та же модель верно восстановила «какой машине
    // и плетью» → «Машу и Петю», и такие правки терять нельзя.
    //
    // Отличаем одно от другого по СОЗВУЧИЮ. Слово сводим к «скелету»: убираем гласные
    // и мягкие знаки, схлопываем оглушение (б/п, д/т, в/ф, ж/ш, з/с, г/к). Замена
    // принимается, только если скелеты близки:
    //     плетью → плт   ≈  Петю → пт      расстояние 1  → принимаем
    //     дождь  → джд   ≠  бешен → пшн    расстояние 3  → откат к «дождь»
    private fun skeleton(w: String): String {
        val sb = StringBuilder()
        for (c in w.lowercase().replace('ё', 'е')) {
            if (c in "аеиоуыэюяьъй") continue
            sb.append(when (c) {
                'б' -> 'п'; 'д' -> 'т'; 'в' -> 'ф'; 'ж' -> 'ш'; 'з' -> 'с'; 'г' -> 'к'
                else -> c
            })
        }
        return sb.toString()
    }

    private fun lev(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur.copyOf()
        }
        return prev[b.length]
    }

    /** Созвучны ли слова настолько, чтобы считать замену восстановлением, а не порчей. */
    private fun soundsAlike(a: String, b: String): Boolean {
        val sa = skeleton(a); val sb2 = skeleton(b)
        if (sa.isEmpty() || sb2.isEmpty()) return sa == sb2
        if (sa == sb2) return true
        val allowed = maxOf(1, minOf(sa.length, sb2.length) / 3)
        return lev(sa, sb2) <= allowed
    }

    private val wordRe = Regex("[А-Яа-яЁёA-Za-z0-9]+")

    /**
     * Сверяет результат «Чисто» с исходником по словам и откатывает несозвучные замены.
     * Выравнивание — расстоянием Левенштейна по словам (кусок ~130 слов, считается мгновенно).
     * Возвращает исправленный текст и число откатов.
     */
    private fun restoreWords(result: String, source: String): Pair<String, Int> {
        val rTokens = wordRe.findAll(result).map { it.value }.toList()
        val sTokens = wordRe.findAll(source).map { it.value }.toList()
        if (rTokens.isEmpty() || sTokens.isEmpty() || rTokens.size > 400 || sTokens.size > 400)
            return result to 0
        val n = sTokens.size; val m = rTokens.size
        // Таблица правок: 0 — совпало/замена, 1 — пропуск, 2 — вставка.
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val same = sTokens[i - 1].equals(rTokens[j - 1], true)
            d[i][j] = minOf(d[i - 1][j - 1] + (if (same) 0 else 1), d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        // Обратный ход: собираем замены result-слово → source-слово.
        val fix = HashMap<Int, String>()   // индекс слова в result → чем заменить
        var i = n; var j = m; var rolled = 0
        while (i > 0 && j > 0) {
            val same = sTokens[i - 1].equals(rTokens[j - 1], true)
            when {
                d[i][j] == d[i - 1][j - 1] + (if (same) 0 else 1) -> {
                    if (!same && !soundsAlike(sTokens[i - 1], rTokens[j - 1])) {
                        fix[j - 1] = sTokens[i - 1]; rolled++
                    }
                    i--; j--
                }
                d[i][j] == d[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
        if (fix.isEmpty()) return result to 0
        // Собираем текст обратно, сохраняя всю пунктуацию и пробелы результата.
        val sb = StringBuilder(); var last = 0; var idx = 0
        for (mt in wordRe.findAll(result)) {
            sb.append(result, last, mt.range.first)
            val repl = fix[idx]
            if (repl != null) {
                // сохраняем заглавную букву, если она была в результате
                sb.append(if (mt.value.firstOrNull()?.isUpperCase() == true)
                    repl.replaceFirstChar { it.uppercaseChar() } else repl)
            } else sb.append(mt.value)
            last = mt.range.last + 1; idx++
        }
        sb.append(result, last, result.length)
        return sb.toString() to rolled
    }

    /**
     * ПОТЕРЯ ФРАГМЕНТА (v127) — сколько слов исходника выброшено ПОДРЯД.
     *
     * Это стадия «верификации» из практики исправления распознанной речи: сообщество
     * специалистов сходится в том, что LLM, переписывая расшифровку целиком, склонна
     * «переусердствовать» — не только чинить, но и выбрасывать куски. Ловить это надо
     * не промптом, а проверкой результата.
     *
     * Меряем ВЫРАВНИВАНИЕМ с учётом порядка: простая проверка «есть ли слово где-то в
     * ответе» не годится — в тестовом тексте пропало «Конец первой части. Повторяю ещё
     * раз: объём от полутора до двух литров», но слова «конец», «части», «объём»
     * встречались в других местах, и потеря пряталась.
     *
     * Замер на 12 реальных заметках из архивов пользователя:
     *     нормальные результаты  — 0..3 слова подряд («то есть к», «со своими идеями»);
     *     два испорченных        — 12 слов подряд (выброшено целое предложение).
     * Между 3 и 12 огромный зазор, поэтому порог 6 устойчив, а не подогнан под тест.
     */
    private fun longestLostRun(source: String, result: String): Pair<Int, String> {
        val src = wordRe.findAll(source).map { it.value }.toList()
        val res = wordRe.findAll(result).map { it.value }.toList()
        if (src.size < 8 || res.isEmpty() || src.size > 400 || res.size > 400) return 0 to ""
        val a = src.map { skeleton(it) }
        val b = res.map { skeleton(it) }
        val n = a.size; val m = b.size
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val c = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j - 1] + c, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        var i = n; var j = m; var run = 0; var best = 0
        val cur = ArrayList<String>(); var bestTxt = ""
        while (i > 0 && j > 0) {
            val c = if (a[i - 1] == b[j - 1]) 0 else 1
            when {
                d[i][j] == d[i - 1][j - 1] + c -> { run = 0; cur.clear(); i--; j-- }
                d[i][j] == d[i - 1][j] + 1 -> {
                    run++; cur.add(0, src[i - 1])
                    if (run > best) { best = run; bestTxt = cur.joinToString(" ") }
                    i--
                }
                else -> j--
            }
        }
        return best to bestTxt
    }

    // ══ ФИЛЬТР МЕТА-РЕЧИ (v118) ═══════════════════════════════════════════════
    // «Кратко» и «Суть» должны продолжать речь человека, а не рассказывать о ней.
    // В тесте «Умом Россию» модель выдала «Автор сравнивает Россию с умом и аршином…» —
    // это анализ текста, а не изложение, и пользователю он не нужен.
    private val META_STARTS = listOf(
        "этот текст", "это текст", "данный текст", "в тексте", "в данном тексте", "текст -", "текст —",
        "текст представляет", "текст является", "речь идёт", "речь идет", "здесь говорится",
        "автор ", "рассказчик", "говорящий", "в этом видео", "это сообщение", "это стихотворение",
        "в лекции говорится", "лектор рассказывает",
        // v127, найдено в архиве: облако выдало «Лекция охватывает две темы. Первая — …»
        // и «Вот текст лекции:» — это описание материала со стороны, а не изложение.
        "лекция охватывает", "лекция разбирает", "лекция посвящена", "в лекции рассматрив",
        "вот текст", "текст лекции", "материал охватывает", "запись содержит")
    private val META_PREAMBLES = listOf("краткий пересказ", "короткий пересказ", "кратко:", "суть:", "пересказ:")
    // «Вот краткий пересказ:», «Вот краткое изложение:», «Вот суть:» — любая связка
    // «Вот …:» в начале. Отдельным списком все формы не перечислить (модель склоняет
    // как хочет: «краткий», «краткое», «изложение»), поэтому шаблоном.
    private val META_PREFIX_RE = Regex("^вот\\s[^:]{0,50}:", RegexOption.IGNORE_CASE)

    /** Срезает зачин вида «Вот краткий пересказ:» — сам текст после него обычно годный. */
    private fun stripMetaPreamble(t: String): String {
        val head = t.trimStart()
        val low = head.lowercase()
        val byRe = META_PREFIX_RE.find(head)
        if (byRe != null) return head.substring(byRe.range.last + 1).trimStart()
        if (META_PREAMBLES.none { low.startsWith(it) }) return t
        val colon = head.indexOf(':')
        return if (colon in 0..60) head.substring(colon + 1).trimStart() else t
    }

    /** Ответ — рассуждение О тексте, а не изложение текста. */
    private fun isMetaTalk(t: String): Boolean {
        val l = t.trim().lowercase().take(80)
        return META_STARTS.any { l.startsWith(it) }
    }

    // Детект ЯВНОГО зацикливания (одна фраза повторяется много раз подряд).
    private fun isLoopy(text: String): Boolean {
        // Мусор-абракадабра: много латиницы/цифр вместо русского («alpha 2023 Cre01»).
        val letters = text.count { it.isLetter() }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (letters > 10 && latin.toDouble() / letters > 0.5) return true
        val words = text.split(Regex("\\s+")).filter { it.length > 1 }
        if (words.size < 10) return false
        // 3-словное сочетание повторяется 3+ раза — явная галлюцинация
        val triples = HashMap<String, Int>()
        for (i in 0..words.size - 3) {
            val key = "${words[i]} ${words[i+1]} ${words[i+2]}".lowercase()
            val c = (triples[key] ?: 0) + 1; triples[key] = c
            if (c >= 3) return true
        }
        // одно слово подряд 4+ раза
        var run = 1
        for (i in 1 until words.size) {
            if (words[i].equals(words[i-1], true)) { run++; if (run >= 4) return true } else run = 1
        }
        return false
    }

    /** Продолжить обработку ВСЕХ заметок, где есть недосчитанное (вызывать периодически). */
    fun resumeAll() {
        if (!settings.autoAi) return  // при ручном режиме фон не досчитывает сам
        for (note in notesProvider()) {
            if (note.original.isBlank()) continue
            val hasGaps = allCombos(note.isLecture).any { (l, t) -> note.getVariant(l, t) == null }
            if (hasGaps && jobs[note.id]?.isActive != true) {
                ensureAll(note, Level.CLEAN, Tone.NEUTRAL)
            }
        }
    }

    fun reset(noteId: Long) {
        jobs[noteId]?.cancel()
        jobs.remove(noteId)
        val prefix = "$noteId:"
        states.keys.filter { it.startsWith(prefix) }.forEach { states.remove(it) }
        progressDone.remove(noteId)
        progressTotal.remove(noteId)
        partDone.remove(noteId); partTotal.remove(noteId)
        activeNote.remove(noteId)
        activeEngineOf.remove(noteId); stageOf.remove(noteId)
    }
}

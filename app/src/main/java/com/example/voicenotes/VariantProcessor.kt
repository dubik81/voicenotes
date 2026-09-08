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

    /** Отменяет обработку заметки (кнопка отмены). */
    fun cancel(noteId: Long) {
        jobs[noteId]?.cancel()
        jobs.remove(noteId)
        Diagnostics.action("Обработка ИИ отменена пользователем")
    }
    private val progressDone = mutableStateMapOf<Long, Int>()
    private val progressTotal = mutableStateMapOf<Long, Int>()
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
        return AiClient.processAll(text, settings.apiKey)
    }

    // Метка движка, которым получен последний результат (для истории версий).
    @Volatile private var lastEngine: String = ""
    private fun localLabel() = "на устройстве (${settings.localAiModel})"

    // ── Промпты локальной модели: КОРОТКИЕ и КОНКРЕТНЫЕ (1.5B теряется в длинных).
    private val LOCAL_CLEAN = "Исправь текст: расставь точки и запятые по смыслу, исправь ошибки распознавания. Все слова и смысл сохрани. Выведи только текст."
    private val LOCAL_BRIEF_CHUNK = "Перескажи коротко, в 1-2 предложениях, сохранив факты:"
    private val LOCAL_BRIEF = "Кратко перескажи этот текст в 2-3 предложениях. Факты не заменяй общими словами:"
    private val LOCAL_GIST = "О чём этот текст? Ответь одним-двумя предложениями, не искажая смысл:"
    private val LOCAL_LECTURE_BRIEF = "Это лекция. Кратко изложи её содержание в 3-4 предложениях:"
    private val LOCAL_LECTURE_GIST = "Это лекция. Одним-двумя предложениями: о чём она?"

    /** Локальное «Чисто»: модель по кускам; кусок, который модель исказила, — правилами. */
    private suspend fun localClean(note: Note, text: String, prompt: String = LOCAL_CLEAN): String {
        val googleCaps = note.recordMode == "google"
        stageOf[note.id] = "Чисто: модель на устройстве…"
        val res = LocalAiEngine.processLong(context, prompt, text, settings.localAiModel,
            onProgress = { d, t, _ -> progressDone[note.id] = d; progressTotal[note.id] = t
                stageOf[note.id] = "Чисто: часть $d из $t" },
            chunkOk = { chunk, r -> !tooDistorted(r, chunk) && !isCopyOrTruncation(r, chunk) })
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

    /** Локальная суммаризация (Кратко/Суть) с картой-свёрткой для длинного текста. */
    private suspend fun localSummary(note: Note, text: String, l: Level, lecture: Boolean): String {
        val finalPrompt = when {
            lecture && l == Level.BRIEF -> LOCAL_LECTURE_BRIEF
            lecture -> LOCAL_LECTURE_GIST
            l == Level.BRIEF -> LOCAL_BRIEF
            else -> LOCAL_GIST
        }
        val name = if (l == Level.BRIEF) "Кратко" else "Суть"
        stageOf[note.id] = "$name: модель на устройстве…"
        val r = LocalAiEngine.summarize(context, LOCAL_BRIEF_CHUNK, finalPrompt, text, settings.localAiModel,
            onProgress = { d, t, _ -> progressDone[note.id] = d; progressTotal[note.id] = t
                stageOf[note.id] = "$name: часть $d из $t" })
        val ok = !r.isNullOrBlank() && !isLoopy(r) && r.length >= (if (l == Level.BRIEF) 10 else 5) &&
            r.length < text.length
        return if (ok) {
            val limited = limitSentences(r!!, if (l == Level.GIST) 2 else 4)
            Diagnostics.engine("$l: локальный ИИ (суммаризация), ${limited.length} симв")
            lastEngine = localLabel()
            limited
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
        return AiClient.processLecture(text, settings.apiKey)
    }

    // Локальная обработка: по одному варианту (маленькой модели проще).
    // Чисто — модель по кускам (при искажении куска — правила), Кратко/Суть — суммаризация.
    // Уровни, где все тоны уже есть, НЕ пересчитываем (фон добирает только недостающее).
    private suspend fun localProcessAll(note: Note, text: String): Map<String, String> =
        localBatch(note, text, lecture = false)

    private suspend fun localProcessLecture(note: Note, text: String): Map<String, String> =
        localBatch(note, text, lecture = true)

    private suspend fun localBatch(note: Note, text: String, lecture: Boolean): Map<String, String> {
        val result = mutableMapOf<String, String>()
        fun missing(l: Level) = Tone.entries.any { note.getVariant(l, it) == null }
        for (l in listOf(Level.CLEAN, Level.BRIEF, Level.GIST)) {
            if (!missing(l)) continue
            val r = if (l == Level.CLEAN) localClean(note, text) else localSummary(note, text, l, lecture)
            // Заполняем ВСЕ тоны одинаково (локальная модель тон не различает).
            for (tn in Tone.entries) result["${l.ordinal}:${tn.ordinal}"] = r
            engineOf["${l.ordinal}"] = lastEngine
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
                        val srcText = note.refinedText ?: note.original
                        Diagnostics.info("В обработку ушёл текст (${srcText.length} симв): \"${srcText.take(50)}...\"")
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
                    }
                    attempt++
                    if (attempt < maxRetries &&
                        combos.any { (l, t) -> note.getVariant(l, t) == null }) {
                        delay(6000)
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

    /** Пересчитать ОДИН вариант заново («другой вариант» / не понравился). */
    fun regenerateOne(note: Note, l: Level, t: Tone, onDone: (Boolean) -> Unit) {
        if (note.original.isBlank() || l == Level.VERBATIM) return
        scope.launch {
            states[k(note.id, l, t)] = State.RUNNING
            var ok = false
            try {
                val text = computeOne(note, l, t, vary = true)
                note.putVariant(l, t, text, lastEngine)
                states[k(note.id, l, t)] = State.DONE
                persist()
                ok = true
                Diagnostics.engine("Обновлён вариант ($l/$t): ${text.length} симв, движок: $lastEngine")
            } catch (e: Exception) {
                states[k(note.id, l, t)] = State.FAILED
                lastAiError = e.message?.take(50)
                Diagnostics.error("Обновление варианта ($l) не удалось: ${e.message?.take(50)}")
            }
            stageOf.remove(note.id)
            onDone(ok)
        }
    }

    /** Вычисление одного варианта с проверкой длины. */
    private suspend fun computeOne(note: Note, l: Level, t: Tone, vary: Boolean = false): String {
        val orig = note.refinedText ?: note.original
        // Роутинг: локальный ИИ (если выбран офлайн) или облачный.
        if (settings.useAI && settings.localAi &&
            LocalAiModelManager.isReady(context, settings.localAiModel)) {
            return when (l) {
                Level.CLEAN -> {
                    // «Обновить» = другая формулировка задачи → другой результат.
                    val prompts = listOf(
                        LOCAL_CLEAN,
                        "Расставь знаки препинания по смыслу и исправь ошибки распознавания. Не убирай слова. Выведи только текст.",
                        "Раздели текст на предложения по смыслу, поставь точки и запятые, исправь окончания слов. Выведи только текст."
                    )
                    localClean(note, orig, if (vary) prompts.random() else prompts[0])
                }
                Level.VERBATIM -> { lastEngine = "правила"; Punctuator.punctuate(orig) }
                else -> localSummary(note, orig, l, note.isLecture)
            }
        }
        val result = if (settings.useAI) {
            lastEngine = "облако"
            AiClient.process(orig, l, t, settings.apiKey, vary)
        } else { lastEngine = "правила"; TextCondenser.condense(orig, l) }

        if (l != Level.VERBATIM && result.length > orig.length) {
            return if (settings.useAI) {
                try {
                    val shorter = AiClient.process(orig, l, t, settings.apiKey, vary = true)
                    if (shorter.length <= orig.length) capLength(shorter, orig, l) else TextCondenser.condense(orig, l)
                } catch (_: Exception) { TextCondenser.condense(orig, l) }
            } else TextCondenser.condense(orig, l)
        }
        return capLength(result, orig, l)
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
        activeNote.remove(noteId)
        activeEngineOf.remove(noteId); stageOf.remove(noteId)
    }
}

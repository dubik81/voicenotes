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
    private suspend fun processAllRouted(text: String): Map<String, String> {
        if (settings.localAi) {
            if (!LocalAiModelManager.isReady(context, settings.localAiModel)) {
                // Модели нет — работаем на надёжных правилах (без ИИ, но всегда результат).
                Diagnostics.engine("Офлайн без модели: обработка правилами")
                return rulesBasedAll(text)
            }
            Diagnostics.engine("Обработка вариантов: ЛОКАЛЬНЫЙ ИИ (+ правила как запас)")
            return localProcessAll(text)  // внутри есть fallback на правила
        }
        Diagnostics.engine("Обработка вариантов: ОБЛАЧНЫЙ ИИ")
        return AiClient.processAll(text, settings.apiKey)
    }

    // Полностью офлайн-обработка на правилах (без ИИ) — гарантированный результат.
    private fun rulesBasedAll(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val t = Tone.NEUTRAL.ordinal
        result["${Level.CLEAN.ordinal}:$t"] = CleanProcessor.clean(text)
        result["${Level.BRIEF.ordinal}:$t"] = TextCondenser.condense(text, Level.BRIEF)
        result["${Level.GIST.ordinal}:$t"] = TextCondenser.condense(text, Level.GIST)
        return result
    }

    private suspend fun processLectureRouted(text: String): Map<String, String> {
        if (settings.localAi) {
            if (!LocalAiModelManager.isReady(context, settings.localAiModel)) {
                Diagnostics.error("Локальный ИИ (лекция): модель не скачана")
                throw RuntimeException("Локальный ИИ: модель не скачана")
            }
            Diagnostics.engine("Стенограмма: ЛОКАЛЬНЫЙ ИИ")
            val res = localProcessLecture(text)
            if (res.isNotEmpty()) { Diagnostics.engine("Локальный ИИ (лекция) вернул ${res.size}"); return res }
            Diagnostics.error("Локальный ИИ (лекция) не дал результат (${LocalAiEngine.lastStatus})")
            throw RuntimeException("Локальный ИИ не дал результат (${LocalAiEngine.lastStatus})")
        }
        Diagnostics.engine("Стенограмма: ОБЛАЧНЫЙ ИИ")
        return AiClient.processLecture(text, settings.apiKey)
    }

    // Локальная обработка: маленькой модели проще делать по одному варианту,
    // чем большой JSON. Генерируем ключевые варианты по отдельности.
    private suspend fun localProcessAll(text: String): Map<String, String> {
        val model = settings.localAiModel
        val result = mutableMapOf<String, String>()
        fun okRes(r: String?, minLen: Int) = !r.isNullOrBlank() && !isLoopy(r) && r.length >= minLen
        // Дословно — как есть.
        for (tn in Tone.entries) result["${Level.VERBATIM.ordinal}:${tn.ordinal}"] = text
        // КРАТКО и СУТЬ — РОДНАЯ задача модели (суммаризация, для чего Meta её создала).
        val b = LocalAiEngine.generate(context,
            "Перескажи ВЕСЬ этот текст кратко, охватив все основные моменты С НАЧАЛА до конца, в 2-3 предложениях. Не пропускай начало:", text, model)
        val bRes = if (okRes(b, 10)) limitSentences(b!!, 4) else TextCondenser.condense(text, Level.BRIEF)
        val g = LocalAiEngine.generate(context,
            "Одним предложением опиши, о чём ВЕСЬ этот текст (охвати главное, не только конец):", text, model)
        val gRes = if (okRes(g, 5)) limitSentences(g!!, 2) else TextCondenser.condense(text, Level.GIST)
        // Заполняем ВСЕ тоны одинаково (локальная модель тон не различает).
        for (tn in Tone.entries) {
            result["${Level.CLEAN.ordinal}:${tn.ordinal}"] = CleanProcessor.clean(text)
            result["${Level.BRIEF.ordinal}:${tn.ordinal}"] = bRes
            result["${Level.GIST.ordinal}:${tn.ordinal}"] = gRes
        }
        return result
    }

    private suspend fun localProcessLecture(text: String): Map<String, String> {
        val model = settings.localAiModel
        val result = mutableMapOf<String, String>()
        fun okRes(r: String?, minLen: Int) = !r.isNullOrBlank() && !isLoopy(r) && r.length >= minLen
        // По назначению модели: Чисто (стенограмма) — правила (модель выдумывает).
        // Кратко/Суть (конспект лекции) — суммаризация, родная задача модели.
        val cleanText = CleanProcessor.clean(text)
        val b = LocalAiEngine.generate(context,
            "Это лекция. Кратко изложи её содержание в 3-4 предложениях (конспект):", text, model)
        val bRes = if (okRes(b, 10)) limitSentences(b!!, 5) else TextCondenser.condense(text, Level.BRIEF)
        val g = LocalAiEngine.generate(context,
            "Это лекция. Одним предложением: о чём она?", text, model)
        val gRes = if (okRes(g, 5)) limitSentences(g!!, 2) else TextCondenser.condense(text, Level.GIST)
        // Все тоны одинаково.
        for (tn in Tone.entries) {
            result["${Level.CLEAN.ordinal}:${tn.ordinal}"] = cleanText
            result["${Level.BRIEF.ordinal}:${tn.ordinal}"] = bRes
            result["${Level.GIST.ordinal}:${tn.ordinal}"] = gRes
        }
        return result
    }

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
        activeNote[note.id] = true

        jobs[note.id] = scope.launch {
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
                        val all = if (note.isLecture)
                            processLectureRouted(note.refinedText ?: note.original)
                        else
                            processAllRouted(note.refinedText ?: note.original)
                        Diagnostics.info("В обработку ушёл текст (${note.original.length} симв): \"${note.original.take(50)}...\"")
                        // Умный заголовок стенограммы от ИИ.
                        all["TITLE"]?.takeIf { it.isNotBlank() }?.let { note.title = it }
                        for ((l, t) in combos) {
                            val key = "${l.ordinal}:${t.ordinal}"
                            val text = all[key]
                            if (text != null && text.isNotBlank()) {
                                note.putVariant(l, t, text)
                                states[k(note.id, l, t)] = State.DONE
                            }
                        }
                        // «Дословно» (VERBATIM) НЕ трогаем — оно всегда исходный текст,
                        // не меняется после ИИ (требование пользователя).
                        progressDone[note.id] = combos.count { (l, t) -> note.getVariant(l, t) != null }
                        persist()
                        val allDone = combos.all { (l, t) -> note.getVariant(l, t) != null }
                        if (allDone) break
                        // Пришло частично: недостающие ставим «в очередь» (не висящие часы),
                        // добор пойдёт следующим проходом.
                        for ((l, t) in combos) {
                            if (note.getVariant(l, t) == null) states[k(note.id, l, t)] = State.QUEUED
                        }
                    } catch (e: Exception) {
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
                        note.putVariant(l, t, TextCondenser.condense(note.original, l))
                        states[k(note.id, l, t)] = State.DONE
                    }
                }
                progressDone[note.id] = combos.size
                persist()
            }
            activeNote[note.id] = false
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
                note.putVariant(l, t, text)
                states[k(note.id, l, t)] = State.DONE
                persist()
                ok = true
                Diagnostics.engine("Обновлён вариант ($l): ${text.length} симв")
            } catch (e: Exception) {
                states[k(note.id, l, t)] = State.FAILED
                lastAiError = e.message?.take(50)
                Diagnostics.error("Обновление варианта ($l) не удалось: ${e.message?.take(50)}")
            }
            onDone(ok)
        }
    }

    /** Вычисление одного варианта с проверкой длины. */
    private suspend fun computeOne(note: Note, l: Level, t: Tone, vary: Boolean = false): String {
        val orig = note.refinedText ?: note.original
        // Роутинг: локальный ИИ (если выбран офлайн) или облачный.
        if (settings.useAI && settings.localAi &&
            LocalAiModelManager.isReady(context, settings.localAiModel)) {
            // По назначению модели Meta: суммаризация (Кратко/Суть) — её задача,
            // дословное редактирование (Чисто) — НЕ её (выдумывает) → правила.
            when (l) {
                Level.CLEAN -> {
                    // «Обновить» (vary) чередует промпты — Qwen даёт РАЗНЫЕ вариации.
                    // ВАЖНО: пунктуацию распознавания НЕ сохранять слепо — она часто кривая
                    // (точки посреди предложения). Исправлять по смыслу.
                    val prompts = listOf(
                        "Исправь этот текст: убери лишние и неправильные знаки препинания, расставь правильные по смыслу, исправь окончания слов. Знаки препинания из распознавания могут быть ошибочны — ставь по смыслу:",
                        "Отредактируй текст грамотно: правильная пунктуация по смыслу (не доверяй точкам посреди предложений), верные окончания, заглавные буквы:",
                        "Приведи в порядок: раздели на правильные предложения по смыслу, исправь пунктуацию и окончания. Не сохраняй ошибочные знаки препинания:"
                    )
                    val prompt = if (vary) prompts.random() else prompts[0]
                    val res = LocalAiEngine.generate(context, prompt, orig, settings.localAiModel)
                    if (!res.isNullOrBlank() && !isLoopy(res) && !tooDistorted(res, orig) &&
                        res.length <= orig.length * 2) {
                        Diagnostics.engine("Чисто: локальная модель (${res.length} симв)")
                        return res
                    }
                    Diagnostics.engine("Чисто: модель исказила → правила")
                    return CleanProcessor.clean(orig)
                }
                Level.VERBATIM -> return Punctuator.punctuate(orig)
                else -> {
                    val prompt = if (l == Level.BRIEF)
                        "Перескажи ВЕСЬ этот текст кратко, охватив все основные моменты С НАЧАЛА до конца, в 2-3 предложениях. Не пропускай начало:"
                    else "Одним предложением опиши, о чём ВЕСЬ этот текст (охвати главное, не только конец):"
                    val res = LocalAiEngine.generate(context, prompt, orig, settings.localAiModel)
                    if (!res.isNullOrBlank() && !isLoopy(res)) {
                        // Ограничиваем длину: Суть — до 1-2 предложений, Кратко — до 3-4.
                        val limited = limitSentences(res, if (l == Level.GIST) 2 else 4)
                        Diagnostics.engine("$l: локальный ИИ (суммаризация), ${limited.length} симв")
                        return limited
                    }
                    return TextCondenser.condense(orig, l)
                }
            }
        }
        val result = if (settings.useAI)
            AiClient.process(orig, l, t, settings.apiKey, vary)
        else TextCondenser.condense(orig, l)

        if (l != Level.VERBATIM && result.length > orig.length) {
            return if (settings.useAI) {
                try {
                    val shorter = AiClient.process(orig, l, t, settings.apiKey, vary = true)
                    if (shorter.length <= orig.length) shorter else TextCondenser.condense(orig, l)
                } catch (_: Exception) { TextCondenser.condense(orig, l) }
            } else TextCondenser.condense(orig, l)
        }
        return result
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

    // Короткий промпт для одного варианта (локальный ИИ).
    // Чёткие промпты: инструкция в system, текст в user — модель понимает границу.
    private fun localPromptFor(l: Level, lecture: Boolean): String = when (l) {
        Level.VERBATIM -> "Ты редактор. Добавь в текст пользователя знаки препинания и заглавные буквы. Сохрани все слова. Выведи только исправленный текст, без пояснений."
        Level.CLEAN -> "Ты редактор. Перепиши текст пользователя грамотно и связно: убери слова-паразиты и повторы, исправь ошибки, сохрани весь смысл. Выведи только результат, без пояснений."
        Level.BRIEF -> "Ты редактор. Кратко перескажи главное из текста пользователя в 2-3 предложениях. Выведи только пересказ, без пояснений."
        Level.GIST -> "Ты редактор. Одним предложением напиши, о чём текст пользователя. Выведи только это предложение."
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
    }
}

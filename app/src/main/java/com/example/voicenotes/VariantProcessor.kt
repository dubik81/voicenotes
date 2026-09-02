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
        for (tn in Tone.entries) result["${Level.VERBATIM.ordinal}:${tn.ordinal}"] = Punctuator.punctuate(text)
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
        val t = Tone.NEUTRAL.ordinal
        fun okRes(r: String?, minLen: Int) = !r.isNullOrBlank() && !isLoopy(r) && r.length >= minLen
        // Дословно
        val v = LocalAiEngine.generate(context, localPromptFor(Level.VERBATIM, false), text, model)
        val vClean = if (okRes(v, text.length / 3)) v!! else Punctuator.punctuate(text)
        for (tn in Tone.entries) result["${Level.VERBATIM.ordinal}:${tn.ordinal}"] = vClean
        // ЧИСТО — гибрид: правила чистят, ИИ полирует
        val cInput = CleanProcessor.clean(text)
        val c = LocalAiEngine.generate(context, localPromptFor(Level.CLEAN, false), cInput, model)
        result["${Level.CLEAN.ordinal}:$t"] = if (okRes(c, cInput.length / 3)) c!! else cInput
        // Кратко
        val b = LocalAiEngine.generate(context, localPromptFor(Level.BRIEF, false), text, model)
        result["${Level.BRIEF.ordinal}:$t"] = if (okRes(b, 10)) b!! else TextCondenser.condense(text, Level.BRIEF)
        // Суть
        val g = LocalAiEngine.generate(context, localPromptFor(Level.GIST, false), text, model)
        result["${Level.GIST.ordinal}:$t"] = if (okRes(g, 5)) g!! else TextCondenser.condense(text, Level.GIST)
        return result
    }

    private suspend fun localProcessLecture(text: String): Map<String, String> {
        val model = settings.localAiModel
        val result = mutableMapOf<String, String>()
        val t = Tone.NEUTRAL.ordinal
        LocalAiEngine.generate(context,
            "Это лекция. Оформи как стенограмму: исправь ошибки распознавания, расставь пунктуацию, абзацы. Сохрани всё содержание и слова. Верни только текст.",
            text, model)?.let { result["${Level.CLEAN.ordinal}:$t"] = it }
        LocalAiEngine.generate(context,
            "Это лекция. Изложи ЕЁ СОДЕРЖАНИЕ кратко (не рассказывай о лекции, а сожми саму лекцию). Верни только текст.",
            text, model)?.let { result["${Level.BRIEF.ordinal}:$t"] = it }
        LocalAiEngine.generate(context,
            "Это лекция. Изложи её содержание максимально коротко, самую суть. Верни только текст.",
            text, model)?.let { result["${Level.GIST.ordinal}:$t"] = it }
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
                            processLectureRouted(note.original)
                        else
                            processAllRouted(note.original)
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
                        // Дословный вариант от ИИ (умная пунктуация) — сохраняем отдельно.
                        if (!note.isLecture) {
                            val vKey = "${Level.VERBATIM.ordinal}:${Tone.NEUTRAL.ordinal}"
                            all[vKey]?.takeIf { it.isNotBlank() }?.let {
                                note.putVariant(Level.VERBATIM, Tone.NEUTRAL, it)
                                // для всех тонов дословный одинаковый
                                for (t in Tone.entries) note.putVariant(Level.VERBATIM, t, it)
                            }
                        }
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
        val orig = note.original
        // Роутинг: локальный ИИ (если выбран офлайн) или облачный.
        if (settings.useAI && settings.localAi &&
            LocalAiModelManager.isReady(context, settings.localAiModel)) {
            // Для CLEAN: сначала правила убирают паразитов (надёжно), затем локальный ИИ
            // полирует уже ЧИСТЫЙ текст — слабой модели так проще, результат лучше.
            val input = if (l == Level.CLEAN) CleanProcessor.clean(orig) else orig
            val sys = localPromptFor(l, note.isLecture)
            val res = LocalAiEngine.generate(context, sys, input, settings.localAiModel)
            if (!res.isNullOrBlank() && !isLoopy(res) && res.length >= input.length / 3) {
                Diagnostics.engine("Один вариант ($l): локальный ИИ, ${res.length} симв")
                return res
            }
            // Локальный слаб на этом тексте → надёжный результат по правилам.
            Diagnostics.engine("$l: локальный слаб → правила")
            return when (l) {
                Level.CLEAN -> CleanProcessor.clean(orig)
                Level.BRIEF -> TextCondenser.condense(orig, Level.BRIEF)
                Level.GIST -> TextCondenser.condense(orig, Level.GIST)
                else -> Punctuator.punctuate(orig)
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
    private fun localPromptFor(l: Level, lecture: Boolean): String = when (l) {
        Level.VERBATIM -> "Перепиши грамотно, добавь знаки препинания. Верни только текст."
        Level.CLEAN -> "Перепиши этот текст грамотно и связно, теми же словами. Только текст."
        Level.BRIEF -> "Перескажи это в 2-3 предложениях. Только текст."
        Level.GIST -> "О чём это? Ответь одним предложением. Только текст."
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

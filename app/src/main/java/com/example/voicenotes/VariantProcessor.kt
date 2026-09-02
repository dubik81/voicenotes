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
            // Офлайн-режим: работаем ТОЛЬКО локально. Откат на облако запрещён —
            // сбой облака штатен, а неработа офлайна недопустима и должна быть видна.
            if (!LocalAiModelManager.isReady(context, settings.localAiModel)) {
                Diagnostics.error("Локальный ИИ: модель не скачана")
                throw RuntimeException("Локальный ИИ: модель не скачана")
            }
            Diagnostics.engine("Обработка вариантов: ЛОКАЛЬНЫЙ ИИ")
            val res = localProcessAll(text)
            if (res.isNotEmpty()) { Diagnostics.engine("Локальный ИИ вернул ${res.size} вариантов"); return res }
            Diagnostics.error("Локальный ИИ не дал результат (${LocalAiEngine.lastStatus})")
            throw RuntimeException("Локальный ИИ не дал результат (${LocalAiEngine.lastStatus})")
        }
        Diagnostics.engine("Обработка вариантов: ОБЛАЧНЫЙ ИИ")
        return AiClient.processAll(text, settings.apiKey)
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
        // Дословный с умной пунктуацией
        LocalAiEngine.generate(context,
            "Расставь пунктуацию и заглавные буквы по смыслу, сохрани ВСЕ слова. Верни только текст.",
            text, model)?.let { for (tn in Tone.entries) result["${Level.VERBATIM.ordinal}:${tn.ordinal}"] = it }
        // Чисто
        LocalAiEngine.generate(context,
            "Исправь ошибки распознавания, расставь пунктуацию, сохрани все мысли и слова. Не сокращай. Верни только текст.",
            text, model)?.let { result["${Level.CLEAN.ordinal}:$t"] = it }
        // Кратко
        LocalAiEngine.generate(context,
            "Перескажи кратко, вдвое короче, сохрани главное. Верни только текст.",
            text, model)?.let { result["${Level.BRIEF.ordinal}:$t"] = it }
        // Суть
        LocalAiEngine.generate(context,
            "Изложи суть в 1-2 предложениях. Верни только текст.",
            text, model)?.let { result["${Level.GIST.ordinal}:$t"] = it }
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
    fun regenerateOne(note: Note, l: Level, t: Tone, onDone: () -> Unit) {
        if (note.original.isBlank() || l == Level.VERBATIM) return
        scope.launch {
            states[k(note.id, l, t)] = State.RUNNING
            try {
                val text = computeOne(note, l, t, vary = true)
                note.putVariant(l, t, text)
                states[k(note.id, l, t)] = State.DONE
                persist()
            } catch (e: Exception) {
                states[k(note.id, l, t)] = State.FAILED
            }
            onDone()
        }
    }

    /** Вычисление одного варианта с проверкой длины. */
    private suspend fun computeOne(note: Note, l: Level, t: Tone, vary: Boolean = false): String {
        val orig = note.original
        // Роутинг: локальный ИИ (если выбран офлайн) или облачный.
        if (settings.useAI && settings.localAi &&
            LocalAiModelManager.isReady(context, settings.localAiModel)) {
            val sys = localPromptFor(l, note.isLecture)
            val res = LocalAiEngine.generate(context, sys, orig, settings.localAiModel)
            // Отсекаем галлюцинации (зацикливание) и мусор.
            if (!res.isNullOrBlank() && !isLoopy(res)) {
                Diagnostics.engine("Один вариант ($l): локальный ИИ, ${res.length} симв")
                return res
            }
            Diagnostics.error("Один вариант ($l): локальный дал мусор/пусто")
            throw RuntimeException("Локальный ИИ не дал результат (${LocalAiEngine.lastStatus})")
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

    // Детект зацикливания (галлюцинация локальной модели).
    private fun isLoopy(text: String): Boolean {
        val words = text.split(Regex("\\s+")).filter { it.length > 2 }
        if (words.size < 8) return false
        val triples = HashMap<String, Int>()
        for (i in 0..words.size - 3) {
            val key = "${words[i]} ${words[i+1]} ${words[i+2]}".lowercase()
            val c = (triples[key] ?: 0) + 1
            triples[key] = c
            if (c >= 3) return true
        }
        return false
    }

    // Короткий промпт для одного варианта (локальный ИИ).
    private fun localPromptFor(l: Level, lecture: Boolean): String = when (l) {
        Level.VERBATIM -> "Расставь пунктуацию по смыслу, сохрани все слова. Верни только текст."
        Level.CLEAN -> if (lecture) "Оформи как стенограмму, сохрани всё содержание. Верни только текст."
            else "Исправь ошибки распознавания, расставь пунктуацию, сохрани все мысли. Верни только текст."
        Level.BRIEF -> "Перескажи кратко, вдвое короче, сохрани главное. Верни только текст."
        Level.GIST -> "Изложи суть в 1-2 предложениях. Верни только текст."
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

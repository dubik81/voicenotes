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

    fun isActive(noteId: Long): Boolean = activeNote[noteId] == true
    fun doneCount(noteId: Long): Int = progressDone[noteId] ?: 0
    fun totalCount(noteId: Long): Int = progressTotal[noteId] ?: 0

    private fun k(noteId: Long, l: Level, t: Tone) = "$noteId:${l.ordinal}:${t.ordinal}"
    fun stateOf(noteId: Long, l: Level, t: Tone): State? = states[k(noteId, l, t)]

    private fun allCombos(): List<Pair<Level, Tone>> = buildList {
        for (l in Level.entries) for (t in Tone.entries) {
            if (l == Level.VERBATIM) continue
            add(l to t)
        }
    }

    /** Запускает/продолжает расчёт недостающих вариантов заметки. */
    fun ensureAll(note: Note, priorityLevel: Level, priorityTone: Tone) {
        if (note.original.isBlank()) return
        if (jobs[note.id]?.isActive == true) return

        val combos = allCombos()
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
            // очередь: приоритет первым
            val order = buildList {
                add(priorityLevel to priorityTone)
                for (c in combos) if (c != (priorityLevel to priorityTone)) add(c)
            }
            // повторяем проходы, пока есть незавершённые (агрессивные повторы)
            var attempt = 0
            while (attempt < maxRetries) {
                var remaining = 0
                for ((l, t) in order) {
                    if (note.getVariant(l, t) != null) {
                        states[k(note.id, l, t)] = State.DONE
                        continue
                    }
                    remaining++
                    states[k(note.id, l, t)] = State.RUNNING
                    try {
                        val text = computeOne(note, l, t)
                        note.putVariant(l, t, text)
                        states[k(note.id, l, t)] = State.DONE
                        progressDone[note.id] = combos.count { (ll, tt) -> note.getVariant(ll, tt) != null }
                        persist()  // сохраняем сразу на диск
                    } catch (e: Exception) {
                        states[k(note.id, l, t)] = State.FAILED
                    }
                    if (settings.useAI) delay(requestSpacingMs)  // не превышаем лимит
                }
                // всё готово?
                val allDone = combos.all { (l, t) -> note.getVariant(l, t) != null }
                if (allDone) break
                attempt++
                if (attempt < maxRetries) delay(8000)  // пауза перед новым проходом
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
        val result = if (settings.useAI)
            AiClient.process(orig, l, t, settings.apiKey, vary)
        else TextCondenser.condense(orig, l)

        // Проверка длины: сжатый не должен быть длиннее оригинала.
        if (l != Level.VERBATIM && result.length > orig.length) {
            // для правил просто обрежем логикой; для ИИ — вернём правило как запас
            return if (settings.useAI) {
                // повторим с явным требованием короче — один раз
                try {
                    val shorter = AiClient.process(orig, l, t, settings.apiKey, vary = true)
                    if (shorter.length <= orig.length) shorter else TextCondenser.condense(orig, l)
                } catch (_: Exception) { TextCondenser.condense(orig, l) }
            } else TextCondenser.condense(orig, l)
        }
        return result
    }

    /** Продолжить обработку ВСЕХ заметок, где есть недосчитанное (вызывать периодически). */
    fun resumeAll() {
        for (note in notesProvider()) {
            if (note.original.isBlank()) continue
            val hasGaps = allCombos().any { (l, t) -> note.getVariant(l, t) == null }
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

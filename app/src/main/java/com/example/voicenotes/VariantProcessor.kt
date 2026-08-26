package com.example.voicenotes

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Фоновый расчёт всех вариантов (ступень × тон) для заметок.
 * Живёт на уровне приложения, поэтому НЕ прерывается при закрытии заметки.
 *
 * Статусы вариантов доступны реактивно через statusOf(), чтобы кнопки
 * могли показывать «в очереди / считается / готово».
 */
class VariantProcessor(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val onNoteUpdated: () -> Unit
) {
    enum class State { QUEUED, RUNNING, DONE, FAILED }

    // Ключ "noteId:levelOrdinal:toneOrdinal" -> состояние
    private val states = mutableStateMapOf<String, State>()
    // Активные задачи по noteId, чтобы не запускать дважды.
    private val jobs = mutableMapOf<Long, Job>()

    private fun k(noteId: Long, l: Level, t: Tone) = "$noteId:${l.ordinal}:${t.ordinal}"

    fun stateOf(noteId: Long, l: Level, t: Tone): State? = states[k(noteId, l, t)]

    /**
     * Запускает расчёт всех недостающих вариантов заметки, если ещё не запущен.
     * Порядок: сначала текущие (переданные priority), потом всё остальное.
     */
    fun ensureAll(note: Note, priorityLevel: Level, priorityTone: Tone) {
        if (note.original.isBlank()) return
        if (jobs[note.id]?.isActive == true) return  // уже идёт

        // помечаем недостающие как QUEUED
        for (l in Level.entries) for (t in Tone.entries) {
            if (l == Level.VERBATIM) continue
            val key = k(note.id, l, t)
            if (note.getVariant(l, t) == null && states[key] == null) states[key] = State.QUEUED
        }

        jobs[note.id] = scope.launch {
            // очередь: сначала приоритетная комбинация, затем остальные
            val order = buildList {
                add(priorityLevel to priorityTone)
                for (l in Level.entries) for (t in Tone.entries) {
                    if (l == Level.VERBATIM) continue
                    val pair = l to t
                    if (pair != (priorityLevel to priorityTone)) add(pair)
                }
            }
            for ((l, t) in order) {
                if (l == Level.VERBATIM) continue
                if (note.getVariant(l, t) != null) { states[k(note.id, l, t)] = State.DONE; continue }
                states[k(note.id, l, t)] = State.RUNNING
                try {
                    val text = if (settings.useAI)
                        AiClient.process(note.original, l, t, settings.apiKey)
                    else TextCondenser.condense(note.original, l)
                    note.putVariant(l, t, text)
                    states[k(note.id, l, t)] = State.DONE
                    onNoteUpdated()
                } catch (e: Exception) {
                    states[k(note.id, l, t)] = State.FAILED
                }
            }
        }
    }

    /** Сбросить статусы и варианты заметки (после новой записи). */
    fun reset(noteId: Long) {
        jobs[noteId]?.cancel()
        jobs.remove(noteId)
        val prefix = "$noteId:"
        states.keys.filter { it.startsWith(prefix) }.forEach { states.remove(it) }
    }
}

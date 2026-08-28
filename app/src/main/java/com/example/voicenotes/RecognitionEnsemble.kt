package com.example.voicenotes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile

/**
 * Офлайн-ансамбль распознавания (эксперимент).
 * Идея: прогнать записанное аудио через Vosk несколько раз с разной
 * обработкой (оригинал + усиленная громкость), затем выбрать лучший
 * результат голосованием по полноте/длине. Помогает вытащить слова,
 * которые движок «проглотил» на тихих/быстрых участках.
 *
 * Всё офлайн, без запросов к ИИ.
 */
object RecognitionEnsemble {

    private const val sampleRate = 16000

    /**
     * Перераспознаёт WAV-файл несколькими способами и возвращает лучший текст.
     * thorough=true — «дотошный» режим (кнопка «Обновить текст»): больше прогонов.
     * Если ансамбль не дал улучшения — вернёт null.
     */
    suspend fun refine(model: Model, wav: File, thorough: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!wav.exists() || wav.length() < 44) return@withContext null
        val pcm = readPcm(wav) ?: return@withContext null

        val candidates = mutableListOf<String>()
        // нормализация громкости — ключевое для тихих записей
        val normalized = normalize(pcm)
        candidates.add(recognizePcm(model, normalized))
        candidates.add(recognizePcm(model, pcm))               // оригинал
        candidates.add(recognizePcm(model, amplify(pcm, 2.0))) // усиление x2

        if (thorough) {
            // дотошный режим: ещё варианты обработки
            candidates.add(recognizePcm(model, amplify(normalized, 1.3)))
            candidates.add(recognizePcm(model, amplify(pcm, 3.0)))
            candidates.add(recognizePcm(model, amplify(pcm, 1.5)))
        }

        // Голосование: самый полный результат (больше всего слов).
        val best = candidates
            .filter { it.isNotBlank() }
            .maxByOrNull { it.split(Regex("\\s+")).size }
            ?: return@withContext null

        return@withContext best.ifBlank { null }
    }

    /** Нормализация: усиливаем сигнал так, чтобы пик достигал ~85% максимума. */
    private fun normalize(pcm: ShortArray): ShortArray {
        var peak = 1
        for (s in pcm) { val a = kotlin.math.abs(s.toInt()); if (a > peak) peak = a }
        val target = (32767 * 0.85).toInt()
        val factor = target.toDouble() / peak
        if (factor <= 1.05) return pcm  // уже достаточно громко
        return amplify(pcm, factor)
    }

    private fun readPcm(wav: File): ShortArray? = try {
        val raf = RandomAccessFile(wav, "r")
        raf.seek(44) // пропускаем WAV-заголовок
        val len = (raf.length() - 44).toInt()
        val bytes = ByteArray(len)
        raf.readFully(bytes)
        raf.close()
        val shorts = ShortArray(len / 2)
        var i = 0
        while (i + 1 < len) {
            shorts[i / 2] = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort()
            i += 2
        }
        shorts
    } catch (_: Exception) { null }

    private fun amplify(pcm: ShortArray, factor: Double): ShortArray {
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val v = (pcm[i] * factor).toInt().coerceIn(-32768, 32767)
            out[i] = v.toShort()
        }
        return out
    }

    private fun recognizePcm(model: Model, pcm: ShortArray): String {
        val recognizer = Recognizer(model, sampleRate.toFloat())
        try {
            // кормим кусками
            val chunk = 4000
            val bytes = ByteArray(chunk * 2)
            var pos = 0
            val sb = StringBuilder()
            while (pos < pcm.size) {
                val end = minOf(pos + chunk, pcm.size)
                var bi = 0
                for (i in pos until end) {
                    bytes[bi++] = (pcm[i].toInt() and 0xff).toByte()
                    bytes[bi++] = ((pcm[i].toInt() shr 8) and 0xff).toByte()
                }
                if (recognizer.acceptWaveForm(bytes, bi)) {
                    val r = JSONObject(recognizer.result).optString("text", "")
                    if (r.isNotBlank()) sb.append(r).append(" ")
                }
                pos = end
            }
            val fin = JSONObject(recognizer.finalResult).optString("text", "")
            if (fin.isNotBlank()) sb.append(fin)
            return sb.toString().trim()
        } finally {
            recognizer.close()
        }
    }
}

package com.example.voicenotes

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Офлайн-распознавание Vosk с ОДНОВРЕМЕННОЙ записью аудио в WAV.
 * Один поток с AudioRecord: каждый буфер идёт и в Vosk, и (опционально) в файл.
 *
 * Колбэки:
 *  onPartial(text) — промежуточный текст (живой)
 *  onFinal(text)   — финальная фраза (после паузы)
 *  onError(msg)
 */
class VoskEngine(
    private val model: Model,
    private val saveAudioTo: File?
) {
    private val sampleRate = 16000
    @Volatile private var running = false
    private var recordThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (running) return
        running = true

        recordThread = thread(start = true) {
            var audioRecord: AudioRecord? = null
            var recognizer: Recognizer? = null
            var wav: RandomAccessFile? = null
            var pcmBytes = 0L
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBuf, sampleRate) // ~1 сек буфер
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
                recognizer = Recognizer(model, sampleRate.toFloat())

                // Готовим WAV с временным заголовком (перезапишем размеры в конце).
                if (saveAudioTo != null) {
                    wav = RandomAccessFile(saveAudioTo, "rw").apply {
                        setLength(0)
                        writeWavHeader(this, sampleRate, 0)
                    }
                }

                val buffer = ByteArray(bufSize)
                audioRecord.startRecording()

                while (running) {
                    val n = audioRecord.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        // в файл
                        if (wav != null) {
                            wav.write(buffer, 0, n)
                            pcmBytes += n
                        }
                        // в распознавание
                        if (recognizer.acceptWaveForm(buffer, n)) {
                            val res = recognizer.result
                            val text = JSONObject(res).optString("text", "")
                            if (text.isNotBlank()) onFinal(text)
                        } else {
                            val partial = JSONObject(recognizer.partialResult)
                                .optString("partial", "")
                            if (partial.isNotBlank()) onPartial(partial)
                        }
                    }
                }

                // финальный «хвост»
                val finalRes = JSONObject(recognizer.finalResult).optString("text", "")
                if (finalRes.isNotBlank()) onFinal(finalRes)

            } catch (e: Exception) {
                onError(e.message ?: "Ошибка распознавания Vosk")
            } finally {
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                try { recognizer?.close() } catch (_: Exception) {}
                if (wav != null) {
                    try {
                        // дописываем реальные размеры в заголовок WAV
                        writeWavHeader(wav, sampleRate, pcmBytes)
                        wav.close()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun stop() {
        running = false
        try { recordThread?.join(2000) } catch (_: Exception) {}
        recordThread = null
    }

    private fun writeWavHeader(f: RandomAccessFile, sr: Int, pcmLen: Long) {
        val byteRate = sr * 2
        val totalDataLen = pcmLen + 36
        f.seek(0)
        f.writeBytes("RIFF")
        f.write(intLE(totalDataLen.toInt()))
        f.writeBytes("WAVE")
        f.writeBytes("fmt ")
        f.write(intLE(16))               // subchunk1 size
        f.write(shortLE(1))              // PCM
        f.write(shortLE(1))              // channels
        f.write(intLE(sr))
        f.write(intLE(byteRate))
        f.write(shortLE(2))             // block align
        f.write(shortLE(16))            // bits per sample
        f.writeBytes("data")
        f.write(intLE(pcmLen.toInt()))
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte()
    )
    private fun shortLE(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte()
    )
}

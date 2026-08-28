package com.example.voicenotes

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Универсальная запись аудио в WAV, независимая от движка распознавания.
 * Используется в Google-режиме (параллельно с распознаванием) и как основа
 * для детекта громких участков (идея «караоке»).
 *
 * ВНИМАНИЕ: в Google-режиме микрофон может быть занят распознавателем —
 * тогда запись не запустится (ловим исключение и просто не пишем аудио).
 */
class AudioRecorder(private val outFile: File, private val appendMode: Boolean) {
    private val sampleRate = 16000
    @Volatile private var running = false
    private var recordThread: Thread? = null

    // Профиль громкости: список (времяМс, rms) для детекта пропусков.
    val volumeProfile = mutableListOf<Pair<Long, Double>>()
    @Volatile var started = false
        private set

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        return try {
            running = true
            recordThread = thread(start = true) { recordLoop() }
            started = true
            true
        } catch (e: Exception) {
            running = false
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun recordLoop() {
        var audioRecord: AudioRecord? = null
        var wav: RandomAccessFile? = null
        var pcmBytes = 0L
        val startTime = System.currentTimeMillis()
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(minBuf, sampleRate)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)

            val exists = appendMode && outFile.exists() && outFile.length() > 44
            if (exists) {
                wav = RandomAccessFile(outFile, "rw")
                pcmBytes = outFile.length() - 44
                wav.seek(outFile.length())
            } else {
                wav = RandomAccessFile(outFile, "rw").apply {
                    setLength(0); writeWavHeader(this, sampleRate, 0)
                }
            }

            val buffer = ByteArray(bufSize)
            audioRecord.startRecording()
            while (running) {
                val n = audioRecord.read(buffer, 0, buffer.size)
                if (n > 0) {
                    // громкость
                    var sum = 0.0; var i = 0
                    while (i + 1 < n) {
                        val s = (buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)
                        sum += (s * s).toDouble(); i += 2
                    }
                    val rms = if (n > 1) Math.sqrt(sum / (n / 2)) else 0.0
                    volumeProfile.add((System.currentTimeMillis() - startTime) to rms)
                    wav.write(buffer, 0, n)
                    pcmBytes += n
                }
            }
        } catch (_: Exception) {
            // микрофон занят (Google держит) — тихо выходим
        } finally {
            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            if (wav != null) {
                try { writeWavHeader(wav, sampleRate, pcmBytes); wav.close() } catch (_: Exception) {}
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
        f.seek(0)
        f.writeBytes("RIFF"); f.write(intLE((pcmLen + 36).toInt())); f.writeBytes("WAVE")
        f.writeBytes("fmt "); f.write(intLE(16)); f.write(shortLE(1)); f.write(shortLE(1))
        f.write(intLE(sr)); f.write(intLE(byteRate)); f.write(shortLE(2)); f.write(shortLE(16))
        f.writeBytes("data"); f.write(intLE(pcmLen.toInt()))
    }
    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())
    private fun shortLE(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
}

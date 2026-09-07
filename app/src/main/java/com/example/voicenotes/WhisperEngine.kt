package com.example.voicenotes

import android.content.Context
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import dev.ffmpegkit.whisper.WhisperModel

/**
 * Обёртка над whisper-android: точное офлайн-распознавание записанного файла.
 * Модель загружается один раз и кэшируется между вызовами.
 */
object WhisperEngine {

    @Volatile private var loadedModel: WhisperModel? = null
    @Volatile private var loadedModelId: String? = null

    /**
     * Распознаёт WAV-файл через Whisper. Возвращает текст или null при ошибке.
     * modelId — "tiny"/"base"/"small".
     */
    suspend fun transcribe(context: Context, wavPath: String, modelId: String): String? {
        return try {
            val modelPath = WhisperModelManager.modelFile(context, modelId).absolutePath
            val model = if (loadedModel != null && loadedModelId == modelId) {
                loadedModel!!
            } else {
                releaseCurrent()
                val m = Whisper.loadModel(context, modelPath)
                loadedModel = m
                loadedModelId = modelId
                m
            }
            // Предобработка звука (нормализация громкости + шумоподавление) —
            // помогает распознаванию тихой/шумной записи.
            val processedPath = try {
                preprocessWav(context, wavPath)
            } catch (_: Exception) { wavPath }
            val result = Whisper.transcribe(model, processedPath, WhisperConfig(language = "ru"))
            result.text?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    // Читает WAV (16-bit PCM моно), применяет предобработку, пишет новый WAV.
    private fun preprocessWav(context: Context, wavPath: String): String {
        val bytes = java.io.File(wavPath).readBytes()
        if (bytes.size < 44) return wavPath  // нет данных
        // PCM данные после 44-байтового заголовка WAV.
        val pcmBytes = bytes.copyOfRange(44, bytes.size)
        val samples = ShortArray(pcmBytes.size / 2)
        java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().get(samples)
        // Предобработка.
        val processed = AudioPreprocessor.process(samples)
        // Записываем новый WAV с тем же заголовком.
        val out = java.io.File(context.cacheDir, "whisper_pre.wav")
        val bb = java.nio.ByteBuffer.allocate(processed.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (s in processed) bb.putShort(s)
        out.outputStream().use { os ->
            os.write(bytes, 0, 44)  // оригинальный заголовок
            os.write(bb.array())
        }
        return out.absolutePath
    }

    private fun releaseCurrent() {
        try { loadedModel?.let { Whisper.releaseModel(it) } } catch (_: Exception) {}
        loadedModel = null
        loadedModelId = null
    }
}

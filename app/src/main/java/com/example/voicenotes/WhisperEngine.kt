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
            val result = Whisper.transcribe(model, wavPath, WhisperConfig(language = "ru"))
            result.text?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun releaseCurrent() {
        try { loadedModel?.let { Whisper.releaseModel(it) } } catch (_: Exception) {}
        loadedModel = null
        loadedModelId = null
    }
}

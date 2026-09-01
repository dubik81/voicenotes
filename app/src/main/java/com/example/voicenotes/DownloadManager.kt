package com.example.voicenotes

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Фоновая загрузка моделей на уровне приложения (переживает закрытие экрана
 * настроек). Загрузка идёт, пока живо приложение, а не пока открыт экран.
 * Прогресс и ошибки видны из любого экрана через общие состояния.
 */
object DownloadManager {
    // ключ -> прогресс 0..100 (-1 = не идёт)
    val progress = mutableStateMapOf<String, Int>()
    val errors = mutableStateMapOf<String, String>()
    private val jobs = mutableMapOf<String, Job>()

    fun isDownloading(key: String) = (progress[key] ?: -1) in 0..100

    /** Запускает загрузку модели Whisper в фоне приложения. */
    fun downloadWhisper(scope: CoroutineScope, context: Context, modelId: String) {
        val key = "whisper:$modelId"
        if (jobs[key]?.isActive == true) return
        errors.remove(key)
        progress[key] = 0
        jobs[key] = scope.launch(Dispatchers.IO) {
            try {
                WhisperModelManager.download(context, modelId) { p -> progress[key] = p }
                progress[key] = -1
            } catch (e: Exception) {
                progress[key] = -1
                errors[key] = e.message ?: "Ошибка загрузки"
            }
        }
    }

    /** Запускает загрузку модели локального ИИ в фоне приложения. */
    fun downloadLocalAi(scope: CoroutineScope, context: Context, modelId: String) {
        val key = "localai:$modelId"
        if (jobs[key]?.isActive == true) return
        errors.remove(key)
        progress[key] = 0
        jobs[key] = scope.launch(Dispatchers.IO) {
            try {
                LocalAiModelManager.download(context, modelId) { p -> progress[key] = p }
                progress[key] = -1
            } catch (e: Exception) {
                progress[key] = -1
                errors[key] = e.message ?: "Ошибка загрузки"
            }
        }
    }
}

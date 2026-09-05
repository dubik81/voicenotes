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
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "voicenotes:whdownload")
            try {
                wl.acquire(60 * 60 * 1000L)
                WhisperModelManager.download(context, modelId) { p -> progress[key] = p }
                progress[key] = -1
            } catch (e: Exception) {
                progress[key] = -1
                errors[key] = e.message ?: "Ошибка загрузки"
            } finally {
                if (wl.isHeld) wl.release()
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
            // Wake lock: не даём процессору уснуть при потухшем экране —
            // иначе скачивание большой модели прерывается.
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "voicenotes:download")
            try {
                wl.acquire(60 * 60 * 1000L)  // максимум час
                LocalAiModelManager.download(context, modelId) { p -> progress[key] = p }
                progress[key] = -1
            } catch (e: Exception) {
                progress[key] = -1
                errors[key] = e.message ?: "Ошибка загрузки"
            } finally {
                if (wl.isHeld) wl.release()
            }
        }
    }
}

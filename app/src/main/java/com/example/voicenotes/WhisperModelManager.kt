package com.example.voicenotes

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Загрузка модели Whisper с Hugging Face (один раз, потом офлайн).
 * Размеры: tiny (~75МБ), base (~142МБ), small (~466МБ).
 */
object WhisperModelManager {

    data class ModelInfo(val id: String, val fileName: String, val url: String, val sizeMb: Int)

    val MODELS = mapOf(
        "tiny" to ModelInfo("tiny", "ggml-tiny.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin", 75),
        "base" to ModelInfo("base", "ggml-base.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin", 142),
        "small" to ModelInfo("small", "ggml-small.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin", 466)
    )

    fun modelFile(context: Context, modelId: String): File {
        val info = MODELS[modelId] ?: MODELS["base"]!!
        val dir = File(context.filesDir, "whisper").apply { mkdirs() }
        return File(dir, info.fileName)
    }

    fun isReady(context: Context, modelId: String): Boolean {
        val f = modelFile(context, modelId)
        return f.exists() && f.length() > 1_000_000
    }

    /** Скачивает модель с прогрессом (0..100). Кидает исключение при ошибке. */
    fun download(context: Context, modelId: String, onProgress: (Int) -> Unit) {
        val info = MODELS[modelId] ?: MODELS["base"]!!
        val target = modelFile(context, modelId)
        val tmp = File(target.absolutePath + ".part")

        val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw RuntimeException("Не удалось скачать модель (${conn.responseCode})")
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (info.sizeMb * 1_000_000L)
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    read += n
                    onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        conn.disconnect()
        if (tmp.length() < 1_000_000) { tmp.delete(); throw RuntimeException("Модель скачалась не полностью") }
        tmp.renameTo(target)
        onProgress(100)
    }
}

package com.example.voicenotes

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Загрузка моделей локального ИИ (ExecuTorch .pte формат) с Hugging Face.
 * Скачиваются один раз после установки, дальше работают офлайн.
 * Размер приложения не зависит от модели — она отдельный файл.
 */
object LocalAiModelManager {

    data class ModelInfo(val id: String, val fileName: String, val url: String, val sizeMb: Int, val label: String)

    // Компактные модели, дружелюбные к русскому, в формате ExecuTorch (.pte).
    // Ссылки указывают на community-сборки ExecuTorch на Hugging Face.
    val MODELS = mapOf(
        "small" to ModelInfo("small", "llama32-1b.pte",
            "https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-ET/resolve/main/llama3_2-1B.pte",
            1200, "Малая 1B (~1.2 ГБ, быстрая)"),
        "medium" to ModelInfo("medium", "llama32-3b.pte",
            "https://huggingface.co/executorch-community/Llama-3.2-3B-Instruct-ET/resolve/main/llama3_2-3B.pte",
            3200, "Средняя 3B (~3.2 ГБ, умнее)"),
        "gemma" to ModelInfo("gemma", "gemma2-2b.pte",
            "https://huggingface.co/executorch-community/gemma-2-2b-ET/resolve/main/gemma2-2B.pte",
            2500, "Gemma 2B (~2.5 ГБ, баланс)")
    )

    fun modelFile(context: Context, modelId: String): File {
        val info = MODELS[modelId] ?: MODELS["small"]!!
        val dir = File(context.filesDir, "localai").apply { mkdirs() }
        return File(dir, info.fileName)
    }

    fun isReady(context: Context, modelId: String): Boolean {
        val f = modelFile(context, modelId)
        return f.exists() && f.length() > 100_000_000  // модель весит сотни МБ+
    }

    fun download(context: Context, modelId: String, onProgress: (Int) -> Unit) {
        val info = MODELS[modelId] ?: MODELS["small"]!!
        val target = modelFile(context, modelId)
        val tmp = File(target.absolutePath + ".part")
        val conn = (URL(info.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode !in 200..299)
            throw RuntimeException("Не удалось скачать модель ИИ (${conn.responseCode})")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (info.sizeMb * 1_000_000L)
        conn.inputStream.use { input ->
            tmp.outputStream().use { output ->
                val buf = ByteArray(128 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    output.write(buf, 0, n); read += n
                    onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                }
            }
        }
        conn.disconnect()
        if (tmp.length() < 100_000_000) { tmp.delete(); throw RuntimeException("Модель скачалась не полностью") }
        tmp.renameTo(target)
        onProgress(100)
    }
}

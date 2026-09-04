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

    // Реальные модели ExecuTorch (.pte) из officiального executorch-community.
    // Квантованные INT4 — компактные, подходят для телефона.
    val MODELS = mapOf(
        "small" to ModelInfo("small", "llama-1b-int4.pte",
            "https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET/resolve/main/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8.pte",
            1100, "Llama 1B (для Кратко/Суть)"),
        "qwen" to ModelInfo("qwen", "qwen25-1_5b.pte",
            "https://huggingface.co/software-mansion/react-native-executorch-qwen-2.5/resolve/v0.8.0/qwen-2.5-1.5B/original/qwen2_5_1_5b_bf16.pte",
            3100, "Qwen 2.5 1.5B (лучше для русского/Чисто)"),
        "bf16" to ModelInfo("bf16", "llama-1b-bf16.pte",
            "https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-ET/resolve/main/Llama-3.2-1B-Instruct.pte",
            2500, "Llama 1B полная (~2.5 ГБ)")
    )

    // Токенизатор зависит от модели: Qwen — свой, Llama — свой.
    fun tokenizerUrlFor(modelId: String): String = when (modelId) {
        "qwen" -> "https://huggingface.co/software-mansion/react-native-executorch-qwen-2.5/resolve/v0.8.0/tokenizer.json"
        else -> "https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET/resolve/main/tokenizer.model"
    }

    // Токенизатор (нужен вместе с моделью для работы).
    const val TOKENIZER_URL =
        "https://huggingface.co/executorch-community/Llama-3.2-1B-Instruct-SpinQuant_INT4_EO8-ET/resolve/main/tokenizer.model"

    fun tokenizerFile(context: Context): File {
        val dir = File(context.filesDir, "localai").apply { mkdirs() }
        return File(dir, "tokenizer.model")
    }

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
        // Скачиваем токенизатор (нужен для работы модели), если ещё нет.
        val tok = tokenizerFile(context)
        if (!tok.exists() || tok.length() < 1000) {
            try {
                val tc = (URL(tokenizerUrlFor(modelId)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true
                }
                if (tc.responseCode in 200..299) {
                    tc.inputStream.use { inp -> tok.outputStream().use { it.write(inp.readBytes()) } }
                }
                tc.disconnect()
            } catch (_: Exception) { /* токенизатор опционален для скачивания, проверим при запуске */ }
        }
        onProgress(100)
    }
}

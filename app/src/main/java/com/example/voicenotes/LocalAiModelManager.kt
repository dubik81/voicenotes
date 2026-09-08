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

    // Токенизатор — СВОЙ у каждой модели (Qwen: tokenizer.json, Llama: tokenizer.model).
    // v115 и раньше был один общий файл tokenizer.model: какой скачался первым, тот и
    // использовался для ВСЕХ моделей. Модель с чужим токенизатором = мусор на выходе.
    private fun tokenizerName(modelId: String) =
        if (modelId.startsWith("qwen")) "tokenizer_qwen.json" else "tokenizer_llama.model"

    fun tokenizerFile(context: Context, modelId: String): File {
        val dir = File(context.filesDir, "localai").apply { mkdirs() }
        val f = File(dir, tokenizerName(modelId))
        if (!f.exists()) migrateLegacyTokenizer(dir, modelId, f)
        return f
    }

    /** Старый общий tokenizer.model: по содержимому определяем, чей он, и переносим. */
    private fun migrateLegacyTokenizer(dir: File, modelId: String, target: File) {
        try {
            val legacy = File(dir, "tokenizer.model")
            if (!legacy.exists() || legacy.length() < 1000) return
            val head = legacy.inputStream().use { val b = ByteArray(64); val n = it.read(b); String(b, 0, maxOf(n, 0)) }.trimStart()
            val isJson = head.startsWith("{")   // HF tokenizer.json (Qwen) — текстовый JSON
            val wantQwen = modelId.startsWith("qwen")
            if (isJson == wantQwen) {
                legacy.copyTo(target, overwrite = true)
                Diagnostics.info("Токенизатор: старый общий файл распознан как ${if (isJson) "Qwen(json)" else "Llama(model)"} → ${target.name}")
            }
        } catch (_: Throwable) {}
    }

    /** Докачать токенизатор для модели (если модель есть, а токенизатора нет). */
    fun ensureTokenizer(context: Context, modelId: String) {
        val tok = tokenizerFile(context, modelId)
        if (tok.exists() && tok.length() > 1000) return
        val tc = (URL(tokenizerUrlFor(modelId)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 60000; instanceFollowRedirects = true
        }
        if (tc.responseCode !in 200..299) throw RuntimeException("токенизатор: HTTP ${tc.responseCode}")
        tc.inputStream.use { inp -> tok.outputStream().use { it.write(inp.readBytes()) } }
        tc.disconnect()
        Diagnostics.info("Токенизатор скачан: ${tok.name} (${tok.length()} б)")
    }

    /**
     * v117: Конвертация HF tokenizer.json (Qwen) → формат tiktoken («base64 rank» построчно).
     * Причина: HF-токенизатор внутри ExecuTorch не читает кириллицу на входе (модель отвечала
     * «ваш текст напоминает русский с ошибками», «Скажи: привет» → «I'm not able to understand»),
     * а tiktoken-путь работает по байтам и с русским справляется. Служебные токены в tiktoken
     * получают ID = размер словаря + номер в списке Llama 3, поэтому в промпте используются
     * их «псевдонимы» (см. LocalAiEngine.buildPrompt).
     */
    fun tiktokenFile(context: Context, modelId: String): File =
        File(File(context.filesDir, "localai"), "tokenizer_${modelId}.tiktoken")

    fun convertJsonToTiktoken(json: File, out: File): String {
        val text = json.readText()
        val root = org.json.JSONObject(text)
        val vocab = root.getJSONObject("model").getJSONObject("vocab")
        // Минимальный ID служебных токенов — всё, что выше, в основной словарь не идёт.
        var minAdded = Int.MAX_VALUE
        root.optJSONArray("added_tokens")?.let { arr ->
            for (i in 0 until arr.length()) minAdded = minOf(minAdded, arr.getJSONObject(i).getInt("id"))
        }
        // GPT-2 bytes_to_unicode → обратная таблица (символ словаря → байт).
        val bs = ArrayList<Int>(); val cs = ArrayList<Int>()
        for (b in 33..126) { bs.add(b); cs.add(b) }
        for (b in 161..172) { bs.add(b); cs.add(b) }
        for (b in 174..255) { bs.add(b); cs.add(b) }
        var n = 0
        for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
        val charToByte = HashMap<Int, Int>()
        for (i in bs.indices) charToByte[cs[i]] = bs[i]

        val byId = arrayOfNulls<String>(minAdded)
        var count = 0; var bad = 0
        val keys = vocab.keys()
        while (keys.hasNext()) {
            val tok = keys.next(); val id = vocab.getInt(tok)
            if (id >= minAdded) continue
            val bytes = java.io.ByteArrayOutputStream()
            var ok = true
            var i = 0
            while (i < tok.length) {
                val cp = tok.codePointAt(i); i += Character.charCount(cp)
                val b = charToByte[cp]
                if (b == null) { ok = false; break }
                bytes.write(b)
            }
            if (!ok) { bad++; continue }
            byId[id] = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
            count++
        }
        val gaps = byId.count { it == null }
        if (gaps > 0) throw RuntimeException("словарь с пропусками: $gaps из $minAdded (не сконвертировано $bad)")
        val tmp = File(out.absolutePath + ".part")
        tmp.bufferedWriter().use { w -> for (id in 0 until minAdded) { w.write(byId[id]); w.write(" "); w.write(id.toString()); w.write("\n") } }
        tmp.renameTo(out)
        return "словарь $count токенов, служебные с $minAdded"
    }

    fun hasTokenizer(context: Context, modelId: String): Boolean =
        tokenizerFile(context, modelId).let { it.exists() && it.length() > 1000 }

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
        if (isReady(context, modelId)) {
            // Модель уже есть — докачиваем только токенизатор (если его нет).
            ensureTokenizer(context, modelId); onProgress(100); return
        }
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
        // Токенизатор своей модели (без него модель не работает).
        try { ensureTokenizer(context, modelId) } catch (e: Exception) {
            Diagnostics.error("Токенизатор не скачался: ${e.message?.take(60)} — докачаю при первом запуске ИИ")
        }
        onProgress(100)
    }
}

package com.example.voicenotes

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Облачное распознавание речи через OpenRouter (endpoint /audio/transcriptions).
 * Использует тот же ключ, что и текстовый ИИ. Whisper точнее Google и Vosk.
 */
object Transcriber {

    private const val ENDPOINT = "https://openrouter.ai/api/v1/audio/transcriptions"

    // Модели транскрипции по приоритету (актуальны на 2026).
    private val MODELS = listOf(
        "openai/whisper-large-v3",
        "openai/whisper-1"
    )

    /**
     * Распознаёт WAV-файл. Возвращает точный текст.
     * languageHint — код языка ("ru") для повышения точности.
     */
    suspend fun transcribe(wav: File, apiKey: String, languageHint: String = "ru"): String =
        withContext(Dispatchers.IO) {
            if (!wav.exists() || wav.length() < 44) throw RuntimeException("Аудиофайл пуст")
            // Whisper: до 25 МБ. WAV 16кГц моно ~ 2 МБ/мин, хватает надолго.
            val bytes = wav.readBytes()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            var lastError = "Не удалось распознать"
            for (model in MODELS) {
                try {
                    return@withContext callModel(model, b64, apiKey, languageHint)
                } catch (e: Exception) {
                    lastError = e.message ?: lastError
                }
            }
            throw RuntimeException(lastError)
        }

    private fun callModel(model: String, audioB64: String, apiKey: String, lang: String): String {
        val body = JSONObject().apply {
            put("model", model)
            put("input_audio", JSONObject().apply {
                put("data", audioB64)
                put("format", "wav")
            })
            if (lang.isNotBlank()) put("language", lang)
        }

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 90000    // распознавание может быть дольше
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("HTTP-Referer", "https://github.com/dubik81/voicenotes")
            setRequestProperty("X-Title", "Smysl-zametki")
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(response).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw RuntimeException(when (code) {
                    401 -> "Ключ отклонён при распознавании (401)"
                    402 -> "Закончились средства на распознавание (402)"
                    413 -> "Аудиофайл слишком большой (макс 25 МБ)"
                    429 -> "Слишком часто (429), подождите минуту"
                    else -> "Ошибка распознавания $code${if (msg != null) ": $msg" else ""}"
                })
            }
            val text = JSONObject(response).optString("text", "").trim()
            if (text.isBlank()) throw RuntimeException("Whisper вернул пустой текст")
            return text
        } catch (e: java.net.SocketTimeoutException) {
            throw RuntimeException("Распознавание не успело за 90 секунд")
        } finally {
            conn.disconnect()
        }
    }
}

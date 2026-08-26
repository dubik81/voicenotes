package com.example.voicenotes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenRouter. Для скорости и надёжности сами перебираем несколько быстрых
 * бесплатных моделей: если одна медленная/занята — быстро пробуем следующую.
 * Короткий таймаут не даёт «думать минутами».
 */
object AiClient {

    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    // Быстрые бесплатные модели. Пробуем по очереди.
    private val MODELS = listOf(
        "google/gemini-2.0-flash-exp:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "google/gemma-2-9b-it:free",
        "qwen/qwen-2.5-7b-instruct:free"
    )

    private const val PER_MODEL_TIMEOUT_MS = 22000

    /** Пробуем модели по очереди, возвращаем первый успешный ответ. */
    suspend fun process(rawText: String, level: Level, tone: Tone, apiKey: String): String =
        withContext(Dispatchers.IO) {
            var lastError: String = "Не удалось связаться с ИИ"
            for (model in MODELS) {
                try {
                    return@withContext callModel(model, rawText, level, tone, apiKey)
                } catch (e: RetryableException) {
                    lastError = e.message ?: lastError
                    // пробуем следующую модель
                } catch (e: FatalException) {
                    throw RuntimeException(e.message)  // ключ/лимит — нет смысла перебирать
                }
            }
            throw RuntimeException(lastError)
        }

    private class RetryableException(msg: String) : Exception(msg)
    private class FatalException(msg: String) : Exception(msg)

    private fun callModel(
        model: String, rawText: String, level: Level, tone: Tone, apiKey: String
    ): String {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.4)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt(level, tone)))
                put(JSONObject().put("role", "user").put("content", rawText))
            })
        }
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = PER_MODEL_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("HTTP-Referer", "https://github.com/dubik81/voicenotes")
            setRequestProperty("X-Title", "Smysl-zametki")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() } ?: ""
            when {
                code in 200..299 -> return parseContent(response)
                code == 401 -> throw FatalException("Ключ отклонён. Проверьте ключ OpenRouter.")
                code == 402 -> throw FatalException("Закончились бесплатные запросы на сегодня.")
                code == 429 -> throw RetryableException("Модель занята, пробую другую…")
                else -> throw RetryableException("Ошибка $code, пробую другую модель…")
            }
        } catch (e: FatalException) {
            throw e
        } catch (e: Exception) {
            throw RetryableException(e.message ?: "Таймаут, пробую другую модель…")
        } finally {
            conn.disconnect()
        }
    }

    private fun systemPrompt(level: Level, tone: Tone): String {
        val task = when (level) {
            Level.VERBATIM ->
                "Приведи текст в порядок: расставь знаки препинания и заглавные буквы, " +
                "убери повторы и оговорки. Смысл, все факты и детали сохрани полностью."
            Level.CLEAN ->
                "Убери слова-паразиты и звуки («эээ», «ну», «типа», «как бы», «короче»), " +
                "повторы и оговорки. Сохрани ВСЕ факты, детали, числа и имена. Расставь " +
                "правильную пунктуацию и заглавные буквы. Результат — гладкий, читаемый текст."
            Level.BRIEF ->
                "Перескажи текст своими словами примерно вдвое короче. Объединяй мелкие " +
                "детали в обобщения, оставь ключевые мысли. Обязательно связные, грамотные " +
                "предложения с правильной пунктуацией."
            Level.GIST ->
                "Сформулируй самую суть в 1–3 красивых, законченных предложениях. Это должен " +
                "быть аккуратный, грамотный текст с правильной пунктуацией — как хорошее " +
                "резюме. Можно свободно переформулировать своими словами. Никаких обрывков."
        }
        val toneRule = when (tone) {
            Tone.FORMAL -> "Стиль официальный, деловой. Замени разговорные слова на нейтральные."
            Tone.NEUTRAL -> "Стиль нейтральный, спокойный."
            Tone.CASUAL -> "Стиль живой, разговорный, но грамотный. Уместно добавь 1–3 эмодзи по смыслу."
        }
        return """
Ты — редактор голосовых заметок. Задача: $task
$toneRule

ЖЁСТКИЕ ТРЕБОВАНИЯ (нарушать нельзя):
1. Ответ — всегда грамотный СВЯЗНЫЙ текст с правильной пунктуацией и заглавными буквами.
2. Никаких рваных фраз, обрывков и слов-паразитов в ответе.
3. Каждое предложение законченное и осмысленное.
4. Отвечай на языке оригинала.
5. Не выдумывай фактов, которых нет в оригинале.
6. Верни ТОЛЬКО готовый текст — без пояснений, заголовков и кавычек.

Плохо (так НЕЛЬЗЯ): «нам это как бы надо на улицу ну погулять короче»
Хорошо: «Нам нужно выйти на улицу и погулять.»
""".trim()
    }

    private fun parseContent(response: String): String {
        val choices = JSONObject(response).optJSONArray("choices")
            ?: throw RetryableException("Пустой ответ")
        if (choices.length() == 0) throw RetryableException("Пустой ответ")
        val text = choices.getJSONObject(0).optJSONObject("message")
            ?.optString("content", "")?.trim().orEmpty()
        if (text.isBlank()) throw RetryableException("Пустой текст")
        return text
    }
}

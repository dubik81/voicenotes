package com.example.voicenotes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Подключение к OpenRouter. Ускорено: вместо медленного авто-роутера задаём
 * список конкретных быстрых бесплатных моделей — OpenRouter пробует их по
 * порядку (фолбэк), если первая занята.
 * Ключ хранится только на телефоне (в Settings).
 */
object AiClient {

    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    // Быстрые бесплатные модели. Порядок = приоритет. Список можно расширять.
    private val MODELS = listOf(
        "google/gemini-2.0-flash-exp:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "qwen/qwen-2.5-72b-instruct:free"
    )

    suspend fun process(rawText: String, level: Level, tone: Tone, apiKey: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("models", JSONArray(MODELS))       // фолбэк-список
                put("model", MODELS.first())           // основная
                put("temperature", 0.4)
                put("messages", JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt(level, tone)))
                    put(JSONObject().put("role", "user").put("content", rawText))
                })
            }

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
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
                if (code !in 200..299) {
                    throw RuntimeException(when (code) {
                        401 -> "Ключ отклонён. Проверьте ключ OpenRouter."
                        402 -> "Закончились бесплатные запросы на сегодня."
                        429 -> "Слишком часто. Подождите минуту."
                        else -> "Ошибка сервера ($code): ${parseError(response)}"
                    })
                }
                parseContent(response)
            } finally {
                conn.disconnect()
            }
        }

    private fun systemPrompt(level: Level, tone: Tone): String {
        val task = when (level) {
            Level.VERBATIM ->
                "Приведи текст в порядок: расставь знаки препинания и заглавные буквы, " +
                "убери повторы и оговорки. Смысл, все факты и детали сохрани полностью. " +
                "Длину почти не меняй."
            Level.CLEAN ->
                "Убери слова-паразиты, звуки («эээ», «ну», «типа»), повторы и оговорки. " +
                "Сохрани ВСЕ факты, детали, числа и имена. Расставь правильную пунктуацию."
            Level.TIGHT ->
                "Убери воду, лишние эмоции, вводные обороты и красивости. Сохрани ВСЕ " +
                "факты и детали, но подай их сухо и по делу. Не обобщай, не выбрасывай " +
                "содержание. Если сказано «магазин, аптека, почта» — оставь все три."
            Level.BRIEF ->
                "Перескажи текст своими словами примерно вдвое короче. Объединяй мелкие " +
                "детали в обобщения, оставь ключевые мысли. Пиши связными предложениями."
            Level.GIST ->
                "Сформулируй самую суть в одном-двух законченных предложениях. " +
                "Максимальное обобщение, только главная мысль."
        }
        val toneRule = when (tone) {
            Tone.FORMAL -> "Тон официальный, деловой. Без разговорных слов."
            Tone.NEUTRAL -> "Тон нейтральный, спокойный."
            Tone.CASUAL -> "Тон живой, разговорный, но грамотный."
            Tone.EMOJI -> "Тон живой, разговорный. Уместно добавь эмодзи по смыслу (не перебарщивай)."
        }
        return """
Ты — редактор голосовых заметок. Твоя задача: $task
$toneRule

ЖЁСТКИЕ ТРЕБОВАНИЯ К ЛЮБОМУ ОТВЕТУ:
1. Результат — всегда грамотный, СВЯЗНЫЙ текст с правильной пунктуацией и заглавными буквами. Никаких обрывков и рваных фраз.
2. Каждое предложение — законченное и осмысленное.
3. Отвечай на языке оригинала.
4. Не придумывай факты, которых нет в оригинале.
5. Верни ТОЛЬКО готовый текст — без пояснений, заголовков и кавычек.

Пример ПЛОХО (так нельзя): «нам нужно это пойти улицу вместе не знаю гулять это точно»
Пример ХОРОШО: «Нам нужно выйти на улицу и погулять вместе.»
""".trim()
    }

    private fun parseContent(response: String): String {
        val choices = JSONObject(response).optJSONArray("choices")
            ?: throw RuntimeException("Пустой ответ от ИИ")
        if (choices.length() == 0) throw RuntimeException("ИИ не вернул результат")
        val text = choices.getJSONObject(0).optJSONObject("message")
            ?.optString("content", "")?.trim().orEmpty()
        if (text.isBlank()) throw RuntimeException("ИИ вернул пустой текст")
        return text
    }

    private fun parseError(response: String): String = try {
        JSONObject(response).optJSONObject("error")?.optString("message") ?: "неизвестно"
    } catch (_: Exception) { "неизвестно" }
}

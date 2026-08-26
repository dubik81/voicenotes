package com.example.voicenotes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Подключение к OpenRouter — единый шлюз к бесплатным ИИ-моделям.
 * Ключ бесплатный, заводится на openrouter.ai/keys без карты.
 * Модель "openrouter/free" сама выбирает доступную бесплатную модель.
 * Ключ хранится только на телефоне пользователя.
 */
object AiClient {

    private const val MODEL = "openrouter/free"
    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    const val THRESHOLD = 0.6f

    suspend fun process(rawText: String, concentration: Float, apiKey: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("temperature", 0.3)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt(concentration))
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", rawText)
                    })
                })
            }

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                // Необязательные заголовки для статистики OpenRouter.
                setRequestProperty("HTTP-Referer", "https://github.com/dubik81/voicenotes")
                setRequestProperty("X-Title", "Smysl-zametki")
            }

            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (code !in 200..299) {
                    val msg = parseError(response)
                    throw RuntimeException(
                        when (code) {
                            401 -> "Ключ отклонён. Проверьте ключ OpenRouter."
                            402 -> "Закончились бесплатные запросы на сегодня."
                            429 -> "Слишком часто. Подождите минуту и повторите."
                            else -> "Ошибка сервера ($code): $msg"
                        }
                    )
                }
                parseContent(response)
            } finally {
                conn.disconnect()
            }
        }

    private fun systemPrompt(c: Float): String {
        val isGreen = c < THRESHOLD
        val instruction = if (isGreen) {
            val g = (c / THRESHOLD).coerceIn(0f, 1f)
            (when {
                g <= 0.33f ->
                    "Убери из текста только словесный мусор: паразитов, звуки («эээ», " +
                    "«ну», «типа»), повторы и оговорки. Сохрани ВСЕ факты, детали, числа, " +
                    "имена и тон. Длину почти не меняй."
                g <= 0.66f ->
                    "Убери словесный мусор И лишние эмоции: ругательства, восклицания, " +
                    "субъективные оценки. Сохрани ВСЕ факты и детали. Тон сделай нейтральным."
                else ->
                    "Убери всю словесную «упаковку»: эмоции, красивые обороты, вводные " +
                    "слова. Оставь сухую фактическую суть, но СОХРАНИ каждый факт, действие, " +
                    "число и имя."
            }) + " СТРОГО: не объединяй разные факты в общий смысл и ничего не выбрасывай из " +
                "содержания. Если сказано «магазин, аптека, почта» — все три должны остаться."
        } else {
            val r = ((c - THRESHOLD) / (1f - THRESHOLD)).coerceIn(0f, 1f)
            (when {
                r <= 0.33f ->
                    "Перескажи текст своими словами примерно вдвое короче. Можно объединять " +
                    "похожие детали в обобщения (например «магазин, аптека, почта» → «дела " +
                    "по городу»), но ключевые мысли сохрани."
                r <= 0.66f ->
                    "Сделай краткую выжимку только из главных мыслей, своими словами. " +
                    "Второстепенные детали отбрось. Сократи в 3–4 раза."
                else ->
                    "Сформулируй самую суть в одном-двух предложениях. Максимальное обобщение."
            }) + " В этой зоне допустима потеря деталей ради краткости."
        }
        return "Ты — редактор голосовых заметок. $instruction\n\n" +
               "Правила: отвечай на языке оригинала; верни ТОЛЬКО обработанный текст без " +
               "пояснений и кавычек; не придумывай фактов, которых нет в оригинале."
    }

    private fun parseContent(response: String): String {
        val choices = JSONObject(response).optJSONArray("choices")
            ?: throw RuntimeException("Пустой ответ от ИИ")
        if (choices.length() == 0) throw RuntimeException("ИИ не вернул результат")
        val text = choices.getJSONObject(0)
            .optJSONObject("message")?.optString("content", "")?.trim().orEmpty()
        if (text.isBlank()) throw RuntimeException("ИИ вернул пустой текст")
        return text
    }

    private fun parseError(response: String): String = try {
        JSONObject(response).optJSONObject("error")?.optString("message") ?: "неизвестно"
    } catch (_: Exception) { "неизвестно" }
}


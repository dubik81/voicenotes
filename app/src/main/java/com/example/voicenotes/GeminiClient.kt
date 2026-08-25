package com.example.voicenotes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обращение к бесплатному Gemini API.
 * Ключ бесплатный, заводится в Google AI Studio без карты.
 * Ключ хранится только на телефоне пользователя.
 */
object GeminiClient {

    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    /**
     * Возвращает обработанный текст или бросает исключение с понятным сообщением.
     * concentration: 0f = дословная чистка, 1f = максимально краткий пересказ смыслом.
     */
    suspend fun process(rawText: String, concentration: Float, apiKey: String): String =
        withContext(Dispatchers.IO) {
            val prompt = buildPrompt(rawText, concentration)

            val body = JSONObject().apply {
                put("contents", org.json.JSONArray().put(
                    JSONObject().put("parts", org.json.JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.3)
                    put("maxOutputTokens", 2048)
                })
            }

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }

            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }

                if (code !in 200..299) {
                    val msg = tryParseError(response)
                    throw RuntimeException(
                        when (code) {
                            400 -> "Неверный запрос или ключ. Проверьте ключ. ($msg)"
                            403 -> "Ключ отклонён. Проверьте, что ключ активен. ($msg)"
                            429 -> "Превышен бесплатный лимит запросов. Подождите минуту."
                            else -> "Ошибка сервера ($code): $msg"
                        }
                    )
                }
                parseText(response)
            } finally {
                conn.disconnect()
            }
        }

    // Порог между "зелёной" (без потери фактов) и "красной" (с обобщением) зонами.
    const val THRESHOLD = 0.6f

    private fun buildPrompt(text: String, c: Float): String {
        val isGreen = c < THRESHOLD
        // Внутри зоны считаем локальную силу 0..1 (насколько глубоко зашли в зону).
        val level = if (isGreen) {
            // Зелёная зона: убираем ТОЛЬКО форму. Все факты обязаны остаться.
            val g = (c / THRESHOLD).coerceIn(0f, 1f)
            when {
                g <= 0.33f ->
                    "Убери только словесный мусор: паразитов, звуки («эээ», «ну», «типа»), " +
                    "повторы и оговорки. Сохрани ВСЕ факты, детали, числа, имена И общий тон. " +
                    "Длину почти не меняй."
                g <= 0.66f ->
                    "Убери словесный мусор И лишние эмоции: ругательства, восклицания, " +
                    "субъективные оценки («ужасно», «шикарно»). Сохрани ВСЕ факты и детали. " +
                    "Оставь нейтральный тон."
                else ->
                    "Убери всю «упаковку»: эмоции, красивые обороты, вводные слова, " +
                    "лирику. Оставь сухую фактическую суть, но при этом СОХРАНИ КАЖДЫЙ " +
                    "факт, каждое действие, число и имя из оригинала. Ничего не обобщай."
            } + "\n\nСТРОГО: не объединяй разные факты в общий смысл, ничего не выбрасывай " +
                "из содержания. Если в оригинале «магазин, аптека, почта» — все три должны остаться."
        } else {
            // Красная зона: разрешено терять детали ради обобщения.
            val r = ((c - THRESHOLD) / (1f - THRESHOLD)).coerceIn(0f, 1f)
            when {
                r <= 0.33f ->
                    "Перескажи своими словами короче. Разрешено объединять похожие детали " +
                    "в обобщения (например «магазин, аптека, почта» → «дела по городу»), " +
                    "но ключевые мысли сохрани. Сокращение примерно вдвое."
                r <= 0.66f ->
                    "Сделай краткую выжимку только из главных мыслей, своими словами. " +
                    "Второстепенные детали и примеры можно отбросить. Сокращение в 3–4 раза."
                else ->
                    "Сформулируй самую суть в одном-двух предложениях. Максимальное " +
                    "обобщение, только главная мысль, детали не важны."
            } + "\n\nВ этой зоне допустима потеря деталей ради краткости и общего смысла."
        }

        return """
Ты — редактор голосовых заметок. $level

Общие правила:
- Отвечай на том же языке, что и исходный текст.
- Верни ТОЛЬКО обработанный текст, без пояснений, без кавычек, без вступлений.
- Не придумывай факты, которых нет в оригинале.

Исходный текст:
$text
""".trim()
    }

    private fun parseText(response: String): String {
        val json = JSONObject(response)
        val candidates = json.optJSONArray("candidates")
            ?: throw RuntimeException("Пустой ответ от ИИ")
        if (candidates.length() == 0) throw RuntimeException("ИИ не вернул результат")
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")?.optJSONArray("parts")
            ?: throw RuntimeException("Неожиданный формат ответа")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.getJSONObject(i).optString("text", ""))
        }
        return sb.toString().trim()
    }

    private fun tryParseError(response: String): String = try {
        JSONObject(response).optJSONObject("error")?.optString("message") ?: "неизвестно"
    } catch (_: Exception) { "неизвестно" }
}

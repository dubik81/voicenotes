package com.example.voicenotes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Подключение к OpenRouter — надёжный вариант.
 * Передаём МАССИВ бесплатных моделей: OpenRouter сам перебирает их при
 * сбое/занятости/рейт-лимите (fallback на стороне сервера). Неудачные
 * запросы не тарифицируются. Подробная диагностика ошибок.
 */
object AiClient {

    private const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    // openrouter/free — специальный роутер: OpenRouter САМ выбирает живую
    // бесплатную модель. Не устаревает, когда конкретные модели уходят из free.
    private val FREE_MODELS = listOf(
        "openrouter/free"
    )

    // Запасные конкретные модели (на случай, если роутер недоступен).
    private val BACKUP_MODELS = listOf(
        "nvidia/nemotron-3-nano-30b:free",
        "google/gemma-4-31b-it:free",
        "openai/gpt-oss-120b:free"
    )

    /** Основной вызов обработки текста. vary=true просит переформулировать иначе. */
    suspend fun process(rawText: String, level: Level, tone: Tone, apiKey: String, vary: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val sys = systemPrompt(level, tone) + if (vary)
                "\n\nВАЖНО: дай ДРУГОЙ вариант формулировки, отличный от обычного — " +
                "переставь акценты, смени структуру фразы, но сохрани смысл и требования выше."
            else ""
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", rawText))
            }
            val (text, _) = request(messages, apiKey, temperature = if (vary) 0.9 else 0.4)
            text
        }

    /**
     * УМНЫЙ ЗАПРОС: за один вызов получаем все варианты (ступень × тон) + базовый.
     * Возвращает Map с ключами "levelOrdinal:toneOrdinal" -> текст.
     * Экономит лимит: 1 запрос вместо 9.
     */
    suspend fun processAll(rawText: String, apiKey: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", allVariantsPrompt()))
                put(JSONObject().put("role", "user").put("content", rawText))
            }
            val (text, _) = request(messages, apiKey, temperature = 0.4)
            parseAllVariants(text, rawText)
        }

    private fun allVariantsPrompt(): String {
        val sb = StringBuilder()
        sb.append("Ты — редактор голосовых заметок. Текст пришёл из распознавания речи, ")
        sb.append("поэтому в нём могут быть ошибки распознавания, оговорки, повторы, слова-паразиты. ")
        sb.append("Сначала мысленно восстанови смысл, затем верни СТРОГО JSON-объект ")
        sb.append("(без markdown, без пояснений) со всеми вариантами обработки.\n\n")

        sb.append("Ступени сжатия (каждая КОРОЧЕ предыдущей):\n")
        sb.append("- CLEAN: убери паразиты («ну», «типа», «как бы», «эээ»), повторы, оговорки. ")
        sb.append("Сохрани ВСЕ факты, числа, имена. Расставь пунктуацию. Длина ~70-90% оригинала.\n")
        sb.append("- BRIEF: перескажи главное своими словами. Объедини детали. Длина ~40-50% оригинала.\n")
        sb.append("- GIST: только самая суть, 1-3 красивых предложения. Длина ~15-25% оригинала.\n\n")

        sb.append("Тона:\n")
        sb.append("- FORMAL: официально-деловой, без разговорных слов.\n")
        sb.append("- NEUTRAL: нейтральный, спокойный.\n")
        sb.append("- CASUAL: живой, разговорный, 1-3 эмодзи по смыслу.\n\n")

        sb.append("ПРИМЕР. Вход: «ну эээ короче я сегодня ходил в магазин потом в аптеку ну и на почту зашёл»\n")
        sb.append("CLEAN_NEUTRAL: «Сегодня я ходил в магазин, потом в аптеку и зашёл на почту.»\n")
        sb.append("BRIEF_NEUTRAL: «Сегодня обошёл магазин, аптеку и почту.»\n")
        sb.append("GIST_NEUTRAL: «Поход по делам: магазин, аптека, почта.»\n\n")

        sb.append("Формат ответа (заполни ВСЕ поля готовым текстом):\n{\n")
        sb.append("  \"VERBATIM\": \"<текст с пунктуацией и заглавными, смысл без изменений>\",\n")
        for (l in listOf("CLEAN", "BRIEF", "GIST")) {
            for (t in listOf("FORMAL", "NEUTRAL", "CASUAL")) {
                sb.append("  \"${l}_${t}\": \"<...>\"")
                sb.append(if (l == "GIST" && t == "CASUAL") "\n" else ",\n")
            }
        }
        sb.append("}\n\n")
        sb.append("ЖЁСТКО: связный грамотный текст с пунктуацией; сжатые варианты СТРОГО короче оригинала ")
        sb.append("по указанным пропорциям; не выдумывай факты; отвечай на языке оригинала; ")
        sb.append("верни ТОЛЬКО валидный JSON.")
        return sb.toString()
    }

    /** Парсит JSON-ответ в Map ключей "levelOrd:toneOrd". Устойчив к мелким огрехам. */
    private fun parseAllVariants(response: String, fallbackOrig: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // вырезаем JSON, даже если модель обернула в markdown ```json ... ```
        val jsonStr = response.substringAfter('{', "").substringBeforeLast('}', "")
            .let { if (it.isBlank()) response else "{$it}" }
        val json = try { JSONObject(jsonStr) } catch (_: Exception) {
            // формат сломан — вернём хотя бы базовый как оригинал
            return mapOf("${Level.VERBATIM.ordinal}:0" to fallbackOrig)
        }

        val levelMap = mapOf("CLEAN" to Level.CLEAN, "BRIEF" to Level.BRIEF, "GIST" to Level.GIST)
        val toneMap = mapOf("FORMAL" to Tone.FORMAL, "NEUTRAL" to Tone.NEUTRAL, "CASUAL" to Tone.CASUAL)

        // базовый
        json.optString("VERBATIM").takeIf { it.isNotBlank() }?.let {
            for (t in Tone.entries) result["${Level.VERBATIM.ordinal}:${t.ordinal}"] = it
        }
        // все комбинации
        for ((lName, l) in levelMap) {
            for ((tName, t) in toneMap) {
                val v = json.optString("${lName}_${tName}")
                if (v.isNotBlank()) result["${l.ordinal}:${t.ordinal}"] = v
            }
        }
        if (result.isEmpty()) throw RuntimeException("ИИ не вернул варианты в нужном формате")
        return result
    }

    /**
     * Проверка ключа: шлёт крошечный запрос и возвращает человекочитаемый результат.
     * Возвращает пару (успех, сообщение).
     */
    suspend fun testKey(apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext false to "Ключ не введён"
        if (!apiKey.startsWith("sk-or-")) {
            return@withContext false to "Ключ должен начинаться с sk-or-…"
        }
        try {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", "ok"))
            }
            request(messages, apiKey, temperature = 0.0, maxTokens = 5)
            true to "Ключ работает! ИИ отвечает."
        } catch (e: Exception) {
            false to (e.message ?: "Неизвестная ошибка")
        }
    }

    /**
     * Запрос с автоперебором: сначала основные модели (массивом), при провале —
     * запасные по одной. Возвращает (текст, использованная_модель).
     */
    private fun request(
        messages: JSONArray,
        apiKey: String,
        temperature: Double,
        maxTokens: Int? = null
    ): Pair<String, String> {
        var lastError = "Не удалось получить ответ ИИ"
        // 1) основные — одним запросом (серверный фолбэк внутри массива)
        try {
            return requestOnce(FREE_MODELS, messages, apiKey, temperature, maxTokens)
        } catch (e: Exception) {
            lastError = e.message ?: lastError
        }
        // 2) запасные — по одной
        for (m in BACKUP_MODELS) {
            try {
                return requestOnce(listOf(m), messages, apiKey, temperature, maxTokens)
            } catch (e: Exception) {
                lastError = e.message ?: lastError
            }
        }
        throw RuntimeException(lastError)
    }

    private fun requestOnce(
        models: List<String>,
        messages: JSONArray,
        apiKey: String,
        temperature: Double,
        maxTokens: Int?
    ): Pair<String, String> {
        val body = JSONObject().apply {
            if (models.size > 1) put("models", JSONArray(models))
            put("model", models.first())
            put("messages", messages)
            put("temperature", temperature)
            if (maxTokens != null) put("max_tokens", maxTokens)
        }

        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
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
                throw RuntimeException(explainError(code, response))
            }
            val json = JSONObject(response)
            val choices = json.optJSONArray("choices")
                ?: throw RuntimeException("ИИ вернул пустой ответ (нет choices)")
            if (choices.length() == 0) throw RuntimeException("ИИ не дал результат")
            val text = choices.getJSONObject(0).optJSONObject("message")
                ?.optString("content", "")?.trim().orEmpty()
            if (text.isBlank()) throw RuntimeException("ИИ вернул пустой текст")
            val usedModel = json.optString("model", "неизвестно")
            return text to usedModel
        } catch (e: java.net.UnknownHostException) {
            throw RuntimeException("Нет интернета или openrouter.ai недоступен")
        } catch (e: java.net.SocketTimeoutException) {
            throw RuntimeException("Превышено время ожидания. Попробуйте ещё раз")
        } catch (e: javax.net.ssl.SSLException) {
            throw RuntimeException("Ошибка защищённого соединения (возможно, мешает VPN)")
        } catch (e: java.io.IOException) {
            throw RuntimeException("Сеть недоступна: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /** Разбор ошибки в понятный текст, с подсказкой что делать. */
    private fun explainError(code: Int, response: String): String {
        val serverMsg = try {
            JSONObject(response).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) { "" }
        return when (code) {
            401 -> "Ключ отклонён (401). Включите в OpenRouter → Settings → Privacy → " +
                   "«Allow free endpoints that train on request data» и сохраните. " +
                   if (serverMsg.isNotBlank()) "[$serverMsg]" else ""
            402 -> "Закончились бесплатные запросы (402). Подождите сутки или пополните баланс."
            403 -> "Доступ к модели закрыт (403). Обычно это временный лимит провайдера — " +
                   "подождите минуту. ${if (serverMsg.isNotBlank()) "[$serverMsg]" else ""}"
            404 -> "Модель стала платной (404). Пробую другую бесплатную…"
            429 -> "Слишком часто (429). Лимит 20 запросов в минуту, подождите минуту."
            in 500..599 -> "Сервер OpenRouter перегружен ($code). Попробуйте позже."
            else -> "Ошибка $code. ${if (serverMsg.isNotBlank()) serverMsg else "Попробуйте позже."}"
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
4. Результат ДОЛЖЕН быть КОРОЧЕ исходного текста (для «Кратко» — примерно вдвое, для «Суть» — в несколько раз). Никогда не длиннее оригинала.
5. Отвечай на языке оригинала.
6. Не выдумывай фактов, которых нет в оригинале.
7. Верни ТОЛЬКО готовый текст — без пояснений, заголовков и кавычек.

Плохо (так НЕЛЬЗЯ): «нам это как бы надо на улицу ну погулять короче»
Хорошо: «Нам нужно выйти на улицу и погулять.»
""".trim()
    }
}

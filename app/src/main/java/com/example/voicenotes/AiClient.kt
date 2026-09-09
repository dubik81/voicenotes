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

    /**
     * ЕДИНОЕ правило «Чисто» — и для пакетного запроса (все варианты сразу), и для
     * одиночного («Обновить»). v115: пакетный промпт требовал «тот же текст той же длины,
     * число предложений сохрани» — облако понимало буквально и отдавало сырой текст с
     * подправленными окончаниями (пользователь: «куски текста не осмысленны»), тогда как
     * одиночный промпт давал хороший результат. Теперь оба одинаковые.
     */
    // v118. Задача «Чисто» — это ВОССТАНОВЛЕНИЕ РЕЧИ, а не улучшение текста. Формулировка
    // описывает модели, ОТКУДА взялся вход: программа разбирала звук слово за словом,
    // поэтому ошибки у неё характерные (созвучные подмены, слипшиеся слова, формальные
    // точки). Понимая происхождение ошибок, модель восстанавливает сказанное, а не
    // «редактирует» текст по своему вкусу.
    private val CLEAN_RULE =
        "ВХОД — результат автоматического распознавания речи. Это НЕ текст, написанный человеком: " +
        "программа разбирала запись слово за словом, поэтому в ней характерные ошибки — созвучные " +
        "подмены («робуста»→«работа», «Машу и Петю»→«какой машине и плетью»), смещённые границы слов, " +
        "искажённые окончания и предлоги. Точки и запятые расставлены программой формально и НЕ " +
        "показывают, где человек заканчивал мысль.\n" +
        "ЗАДАЧА: восстановить, что человек произнёс на самом деле.\n" +
        "КАК: читай текст как звучащую речь; если слово бессмысленно в контексте — подбери созвучное, " +
        "от которого фраза становится осмысленной. Заменяй слово ТОЛЬКО когда оно бессмысленно на своём " +
        "месте И замена звучит похоже; слово, осмысленное в контексте, не трогай никогда. Знаки препинания " +
        "ставь по смыслу речи, исходную разбивку на предложения игнорируй полностью. Убирай только " +
        "слова-паразиты («э-э», «ну», «значит», «типа», «как бы») и повторы-оговорки.\n" +
        "ЗАПРЕЩЕНО: сокращать, пересказывать, обобщать, дополнять от себя, улучшать стиль, менять порядок " +
        "мыслей, писать пояснения о тексте. Сохрани ВСЕ мысли, факты, числа, имена и все фразы, включая " +
        "служебные («конец стихотворения», «следующий пункт»). Названия, цитаты и устойчивые выражения " +
        "оставляй ДОСЛОВНО (нельзя «Что нам стоит дом построить» превращать в «Почему мы должны построить дом»). " +
        "Фрагмент, который восстановить невозможно, оставь как есть — не выдумывай. Объём примерно равен оригиналу."

    private val CLEAN_EXAMPLE =
        "ПРИМЕР 1. Вход: «сегодня проверим программу проверим также ошибки. это нужно чтобы не было сложностей»\n" +
        "Результат: «Сегодня проверим программу, а также ошибки. Это нужно, чтобы не было сложностей.» " +
        "(убран повтор, знаки по смыслу, обе мысли на месте)\n" +
        "ПРИМЕР 2. Вход: «я позову с какой машине и плетью может быть»\n" +
        "Результат: «Я позову с тобой Машу и Петю, может быть.» " +
        "(бессмысленные «какой машине и плетью» заменены СОЗВУЧНЫМИ именами)\n" +
        "ПРИМЕР 3. Вход: «может в любой момент пойти дождь ещё»\n" +
        "Результат: «Может в любой момент пойти дождь.» " +
        "(«дождь» осмысленно на своём месте — его НЕЛЬЗЯ ни на что заменять)"

    // Остаток запросов из последнего ответа (-1 = неизвестно).
    @Volatile var rateLimitRemaining: Int = -1
        private set

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
            // «Обновить» (vary): для Чисто — только ДРУГОЕ ОФОРМЛЕНИЕ (пунктуация, разбивка
            // на предложения), слова и смысл те же; для Кратко/Суть — другая формулировка.
            // Раньше Чисто тоже просили «переставить акценты, сменить структуру» при
            // температуре 0.9 — так на 4-м обновлении менялось название лекции.
            val sys = systemPrompt(level, tone) + when {
                !vary -> ""
                level == Level.CLEAN || level == Level.VERBATIM ->
                    "\n\nВАЖНО: это повторная попытка. Оформи текст ИНАЧЕ (другая разбивка на предложения, " +
                    "другие знаки), но слова, факты, названия и смысл — ровно те же, что в оригинале."
                else ->
                    "\n\nВАЖНО: дай ДРУГОЙ вариант формулировки, отличный от обычного — " +
                    "переставь акценты, смени структуру фразы, но сохрани смысл, факты и требования выше."
            }
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", rawText))
            }
            val temp = when { !vary -> 0.4; level == Level.CLEAN -> 0.6; else -> 0.85 }
            val (text, model) = request(messages, apiKey, temperature = temp)
            Diagnostics.engine("Облако ($level/$tone${if (vary) ", обновить" else ""}): модель $model, ответ ${text.length} симв")
            text
        }

    /**
     * СТЕНОГРАММА ЛЕКЦИИ: оформляет речь как конспект, НЕ искажая смысл и слова.
     * Возвращает Map "levelOrd:toneOrd" (тон всегда NEUTRAL для лекции).
     */
    suspend fun processLecture(rawText: String, apiKey: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", lecturePrompt()))
                put(JSONObject().put("role", "user").put("content", rawText))
            }
            val (text, _) = request(messages, apiKey, temperature = 0.3)
            parseLectureVariants(text, rawText)
        }

    private fun lecturePrompt(): String {
        val sb = StringBuilder()
        sb.append("Ты — стенографист лекции. На входе — распознанная речь лектора. ")
        sb.append("Важно понимать: текст получен автоматическим распознаванием голоса, поэтому ")
        sb.append("некоторые слова могут быть УСЛЫШАНЫ НЕВЕРНО — заменены на похожие по звучанию и ")
        sb.append("длине, но неподходящие по смыслу. Верни СТРОГО JSON с тремя версиями.\n\n")

        sb.append("CLEAN (стенограмма) — ГЛАВНЫЕ ПРАВИЛА:\n")
        sb.append("1. Сохраняй смысл, слова, термины и формулировки лектора. Ничего не перефразируй.\n")
        sb.append("2. Исправляй ошибки распознавания: окончания, падежи, склейки слов, пунктуацию.\n")
        sb.append("3. КОНТЕКСТНОЕ ИСПРАВЛЕНИЕ: если слово не подходит по смыслу, но похоже по звучанию ")
        sb.append("на подходящее — замени. ОСОБО: восстанавливай искажённые названия компаний, ")
        sb.append("брендов, имена (например «зону»→«Ozon» если речь о маркетплейсах). НО будь ОСТОРОЖЕН: ")
        sb.append("не трогай реальные сокращения и названия, если они осмысленны в контексте ")
        sb.append("(например «РВБ» — реальное юрлицо, не меняй). Исправляй только явные искажения.\n")
        sb.append("4. Расставь абзацы по смыслу. Заголовки частей («## Название») используй ОЧЕНЬ ")
        sb.append("аккуратно — только если лекция ЯВНО состоит из нескольких разных тем и это ")
        sb.append("логично помогает структуре. Для короткой или однотемной — без заголовков.\n")
        sb.append("5. Перечисления оформляй списком («- пункт»). Убери речевой мусор.\n")
        sb.append("6. Сохрани все факты, числа, определения, имена дословно.\n")
        sb.append("7. Придумай КОРОТКИЙ осмысленный ЗАГОЛОВОК стенограммы (3-6 слов) по её содержанию.\n\n")

        sb.append("BRIEF (кратко): изложи САМО СОДЕРЖАНИЕ лекции сжато — не описывай «о чём лекция», ")
        sb.append("а перескажи её суть вдвое-втрое короче, сохранив ключевые факты и мысли.\n")
        sb.append("GIST (суть): содержание лекции максимально коротко, самое главное в 2-4 предложениях ")
        sb.append("(изложение сути, а не «эта лекция про...»).\n\n")
        sb.append("Формат:\n{\n")
        sb.append("  \"TITLE\": \"<короткий заголовок 3-6 слов>\",\n")
        sb.append("  \"CLEAN\": \"<стенограмма>\",\n")
        sb.append("  \"BRIEF\": \"<конспект>\",\n")
        sb.append("  \"GIST\": \"<суть>\"\n}\n\n")
        sb.append("Абзацы разделяй пустой строкой. Верни ТОЛЬКО валидный JSON на языке лектора.")
        return sb.toString()
    }

    private fun parseLectureVariants(response: String, fallback: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val jsonStr = response.substringAfter('{', "").substringBeforeLast('}', "")
            .let { if (it.isBlank()) response else "{$it}" }
        val json = try { JSONObject(jsonStr) } catch (_: Exception) {
            return mapOf("${Level.CLEAN.ordinal}:${Tone.NEUTRAL.ordinal}" to fallback)
        }
        // Лекция: тон всегда NEUTRAL
        val t = Tone.NEUTRAL.ordinal
        // Заголовок стенограммы (спецключ "TITLE" — процессор применит к note.title).
        json.optString("TITLE").takeIf { it.isNotBlank() }?.let {
            result["TITLE"] = it.take(40)
        }
        json.optString("CLEAN").takeIf { it.isNotBlank() }?.let {
            result["${Level.CLEAN.ordinal}:$t"] = it
        }
        json.optString("BRIEF").takeIf { it.isNotBlank() }?.let {
            result["${Level.BRIEF.ordinal}:$t"] = it
        }
        json.optString("GIST").takeIf { it.isNotBlank() }?.let {
            result["${Level.GIST.ordinal}:$t"] = it
        }
        if (result.isEmpty()) throw RuntimeException("ИИ не вернул стенограмму")
        return result
    }

    /**
     * АНСАМБЛЬ РАСПОЗНАВАНИЯ: два текста от разных движков (Vosk и Whisper) →
     * ИИ собирает наиболее правильный, выбирая по контексту там, где они расходятся.
     */
    suspend fun assembleFromTwo(voskText: String, whisperText: String, apiKey: String): String =
        withContext(Dispatchers.IO) {
            val user = "Вариант 1 (Vosk):\n$voskText\n\nВариант 2 (Whisper):\n$whisperText"
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", assembleSystemPrompt()))
                put(JSONObject().put("role", "user").put("content", user))
            }
            val (text, _) = request(messages, apiKey, temperature = 0.3)
            text.trim()
        }

    fun assembleSystemPrompt(): String = buildString {
        append("Тебе даны ДВА варианта распознавания ОДНОЙ аудиозаписи разными системами. ")
        append("Они ошибаются в разных местах (заменяют слова на похожие по звучанию). ")
        append("Собери ОДИН правильный текст: там, где варианты расходятся, выбирай слово, ")
        append("которое вернее по смыслу и контексту; если оба явно ошибочны, но созвучны — ")
        append("подбери правильное по звучанию слово. Сохрани все факты и смысл. ")
        append("Расставь пунктуацию и заглавные. Верни ТОЛЬКО собранный текст, без пояснений.")
    }

    suspend fun processAll(rawText: String, apiKey: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", allVariantsPrompt()))
                put(JSONObject().put("role", "user").put("content", rawText))
            }
            // Пакетный ответ должен быть JSON с вариантами. Если модель ответила текстом,
            // который не разбирается, это провал ИМЕННО ЭТОЙ модели — пробуем следующую,
            // а не уходим на общий повтор через 6 секунд (так и набегали минуты ожидания).
            var lastErr = "облако не дало разбираемый ответ"
            repeat(2) { attempt ->
                val (text, model) = request(messages, apiKey, temperature = 0.4,
                    budgetMs = if (attempt == 0) 120_000 else 60_000)
                val parsed = parseAllVariants(text, rawText)
                if (parsed.isNotEmpty()) return@withContext parsed
                lastErr = "модель $model вернула неразбираемый ответ (${text.length} симв)"
                Diagnostics.error("Облако (пакет): $lastErr → пробую другую модель")
            }
            throw RuntimeException(lastErr)
        }

    private fun allVariantsPrompt(): String {
        val sb = StringBuilder()
        sb.append("Ты — редактор голосовых заметок. Текст пришёл из распознавания речи, ")
        sb.append("поэтому в нём могут быть ошибки распознавания, оговорки, повторы, слова-паразиты. ")
        sb.append("Сначала мысленно восстанови смысл, затем верни СТРОГО JSON-объект ")
        sb.append("(без markdown, без пояснений) со всеми вариантами обработки.\n\n")

        // КАСКАД внутри одного запроса: BRIEF считается не от сырой расшифровки, а от
        // уже восстановленного CLEAN, GIST — от BRIEF. Так облако повторяет ту же цепочку,
        // что и локальная модель, и «Кратко» не пересказывает нераспознанную кашу.
        sb.append("Ступени обработки идут ЦЕПОЧКОЙ — каждая следующая работает с результатом предыдущей:\n")
        sb.append("- CLEAN («Чисто») ← из входного текста: ").append(CLEAN_RULE).append("\n")
        sb.append("- BRIEF («Кратко») ← ИЗ СВОЕГО ЖЕ CLEAN, а не из входного текста: изложи то же самое ")
        sb.append("примерно вдвое короче. ПОЛНЫМИ предложениями обычной речью, не телеграфным стилем: ")
        sb.append("нельзя «куплен чай бергамотом и виолончель дочка» — надо «купил чай с бергамотом и ")
        sb.append("виолончель для дочки». Не переписывай фразы дословно — скажи то же своими словами. ")
        sb.append("Сохрани подачу: то же лицо («я», «мы»), тот же порядок мыслей, тот же тон. ")
        sb.append("Слова менять можно, смысл менять нельзя. ВСЕ темы, затронутые в тексте, должны остаться — ")
        sb.append("ни одну не выбрасывай. Числа, имена и названия сохраняй точно. НЕ заменяй конкретные факты ")
        sb.append("обобщениями («замечательная погода» нельзя менять на «красиво» — это разный смысл). ")
        sb.append("НЕ анализируй и не описывай текст: запрещены обороты «в тексте говорится», «автор рассказывает», ")
        sb.append("«этот текст о…», «вот краткий пересказ». Пиши так, будто человек рассказал то же самое, но короче.\n")
        sb.append("- GIST («Суть») ← ИЗ СВОЕГО ЖЕ BRIEF: самое главное, не длиннее четверти исходного текста. ")
        sb.append("Те же запреты: без анализа, без описания текста со стороны, без выдумок. ")
        sb.append("Если тем было несколько — перечисли главное по каждой, не выбрасывай темы.\n\n")

        sb.append("Тона:\n")
        sb.append("- FORMAL: официально-деловой, без разговорных слов.\n")
        sb.append("- NEUTRAL: нейтральный, спокойный.\n")
        sb.append("- CASUAL: живой, разговорный, 1-3 эмодзи по смыслу.\n\n")

        sb.append(CLEAN_EXAMPLE).append("\n\n")

        sb.append("Формат ответа (заполни ВСЕ поля готовым текстом):\n{\n")
        for (l in listOf("CLEAN", "BRIEF", "GIST")) {
            for (t in listOf("FORMAL", "NEUTRAL", "CASUAL")) {
                sb.append("  \"${l}_${t}\": \"<...>\"")
                sb.append(if (l == "GIST" && t == "CASUAL") "\n" else ",\n")
            }
        }
        sb.append("}\n\n")
        sb.append("ЖЁСТКО: связный грамотный текст с пунктуацией; BRIEF и GIST строго короче оригинала; ")
        sb.append("CLEAN по объёму примерно равен оригиналу (не сжимать!); тон FORMAL может быть чуть длиннее ")
        sb.append("из-за деловых оборотов — это допустимо; не выдумывай факты; ")
        sb.append("отвечай на языке оригинала. ОБЯЗАТЕЛЬНО заполни ВСЕ 9 полей JSON — ")
        sb.append("не пропускай ни одного. Верни ТОЛЬКО валидный JSON.")
        return sb.toString()
    }

    /** Парсит JSON-ответ в Map ключей "levelOrd:toneOrd". Устойчив к мелким огрехам. */
    private fun parseAllVariants(response: String, fallbackOrig: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // вырезаем JSON, даже если модель обернула в markdown ```json ... ```
        val jsonStr = response.substringAfter('{', "").substringBeforeLast('}', "")
            .let { if (it.isBlank()) response else "{$it}" }
        val json = try { JSONObject(jsonStr) } catch (_: Exception) {
            // формат сломан — пусто, процессор повторит запрос
            Diagnostics.error("Облако: пакетный ответ не JSON (${response.length} симв): \"${response.take(60)}…\"")
            return emptyMap()
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
        Diagnostics.engine("Облако (пакет): получено вариантов ${result.size} из 9")
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
        maxTokens: Int? = null,
        // Общий предел на ВСЮ операцию, включая перебор моделей. Без него перебор
        // «2 попытки роутера + 3 запасные модели» по 90 с каждая давал до 450 секунд
        // ожидания — в логе пользователя обработка висела 708 с, и он ждал вручную.
        budgetMs: Long = 150_000
    ): Pair<String, String> {
        val deadline = System.currentTimeMillis() + budgetMs
        var lastError = "Не удалось получить ответ ИИ"
        val tried = ArrayList<String>()
        fun left() = deadline - System.currentTimeMillis()

        // 1) основные (openrouter/free): при 404 роутер иногда «прогревается» —
        // делаем до 2 попыток, чтобы успех был с первого нажатия пользователя.
        repeat(2) {
            if (left() > 5_000) {
                try {
                    return requestOnce(FREE_MODELS, messages, apiKey, temperature, maxTokens, left())
                } catch (e: Exception) {
                    lastError = e.message ?: lastError
                    tried.add("openrouter/free: $lastError")
                    if (!lastError.contains("404")) return@repeat
                }
            }
        }
        // 2) запасные — по одной, пока не вышло общее время
        for (m in BACKUP_MODELS) {
            if (left() <= 5_000) break
            try {
                return requestOnce(listOf(m), messages, apiKey, temperature, maxTokens, left())
            } catch (e: Exception) {
                lastError = e.message ?: lastError
                tried.add("$m: $lastError")
            }
        }
        // Честный отчёт: какие модели пробовали и что ответили. Раньше в лог уходила
        // только последняя ошибка, и понять «почему так долго» было нельзя.
        Diagnostics.error("Облако: ни одна модель не ответила за ${(budgetMs - left()) / 1000} с. " +
            tried.joinToString(" | ").take(300))
        throw RuntimeException(lastError)
    }

    private fun requestOnce(
        models: List<String>,
        messages: JSONArray,
        apiKey: String,
        temperature: Double,
        maxTokens: Int?,
        // Сколько времени осталось на ВСЮ операцию: одна модель не должна съедать всё.
        timeLeftMs: Long = 90_000
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
            // Ждём не дольше, чем осталось на всю операцию (и не дольше 75 с на модель:
            // бесплатная модель, молчащая больше минуты, обычно уже не ответит).
            readTimeout = timeLeftMs.coerceIn(10_000, 75_000).toInt()
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("HTTP-Referer", "https://github.com/dubik81/voicenotes")
            setRequestProperty("X-Title", "Smysl-zametki")
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // Остаток лимита из заголовков (OpenRouter шлёт X-RateLimit-*).
            conn.getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()?.let {
                rateLimitRemaining = it
            }
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
            // ОТВЕТ-ПУСТЫШКА. Модель отвечает HTTP 200, а в содержимом — «null».
            // Раньше это считалось успехом: разбор падал («пакетный ответ не JSON: null»),
            // обработка уходила на повтор, и всё вместе висело больше 10 минут.
            // Теперь это провал ЭТОЙ модели — сразу берём следующую.
            if (maxTokens == null && isUselessAnswer(text)) {
                throw RuntimeException("Модель вернула пустышку («${text.take(20)}»)")
            }
            // Фильтр вердикта модерации: некоторые модели вместо обработки возвращают
            // «User Safety: unsafe / Safety Categories: ...». Это не результат — отказ.
            val low = text.lowercase()
            if (low.startsWith("user safety") || low.contains("safety categories:") ||
                (low.contains("user safety:") && text.length < 200)) {
                throw RuntimeException("Модель отклонила текст (модерация). Попробуйте другой вариант.")
            }
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

    // Кавычки и обратные апострофы, в которые модель иногда заворачивает ответ.
    private val QUOTE_CHARS = charArrayOf('\u0022', '\u0027', '\u0060')

    /** Ответ формально есть, а толку нет: «null», «none», «-», пара символов. */
    private fun isUselessAnswer(text: String): Boolean {
        val t = text.trim().trim { it.isWhitespace() || it in QUOTE_CHARS }.lowercase()
        return t in listOf("null", "none", "nil", "n/a", "-", "—", "{}", "[]", "undefined") || t.length < 3
    }

    /**
     * БЫСТРАЯ ПРОВЕРКА ДОСТУПНОСТИ (v122).
     *
     * Идея пользователя: спрашивать у облака «жив ли ты» ДО того, как отправлять текст,
     * — при открытии заметки в режиме «Смысл Онл» или при переключении на Онл. Тогда о
     * проблеме он узнаёт сразу, а не после десяти минут ожидания.
     *
     * Запрос крошечный (одно слово, ответ в 1 токен) и с коротким временем ожидания,
     * поэтому проверка почти ничего не стоит. Результат кэшируется на 2 минуты, чтобы
     * не дёргать сервер при каждом открытии заметки.
     */
    @Volatile private var lastCheckAt = 0L
    @Volatile private var lastCheckResult: Pair<Boolean, String>? = null

    suspend fun quickCheck(apiKey: String, force: Boolean = false): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext false to "Ключ OpenRouter не задан (настройки)"
            val cached = lastCheckResult
            if (!force && cached != null && System.currentTimeMillis() - lastCheckAt < 120_000) {
                return@withContext cached
            }
            val t0 = System.currentTimeMillis()
            val res = try {
                val messages = JSONArray().apply {
                    put(JSONObject().put("role", "user").put("content", "ping"))
                }
                val (_, model) = requestOnce(FREE_MODELS, messages, apiKey, 0.0, 1, timeLeftMs = 12_000)
                true to "облачный ИИ отвечает ($model, ${System.currentTimeMillis() - t0} мс)"
            } catch (e: Exception) {
                false to (e.message ?: "облако не отвечает")
            }
            lastCheckAt = System.currentTimeMillis(); lastCheckResult = res
            val verdict = if (res.first) "OK" else "ПРОБЛЕМА"
            val ms = System.currentTimeMillis() - t0
            Diagnostics.info("Проверка облака: $verdict — ${res.second} [$ms мс]")
            res
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
            Level.CLEAN -> CLEAN_RULE + "\n" + CLEAN_EXAMPLE
            // ВХОД для BRIEF/GIST — уже восстановленный текст предыдущей ступени (каскад
            // в VariantProcessor.sourceFor), поэтому чинить распознавание здесь не нужно:
            // задача только изложить короче, той же подачей.
            Level.BRIEF ->
                "Изложи этот текст примерно вдвое короче. Пиши ПОЛНЫМИ предложениями обычной речью — " +
                "не телеграфным стилем, не выбрасывай предлоги и связки (нельзя «куплен чай бергамотом " +
                "и виолончель дочка» — надо «купил чай с бергамотом и виолончель для дочки»). " +
                "Не переписывай исходные фразы дословно — скажи то же своими словами. " +
                "Сохрани подачу: то же лицо («я», «мы»), " +
                "тот же порядок мыслей, тот же тон. Слова менять можно, смысл менять нельзя. " +
                "ВСЕ темы, затронутые в тексте, должны остаться — ни одну не выбрасывай. " +
                "Числа, имена и названия сохраняй точно, не заменяй факты обобщениями " +
                "(«замечательная погода» ≠ «красиво»). НЕ анализируй и не описывай текст со стороны: " +
                "запрещены обороты «в тексте говорится», «автор рассказывает», «этот текст о…», " +
                "«вот краткий пересказ». Пиши так, будто человек рассказал то же самое, только короче."
            Level.GIST ->
                "Изложи самое главное, не длиннее четверти исходного текста, одним абзацем. " +
                "Если тем несколько — коротко по каждой, ни одну не выбрасывай. " +
                "Факты, названия и смысл — из оригинала, без выдумок. НЕ анализируй и не описывай " +
                "текст со стороны («этот текст о…», «автор говорит») — продолжай речь самого человека. " +
                "Грамотные законченные предложения, никаких обрывков."
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
4. Для «Кратко» результат примерно вдвое короче оригинала, для «Суть» — в несколько раз короче. Для «Чисто» — примерно той же длины (это не сжатие).
5. Отвечай на языке оригинала.
6. Не выдумывай фактов, которых нет в оригинале.
7. Верни ТОЛЬКО готовый текст — без пояснений, заголовков и кавычек.

Плохо (так НЕЛЬЗЯ): «нам это как бы надо на улицу ну погулять короче»
Хорошо: «Нам нужно выйти на улицу и погулять.»
""".trim()
    }
}

package com.example.voicenotes

/**
 * Надёжное офлайн-«Чисто»: детерминированная чистка текста по правилам.
 * НЕ использует ИИ — значит НЕ глючит и НЕ галлюцинирует. Работает всегда.
 * Даёт читаемый грамотный текст: убирает паразитов, повторы, ставит пунктуацию.
 *
 * Это «запасной парашют» для режима «Чисто»: не так умён, как облачный ИИ,
 * но раскрывается на 200% — всегда даёт корректный результат без мусора.
 */
object CleanProcessor {

    // Слова-паразиты и междометия, которые вырезаем.
    private val fillers = setOf(
        "э", "ээ", "эээ", "ну", "значит", "короче", "типа", "как-бы", "кароч",
        "это-самое", "вот", "так-сказать", "в-общем", "собственно", "ммм", "мм",
        "аа", "эм", "гм", "уф", "блин", "ёлки", "слушай", "слушайте"
    )
    // Двусловные паразиты («это самое», «как бы», «то есть» в роли заминки).
    private val fillerPairs = setOf("это самое", "как бы", "так сказать", "в общем")

    private val commaBefore = setOf(
        "но", "а", "зато", "однако", "который", "которая", "которое", "которые",
        "потому", "поэтому", "чтобы", "если", "хотя", "когда", "пока", "что", "чем",
        "будто", "словно", "также", "тоже", "либо", "или"
    )

    private val sentenceStarters = setOf(
        "я", "мы", "он", "она", "они", "это", "вот", "давай", "давайте",
        "нужно", "надо", "затем", "потом", "далее", "кстати", "итак", "сегодня",
        "первый", "второй", "третий", "первое", "второе", "третье"
    )

    // Предлоги: заглавное слово после них — скорее имя собственное («в Москве»), не начало фразы.
    private val prepositions = setOf(
        "в", "во", "на", "к", "ко", "из", "у", "о", "об", "обо", "с", "со", "по", "за", "под",
        "при", "от", "до", "для", "про", "над", "перед", "между", "через", "без", "около", "у"
    )

    /**
     * Главный метод: сырой текст → чистый читаемый текст.
     * googleCaps = true для текста из онлайн-распознавания Google: оно ставит заглавную
     * букву в начале каждой распознанной фразы, но часто НЕ ставит точку перед ней
     * («…дом построить Нарисуем будем жить Так.»). Такие заглавные считаем границей
     * предложения (кроме имён после предлогов).
     */
    fun clean(raw: String, googleCaps: Boolean = false): String {
        if (raw.isBlank()) return ""
        var t = raw.trim().replace(Regex("\\s+"), " ")
        if (googleCaps) t = splitByCapitals(t)
        // Нормализуем протяжные междометия с дефисами/повторами букв: «э-э», «э-э-э», «ааа».
        t = t.replace(Regex("(?i)\\bэ+([-\\s]*э+)*\\b"), " ")
        t = t.replace(Regex("(?i)\\bа{2,}\\b"), " ")
        t = t.replace(Regex("(?i)\\bм{2,}\\b"), " ")

        // 1. Убираем двусловные паразиты.
        for (fp in fillerPairs) {
            t = t.replace(Regex("(?i)\\b${Regex.escape(fp)}\\b"), " ")
        }
        // 2. Разбиваем на слова, убираем одиночные паразиты и подряд-повторы.
        val words = t.split(" ").filter { it.isNotBlank() }
        val out = ArrayList<String>()
        var prevClean = ""
        for (w in words) {
            val bare = w.lowercase().trim('.', ',', '!', '?', ':', ';', '-', '«', '»', '"')
            if (bare in fillers) continue                 // паразит — выкидываем
            if (bare.isNotEmpty() && bare == prevClean) continue  // повтор слова подряд («вопрос вопрос»)
            out.add(w)
            if (bare.isNotEmpty()) prevClean = bare
        }
        if (out.isEmpty()) return Punctuator.punctuate(raw)  // подстраховка

        // 3. Расставляем пунктуацию и предложения.
        val sb = StringBuilder()
        for ((i, w) in out.withIndex()) {
            val bare = w.lowercase().trim('.', ',', '!', '?', ':', ';')
            // новое предложение перед маркером-стартером
            if (i > 2 && bare in sentenceStarters &&
                !sb.trimEnd().endsWith(".") && sb.length > 10) {
                trimTrailingSpace(sb); sb.append(". ")
                sb.append(w).append(" "); continue
            }
            // запятая перед союзом
            if (i > 0 && bare in commaBefore &&
                !sb.trimEnd().endsWith(",") && !sb.trimEnd().endsWith(".")) {
                trimTrailingSpace(sb); sb.append(", ")
            }
            sb.append(w).append(" ")
        }
        var res = sb.toString().trim()
        if (res.isNotEmpty() && res.last() !in charArrayOf('.', '!', '?')) res += "."
        res = res.replace(Regex("(?<=^|\\s)я(?=[\\s,.!?]|$)"), "Я")
        // Нормализация двойной пунктуации.
        res = res.replace(Regex("\\s+([,.!?])"), "$1")
        res = res.replace(Regex(",\\s*\\."), ".")
        res = res.replace(Regex("\\.{2,}"), ".")
        res = res.replace(Regex(",{2,}"), ",")
        res = res.replace(Regex("([.!?])\\s*,"), "$1")
        return capitalizeSentences(res)
    }

    // Заглавные слова, которые почти всегда начинают предложение (не имена).
    private val capStarters = setOf(
        "я", "мы", "ты", "вы", "он", "она", "они", "оно", "это", "этот", "эта", "эти", "так", "итак",
        "затем", "потом", "далее", "сегодня", "вчера", "завтра", "теперь", "здесь", "там", "тут",
        "если", "когда", "что", "как", "где", "почему", "зачем", "поэтому", "например", "также",
        "кстати", "может", "можно", "нужно", "надо", "есть", "нет", "да", "ну", "вот", "но", "а", "и",
        "хорошо", "ладно", "спасибо", "конечно", "значит", "первое", "второе", "третье", "все", "всё",
        "ещё", "еще", "давай", "давайте", "пусть", "пока", "потому", "хотя", "однако", "короче",
        "в", "во", "на", "к", "с", "у", "о", "по", "за", "для", "при", "от", "до", "про", "из"
    )
    // Окончания глаголов — «Нарисуем», «Видит», «Была»: такое слово с заглавной — начало фразы.
    private val verbEndings = listOf("ем", "ём", "им", "ешь", "ёшь", "ишь", "ет", "ёт", "ит", "ут",
        "ют", "ат", "ят", "йте", "ите", "ать", "ять", "ить", "ти", "ся", "сь", "ал", "ил", "ла", "ло", "ли", "ел", "ел")

    private fun looksLikeSentenceStart(word: String): Boolean {
        val bare = word.lowercase().trim(',', '.', '!', '?', ':', ';', '«', '»', '"')
        if (bare in capStarters) return true
        if (bare.length >= 4 && verbEndings.any { bare.endsWith(it) }) return true
        return false
    }

    /**
     * Точка перед заглавным словом посреди фразы (см. clean/googleCaps).
     * Чтобы не рубить перед именами («встретил Ивана»), делим ТОЛЬКО если заглавное
     * слово похоже на начало фразы: местоимение/союз/наречие или глагол по окончанию.
     */
    fun splitByCapitals(text: String): String {
        val words = text.split(" ").filter { it.isNotBlank() }
        val out = StringBuilder()
        for ((i, w) in words.withIndex()) {
            if (i > 0) {
                val prev = words[i - 1]
                val prevBare = prev.lowercase().trim(',', '.', '!', '?', ':', ';', '«', '»', '"')
                val first = w.firstOrNull { it.isLetter() }
                val isCap = first != null && first.isUpperCase() && w.drop(1).any { it.isLowerCase() }
                val prevEndsSentence = prev.endsWith(".") || prev.endsWith("!") || prev.endsWith("?")
                if (isCap && !prevEndsSentence && prevBare !in prepositions && !prev.endsWith(",") &&
                    looksLikeSentenceStart(w)) {
                    trimTrailingSpace(out); out.append(". ")
                }
            }
            out.append(w).append(" ")
        }
        return out.toString().trim()
    }

    /** Нормализация пунктуации после модели: пробелы перед знаками, «,.», «..», заглавные. */
    fun normalizePunct(text: String): String {
        var res = text.trim().replace(Regex("\\s+"), " ")
        res = res.replace(Regex("\\s+([,.!?;:])"), "$1")
        res = res.replace(Regex(",\\s*\\."), ".")
        res = res.replace(Regex("\\.{2,}"), ".")
        res = res.replace(Regex(",{2,}"), ",")
        res = res.replace(Regex("([.!?])\\s*,"), "$1")
        res = res.replace(Regex("([,.!?;:])(?=[^\\s\\d»\"\\)])"), "$1 ")
        if (res.isNotEmpty() && res.last() !in charArrayOf('.', '!', '?')) res += "."
        return res
    }

    private fun trimTrailingSpace(sb: StringBuilder) {
        while (sb.isNotEmpty() && sb.last() == ' ') sb.deleteCharAt(sb.length - 1)
    }

    private fun capitalizeSentences(text: String): String {
        val sb = StringBuilder()
        var cap = true
        for (ch in text) {
            if (cap && ch.isLetter()) { sb.append(ch.uppercaseChar()); cap = false }
            else { sb.append(ch); if (ch == '.' || ch == '!' || ch == '?') cap = true }
        }
        return sb.toString()
    }
}

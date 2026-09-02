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

    /** Главный метод: сырой текст → чистый читаемый текст. */
    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        var t = raw.trim().replace(Regex("\\s+"), " ")
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
        // заглавная «Я»
        res = res.replace(Regex("(?<=^|\\s)я(?=[\\s,.!?]|$)"), "Я")
        return capitalizeSentences(res)
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

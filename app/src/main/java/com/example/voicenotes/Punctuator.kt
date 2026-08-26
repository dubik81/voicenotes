package com.example.voicenotes

/**
 * Лёгкая расстановка пунктуации и заглавных букв в «сыром» распознанном тексте
 * (работает без ИИ, мгновенно). Не идеальна, но делает текст читаемым:
 *  - первая буква и буквы после . ! ? — заглавные;
 *  - точка в конце, если её нет;
 *  - запятые перед распространёнными союзами/вводными;
 *  - «я» пишется заглавной.
 */
object Punctuator {

    // Слова, перед которыми в русском часто нужна запятая.
    private val commaBefore = setOf(
        "но", "а", "зато", "однако", "который", "которая", "которое", "которые",
        "потому", "поэтому", "чтобы", "если", "хотя", "когда", "пока", "что", "чем",
        "будто", "словно", "также", "тоже", "либо"
    )

    fun punctuate(raw: String): String {
        if (raw.isBlank()) return ""
        var t = raw.trim().replace(Regex("\\s+"), " ")

        // Запятые перед союзами (грубо, но помогает читаемости).
        val words = t.split(" ")
        val out = StringBuilder()
        for ((i, w) in words.withIndex()) {
            val clean = w.lowercase().trim(',', '.', '!', '?')
            val needComma = i > 0 && clean in commaBefore &&
                    !out.trimEnd().endsWith(",")
            if (needComma) {
                // заменяем последний пробел на ", "
                if (out.isNotEmpty()) {
                    while (out.isNotEmpty() && out.last() == ' ') out.deleteCharAt(out.length - 1)
                    out.append(", ")
                }
            }
            out.append(w).append(" ")
        }
        t = out.toString().trim()

        // Точка в конце.
        if (t.isNotEmpty() && t.last() !in charArrayOf('.', '!', '?')) t += "."

        // «я» как отдельное слово — заглавная.
        t = t.replace(Regex("(?<=^|\\s)я(?=[\\s,.!?]|$)"), "Я")

        return capitalizeSentences(t)
    }

    fun capitalizeSentences(text: String): String {
        if (text.isBlank()) return text
        val sb = StringBuilder()
        var capNext = true
        for (ch in text) {
            if (capNext && ch.isLetter()) { sb.append(ch.uppercaseChar()); capNext = false }
            else {
                sb.append(ch)
                if (ch == '.' || ch == '!' || ch == '?') capNext = true
            }
        }
        return sb.toString()
    }
}

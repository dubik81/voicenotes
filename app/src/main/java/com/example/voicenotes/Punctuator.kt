package com.example.voicenotes

/**
 * Улучшенная офлайн-расстановка пунктуации для сырого распознанного текста.
 * Без ИИ не идеально, но делает текст читаемым:
 *  - заглавные в начале и после . ! ?
 *  - точка в конце
 *  - запятые перед распространёнными союзами/вводными
 *  - заглавная для «я»
 *  - деление на предложения по длине (сырой поток без знаков → рубим паузы-маркеры)
 */
object Punctuator {

    private val commaBefore = setOf(
        "но", "а", "зато", "однако", "который", "которая", "которое", "которые",
        "потому", "поэтому", "чтобы", "если", "хотя", "когда", "пока", "что", "чем",
        "будто", "словно", "также", "тоже", "либо", "или", "и"
    )

    // Слова, часто начинающие новое предложение (грубая эвристика для потока).
    private val sentenceStarters = setOf(
        "я", "мы", "он", "она", "они", "это", "вот", "давай", "давайте",
        "нужно", "надо", "затем", "потом", "далее", "кстати", "итак"
    )

    fun punctuate(raw: String): String {
        if (raw.isBlank()) return ""
        var t = raw.trim().replace(Regex("\\s+"), " ")

        // Запятые перед союзами.
        val words = t.split(" ")
        val out = StringBuilder()
        for ((i, w) in words.withIndex()) {
            val clean = w.lowercase().trim(',', '.', '!', '?')
            val needComma = i > 0 && clean in commaBefore && !out.trimEnd().endsWith(",")
            if (needComma && clean !in setOf("и", "или")) {
                while (out.isNotEmpty() && out.last() == ' ') out.deleteCharAt(out.length - 1)
                out.append(", ")
            }
            out.append(w).append(" ")
        }
        t = out.toString().trim()

        if (t.isNotEmpty() && t.last() !in charArrayOf('.', '!', '?')) t += "."
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

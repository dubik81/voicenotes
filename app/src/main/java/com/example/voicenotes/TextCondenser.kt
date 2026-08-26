package com.example.voicenotes

/**
 * Бесплатное сжатие текста на правилах (без ИИ), по ступеням Level.
 * Всегда возвращает связный текст с пунктуацией (через Punctuator).
 */
object TextCondenser {

    // Паразиты и звуки. Ловим и точные слова, и «растянутые» звуки регэкспом.
    private val fillerWords = setOf(
        "ну", "вот", "типа", "короче", "значит", "это", "самое", "блин", "мда",
        "угу", "ага", "походу", "допустим", "получается", "собственно", "слушай",
        "слушайте", "понимаешь", "понимаете", "знаешь", "знаете", "так-то", "ваще",
        "вообще-то", "как-бы", "как", "бы", "же", "ли", "уж", "то-есть"
    )
    private val fillerPhrases = listOf(
        "как бы", "это самое", "в общем", "так сказать", "то есть", "на самом деле",
        "по сути", "в принципе", "как говорится", "скажем так", "грубо говоря"
    )
    // Звуки-заполнители: ээ, ммм, эээ, ааа, эмм и т.п.
    private val soundRegex = Regex("(?i)\\b(э+|м+|а+|ну+|мм+|эм+|хм+)\\b")

    private val emotionalWords = setOf(
        "ужасно", "шикарно", "офигенно", "классно", "супер", "круто", "реально",
        "прям", "прямо", "жесть", "капец", "кошмар", "восхитительно", "отвратительно",
        "прекрасно", "великолепно", "жутко", "дико", "очень", "чертовски"
    )
    private val introPhrases = listOf(
        "честно говоря", "если честно", "мне кажется", "я думаю", "я считаю",
        "по моему мнению", "как мне кажется", "в целом", "к слову", "кстати",
        "между прочим", "надо сказать", "стоит отметить", "хочу сказать"
    )

    /** Главный вход: текст + ступень → результат для бесплатного режима. */
    fun condense(original: String, level: Level): String {
        if (original.isBlank()) return ""
        // Дословно — просто пунктуированный оригинал.
        if (level == Level.VERBATIM) return Punctuator.punctuate(original)

        var t = " ${original.lowercase()} "

        // Убираем звуки-заполнители всегда (начиная с CLEAN).
        t = soundRegex.replace(t, " ")

        // Многословные паразиты.
        for (p in fillerPhrases.sortedByDescending { it.length })
            t = t.replace(" $p ", " ", ignoreCase = true)

        // Однословные паразиты.
        var tokens = t.trim().split(Regex("\\s+")).filter { w ->
            val c = w.trim(',', '.', '!', '?', ';', ':').lowercase()
            c.isNotBlank() && c !in fillerWords
        }
        tokens = dedupeConsecutive(tokens)

        // Со ступени TIGHT — эмоции и вводные.
        if (level.ordinal >= Level.TIGHT.ordinal) {
            tokens = tokens.filter { w ->
                w.trim(',', '.', '!', '?', ';', ':').lowercase() !in emotionalWords
            }
            var s = tokens.joinToString(" ")
            for (p in introPhrases.sortedByDescending { it.length })
                s = s.replace(Regex("(?i)\\b${Regex.escape(p)}\\b[,\\s]*"), "")
            tokens = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        }

        var result = tokens.joinToString(" ")

        // Красные ступени — оставляем ключевые предложения.
        if (level.isRed) {
            val keepFraction = if (level == Level.BRIEF) 0.5f else 0.25f
            result = keepKeySentences(Punctuator.punctuate(result), keepFraction)
        }

        return Punctuator.punctuate(result)
    }

    private fun dedupeConsecutive(tokens: List<String>): List<String> {
        val out = ArrayList<String>()
        for (w in tokens) {
            val cur = w.trim(',', '.', '!', '?').lowercase()
            val prev = out.lastOrNull()?.trim(',', '.', '!', '?')?.lowercase()
            if (cur != prev) out.add(w)
        }
        return out
    }

    private fun keepKeySentences(text: String, fraction: Float): String {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.size <= 1) return text
        val scored = sentences.mapIndexed { i, s ->
            Triple(i, s, s.split(Regex("\\s+")).count { it.length > 3 })
        }
        val keepCount = (sentences.size * fraction).toInt().coerceAtLeast(1)
        val kept = scored.sortedByDescending { it.third }.take(keepCount)
            .map { it.first }.toSet()
        return scored.filter { it.first in kept }.sortedBy { it.first }
            .joinToString(" ") { it.second }
    }

    fun zoneHint(level: Level): String = when {
        level == Level.VERBATIM -> "Дословно: полный текст, только пунктуация."
        !level.isRed -> "Зелёная зона: факты сохранены, убрана лишь форма."
        else -> "Красная зона: детали обобщаются, часть информации теряется."
    }
}

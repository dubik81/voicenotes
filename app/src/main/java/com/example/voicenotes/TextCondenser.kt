package com.example.voicenotes

/**
 * Бесплатное сжатие текста на правилах (без облачного ИИ), с двумя зонами.
 *
 * ЗЕЛЁНАЯ зона (concentration < THRESHOLD): сжатие БЕЗ потери фактов.
 *   Убираем только "упаковку": паразитов, звуки, повторы, эмоции, обороты.
 *   Ни одно предложение целиком не выбрасывается.
 *
 * КРАСНАЯ зона (concentration >= THRESHOLD): сжатие С потерей деталей.
 *   Дополнительно оставляем только самые содержательные предложения —
 *   часть информации теряется ради общего смысла.
 */
object TextCondenser {

    const val THRESHOLD = 0.6f

    private val fillerWords = setOf(
        "эээ", "ээ", "э", "ммм", "мм", "ааа", "аа",
        "ну", "вот", "типа", "как бы", "както", "как-то",
        "короче", "значит", "это самое", "в общем", "в общемто",
        "собственно", "так сказать", "допустим", "получается",
        "блин", "мда", "угу", "ага",
        "походу", "по сути", "как говорится",
        "слушай", "слушайте", "понимаешь", "понимаете", "знаешь", "знаете"
    )

    private val introPhrases = listOf(
        "на самом деле", "честно говоря", "если честно", "мне кажется",
        "я думаю", "я считаю", "по моему мнению", "как мне кажется",
        "в принципе", "в целом", "к слову", "кстати", "между прочим",
        "надо сказать", "стоит отметить", "хочу сказать", "скажем так"
    )

    // Эмоциональные / оценочные слова, которые убираем ближе к порогу (в зелёной зоне).
    private val emotionalWords = setOf(
        "ужасно", "шикарно", "офигенно", "классно", "супер", "круто",
        "отвратительно", "прекрасно", "великолепно", "жутко", "дико",
        "реально", "прям", "прямо", "очень-очень", "чёрт", "чертов", "чёртов",
        "капец", "жесть", "кошмар", "восхитительно"
    )

    fun condense(text: String, concentration: Float): String {
        if (text.isBlank()) return ""
        var result = text.trim()

        if (concentration <= 0.02f) return normalizeSpaces(result)

        val isGreen = concentration < THRESHOLD

        if (isGreen) {
            val g = (concentration / THRESHOLD).coerceIn(0f, 1f)
            result = removeFillers(result)                       // всегда
            if (g >= 0.33f) result = removeEmotions(result)      // + эмоции
            if (g >= 0.66f) result = removeIntros(result)        // + обороты
        } else {
            // Красная зона: сначала полная чистка формы, затем отбор предложений.
            result = removeFillers(result)
            result = removeEmotions(result)
            result = removeIntros(result)
            result = keepKeySentences(result, concentration)
        }

        return capitalizeSentences(normalizeSpaces(result))
    }

    /** Подсказка для интерфейса: описание того, что делает текущее положение ползунка. */
    fun zoneHint(concentration: Float): String = when {
        concentration <= 0.02f -> "Дословно: текст без изменений."
        concentration < THRESHOLD -> "Зелёная зона: факты сохранены, убирается только форма."
        else -> "Красная зона: детали обобщаются, часть информации теряется."
    }

    private fun removeFillers(text: String): String {
        var t = " $text "
        val multi = fillerWords.filter { it.contains(" ") }.sortedByDescending { it.length }
        for (f in multi) t = t.replace(" $f ", " ", ignoreCase = true)
        val single = fillerWords.filter { !it.contains(" ") }
        val tokens = t.trim().split(Regex("\\s+")).filter { token ->
            val clean = token.trim(',', '.', '!', '?', ';', ':').lowercase()
            clean.isNotBlank() && clean !in single
        }
        // Схлопываем подряд идущие повторы одного слова.
        val deduped = ArrayList<String>()
        for (w in tokens) {
            val cur = w.trim(',', '.', '!', '?').lowercase()
            val prev = deduped.lastOrNull()?.trim(',', '.', '!', '?')?.lowercase()
            if (cur != prev) deduped.add(w)
        }
        return deduped.joinToString(" ")
    }

    private fun removeEmotions(text: String): String {
        val tokens = text.split(Regex("\\s+")).filter { token ->
            val clean = token.trim(',', '.', '!', '?', ';', ':').lowercase()
            clean !in emotionalWords
        }
        return tokens.joinToString(" ").replace("!", ".")
    }

    private fun removeIntros(text: String): String {
        var t = text
        for (p in introPhrases.sortedByDescending { it.length }) {
            t = t.replace(Regex("(?i)\\b${Regex.escape(p)}\\b[,\\s]*"), "")
        }
        return t
    }

    private fun keepKeySentences(text: String, concentration: Float): String {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.size <= 1) return text

        val scored = sentences.mapIndexed { i, s ->
            val score = s.split(Regex("\\s+")).count { it.length > 3 }
            Triple(i, s, score)
        }
        // r: 0 на пороге -> 1 в конце. Оставляем от ~75% до ~20% предложений.
        val r = ((concentration - THRESHOLD) / (1f - THRESHOLD)).coerceIn(0f, 1f)
        val keepFraction = (0.75f - r * 0.55f).coerceIn(0.2f, 0.75f)
        val keepCount = (sentences.size * keepFraction).toInt().coerceAtLeast(1)

        val kept = scored.sortedByDescending { it.third }.take(keepCount)
            .map { it.first }.toSet()
        return scored.filter { it.first in kept }.sortedBy { it.first }
            .joinToString(" ") { it.second }
    }

    private fun normalizeSpaces(text: String): String =
        text.replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("([,.!?;:]){2,}"), "$1")
            .trim()

    private fun capitalizeSentences(text: String): String {
        if (text.isBlank()) return text
        val sb = StringBuilder()
        var capNext = true
        for (ch in text) {
            if (capNext && ch.isLetter()) { sb.append(ch.uppercaseChar()); capNext = false }
            else { sb.append(ch); if (ch == '.' || ch == '!' || ch == '?') capNext = true }
        }
        return sb.toString()
    }
}

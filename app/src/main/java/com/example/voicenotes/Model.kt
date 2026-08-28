package com.example.voicenotes

import org.json.JSONArray
import org.json.JSONObject

/** Четыре ступени сжатия смысла. Порядок = слева направо. */
enum class Level(val title: String, val short: String) {
    VERBATIM("Дословно", "Полный текст с пунктуацией, без изменений смысла"),
    CLEAN("Чисто", "Убраны паразиты, звуки и повторы"),
    BRIEF("Кратко", "Пересказ главного своими словами"),
    GIST("Суть", "Красивое короткое резюме, самая суть");

    companion object {
        fun fromIndex(i: Int): Level = entries.getOrElse(i.coerceIn(0, entries.size - 1)) { VERBATIM }
        val count get() = entries.size
        /** Индекс, с которого начинается «красная зона» (потеря деталей). */
        const val RED_FROM = 2 // BRIEF и GIST — красные
    }

    val isRed get() = ordinal >= RED_FROM
}

/** Тон итогового текста. */
enum class Tone(val title: String) {
    FORMAL("Формально"),
    NEUTRAL("Обычно"),
    CASUAL("Живой");

    companion object {
        fun fromIndex(i: Int): Tone = entries.getOrElse(i.coerceIn(0, entries.size - 1)) { NEUTRAL }
        val count get() = entries.size
    }
}

/**
 * Заметка. Хранит оригинал (дословный текст с пунктуацией) и кэш готовых
 * вариантов по каждой ступени, чтобы ползунок переключался мгновенно.
 * Ключ кэша: "levelOrdinal:toneOrdinal".
 */
data class Note(
    val id: Long,
    var title: String,
    val createdAt: Long,
    var original: String,                       // текущий рабочий текст (с пунктуацией)
    var audioPath: String? = null,              // путь к аудио, если сохранялось
    var recordMode: String = "",                // "vosk" или "google" — чем записана
    var refinedText: String? = null,            // не используется (совместимость)
    var isRefined: Boolean = false,             // не используется (совместимость)
    val variants: MutableMap<String, String> = mutableMapOf(),
    // История редакций: ключ "levelOrd:toneOrd" -> список версий текста.
    val history: MutableMap<String, MutableList<String>> = mutableMapOf(),
    // Текущий индекс в истории для каждого варианта.
    val historyIndex: MutableMap<String, Int> = mutableMapOf()
) {
    fun variantKey(level: Level, tone: Tone) = "${level.ordinal}:${tone.ordinal}"

    fun getVariant(level: Level, tone: Tone): String? =
        if (level == Level.VERBATIM) original else variants[variantKey(level, tone)]

    fun putVariant(level: Level, tone: Tone, text: String) {
        val key = variantKey(level, tone)
        variants[key] = text
        // добавляем в историю (если это новая версия, не дубль текущей)
        val hist = history.getOrPut(key) { mutableListOf() }
        if (hist.isEmpty() || hist.last() != text) {
            // если мы не в конце истории — обрезаем «будущее» перед добавлением
            val idx = historyIndex[key] ?: (hist.size - 1)
            while (hist.size > idx + 1) hist.removeAt(hist.size - 1)
            hist.add(text)
            historyIndex[key] = hist.size - 1
        }
    }

    /** Можно ли шагнуть к предыдущей версии. */
    fun canGoBack(level: Level, tone: Tone): Boolean {
        val key = variantKey(level, tone)
        return (historyIndex[key] ?: 0) > 0
    }

    /** Можно ли шагнуть к более поздней версии. */
    fun canGoForward(level: Level, tone: Tone): Boolean {
        val key = variantKey(level, tone)
        val hist = history[key] ?: return false
        return (historyIndex[key] ?: 0) < hist.size - 1
    }

    /** Шаг к предыдущей версии. */
    fun goBack(level: Level, tone: Tone) {
        val key = variantKey(level, tone)
        val hist = history[key] ?: return
        val idx = (historyIndex[key] ?: 0) - 1
        if (idx >= 0) { historyIndex[key] = idx; variants[key] = hist[idx] }
    }

    /** Шаг к более поздней версии. */
    fun goForward(level: Level, tone: Tone) {
        val key = variantKey(level, tone)
        val hist = history[key] ?: return
        val idx = (historyIndex[key] ?: 0) + 1
        if (idx < hist.size) { historyIndex[key] = idx; variants[key] = hist[idx] }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("original", original)
        put("recordMode", recordMode)
        put("audioPath", audioPath ?: JSONObject.NULL)
        put("refinedText", refinedText ?: JSONObject.NULL)
        put("isRefined", isRefined)
        val v = JSONObject()
        variants.forEach { (k, value) -> v.put(k, value) }
        put("variants", v)
        // история редакций
        val h = JSONObject()
        history.forEach { (k, list) ->
            val arr = JSONArray(); list.forEach { arr.put(it) }; h.put(k, arr)
        }
        put("history", h)
        val hi = JSONObject()
        historyIndex.forEach { (k, idx) -> hi.put(k, idx) }
        put("historyIndex", hi)
    }

    companion object {
        fun fromJson(o: JSONObject): Note {
            val variants = mutableMapOf<String, String>()
            o.optJSONObject("variants")?.let { vj ->
                vj.keys().forEach { k -> variants[k] = vj.getString(k) }
            }
            val history = mutableMapOf<String, MutableList<String>>()
            o.optJSONObject("history")?.let { hj ->
                hj.keys().forEach { k ->
                    val arr = hj.getJSONArray(k)
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) list.add(arr.getString(i))
                    history[k] = list
                }
            }
            val historyIndex = mutableMapOf<String, Int>()
            o.optJSONObject("historyIndex")?.let { hij ->
                hij.keys().forEach { k -> historyIndex[k] = hij.getInt(k) }
            }
            return Note(
                id = o.getLong("id"),
                title = o.getString("title"),
                createdAt = o.getLong("createdAt"),
                original = o.getString("original"),
                recordMode = o.optString("recordMode", ""),
                audioPath = o.optString("audioPath").takeIf { it.isNotBlank() && it != "null" },
                refinedText = o.optString("refinedText").takeIf { it.isNotBlank() && it != "null" },
                isRefined = o.optBoolean("isRefined", false),
                variants = variants,
                history = history,
                historyIndex = historyIndex
            )
        }

        fun listToJson(notes: List<Note>): String {
            val arr = JSONArray()
            notes.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(s: String): MutableList<Note> {
            val out = mutableListOf<Note>()
            if (s.isBlank()) return out
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
            return out
        }
    }
}

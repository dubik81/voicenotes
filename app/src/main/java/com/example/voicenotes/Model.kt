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
    var refinedText: String? = null,            // уточнённый текст от Whisper (если готов)
    var isRefined: Boolean = false,             // применён ли уточнённый как основной
    val variants: MutableMap<String, String> = mutableMapOf()
) {
    fun variantKey(level: Level, tone: Tone) = "${level.ordinal}:${tone.ordinal}"

    fun getVariant(level: Level, tone: Tone): String? =
        if (level == Level.VERBATIM) original else variants[variantKey(level, tone)]

    fun putVariant(level: Level, tone: Tone, text: String) {
        variants[variantKey(level, tone)] = text
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("original", original)
        put("audioPath", audioPath ?: JSONObject.NULL)
        put("refinedText", refinedText ?: JSONObject.NULL)
        put("isRefined", isRefined)
        val v = JSONObject()
        variants.forEach { (k, value) -> v.put(k, value) }
        put("variants", v)
    }

    companion object {
        fun fromJson(o: JSONObject): Note {
            val variants = mutableMapOf<String, String>()
            o.optJSONObject("variants")?.let { vj ->
                vj.keys().forEach { k -> variants[k] = vj.getString(k) }
            }
            return Note(
                id = o.getLong("id"),
                title = o.getString("title"),
                createdAt = o.getLong("createdAt"),
                original = o.getString("original"),
                audioPath = o.optString("audioPath").takeIf { it.isNotBlank() && it != "null" },
                refinedText = o.optString("refinedText").takeIf { it.isNotBlank() && it != "null" },
                isRefined = o.optBoolean("isRefined", false),
                variants = variants
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

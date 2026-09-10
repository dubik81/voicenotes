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
    var recordMode: String = "",                // "vosk"/"google"/"lecture"
    var isLecture: Boolean = false,             // режим лекции (стенограмма, без тона)
    var refinedText: String? = null,            // не используется (совместимость)
    var isRefined: Boolean = false,             // не используется (совместимость)
    val variants: MutableMap<String, String> = mutableMapOf(),
    // История редакций: ключ "levelOrd:toneOrd" -> список версий текста.
    val history: MutableMap<String, MutableList<String>> = mutableMapOf(),
    // Текущий индекс в истории для каждого варианта.
    val historyIndex: MutableMap<String, Int> = mutableMapOf(),
    // Метка движка для каждой версии истории (параллельно history): «облако», «Qwen», «правила»…
    val historyEngine: MutableMap<String, MutableList<String>> = mutableMapOf()
) {
    fun variantKey(level: Level, tone: Tone) = "${level.ordinal}:${tone.ordinal}"

    fun getVariant(level: Level, tone: Tone): String? =
        if (level == Level.VERBATIM) (variants[variantKey(level, tone)] ?: original)
        else variants[variantKey(level, tone)]

    /**
     * ЗАКРЕПЛЁННЫЕ ВАРИАНТЫ (v134). Ключи «уровень:тон», которые пользователь пометил
     * замком: их не перезаписывает ни фоновый расчёт, ни каскад, ни «Обновить».
     * Причина: удачный вариант получается не всегда, и потерять его нельзя.
     */
    val locked: MutableSet<String> = mutableSetOf()
    fun isLocked(level: Level, tone: Tone) = variantKey(level, tone) in locked
    fun toggleLock(level: Level, tone: Tone): Boolean {
        val key = variantKey(level, tone)
        return if (key in locked) { locked.remove(key); false } else { locked.add(key); true }
    }

    /**
     * @param force записать даже поверх закреплённого (осознанное действие пользователя)
     * @return true если добавлена НОВАЯ версия; false если такой текст уже был в истории
     *         (тогда просто переключаемся на него) или вариант закреплён
     */
    fun putVariant(level: Level, tone: Tone, text: String, engine: String = "", force: Boolean = false): Boolean {
        val key = variantKey(level, tone)
        if (!force && key in locked) return false
        // ДУБЛИКАТЫ (v134). Пользователь: «варианты повторяются». Так и было: «Обновить»
        // выбирает промпт из трёх наугад, и модель нередко выдаёт текст, который в истории
        // уже есть — в архиве версии 2 и 4 совпадали дословно. Такой ответ в историю не
        // добавляем: просто показываем ту версию, что уже была.
        val histExisting = history[key]
        val same = histExisting?.indexOfFirst { it.trim() == text.trim() } ?: -1
        if (same >= 0) {
            variants[key] = histExisting!![same]
            historyIndex[key] = same
            return false
        }
        variants[key] = text
        // добавляем в историю (если это новая версия, не дубль текущей)
        val hist = history.getOrPut(key) { mutableListOf() }
        val eng = historyEngine.getOrPut(key) { mutableListOf() }
        while (eng.size < hist.size) eng.add("")           // выравниваем со старыми данными
        if (hist.isEmpty() || hist.last() != text) {
            // v134: «будущее» больше НЕ обрезаем. Раньше при добавлении версии из
            // середины истории всё, что было дальше, стиралось — и удачный вариант,
            // до которого пользователь долистал назад, пропадал безвозвратно.
            hist.add(text); eng.add(engine)
            historyIndex[key] = hist.size - 1
        } else if (engine.isNotBlank() && eng.isNotEmpty()) {
            eng[eng.size - 1] = engine
        }
        return true
    }

    /** Подпись текущей версии: «2/4 · облако» (для стрелок истории). */
    fun versionLabel(level: Level, tone: Tone): String {
        val key = variantKey(level, tone)
        val hist = history[key] ?: return ""
        if (hist.size < 2) return ""
        val idx = (historyIndex[key] ?: (hist.size - 1)).coerceIn(0, hist.size - 1)
        val eng = historyEngine[key]?.getOrNull(idx).orEmpty()
        return "${idx + 1}/${hist.size}" + if (eng.isNotBlank()) " · $eng" else ""
    }

    /**
     * Чем сделана ТЕКУЩАЯ показанная версия: «облако», «на устройстве (qwen)»,
     * «правила», «правила (облако не ответило)». Пустая строка — метки нет.
     *
     * v130: раньше это было видно только в мелкой подписи у стрелок истории, и то лишь
     * когда версий больше одной. Из-за этого запасной результат правил выглядел как
     * работа ИИ — пользователь оценивал текст, не зная, что ИИ вообще не отвечал.
     */
    fun engineOf(level: Level, tone: Tone): String {
        val key = variantKey(level, tone)
        val eng = historyEngine[key] ?: return ""
        val hist = history[key] ?: return ""
        val idx = (historyIndex[key] ?: (hist.size - 1)).coerceIn(0, maxOf(0, eng.size - 1))
        return eng.getOrNull(idx).orEmpty()
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
        put("isLecture", isLecture)
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
        // Закреплённые варианты должны переживать перезапуск приложения.
        val lk = JSONArray(); locked.forEach { lk.put(it) }
        put("locked", lk)
        val he = JSONObject()
        historyEngine.forEach { (k, list) ->
            val arr = JSONArray(); list.forEach { arr.put(it) }; he.put(k, arr)
        }
        put("historyEngine", he)
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
            val historyEngine = mutableMapOf<String, MutableList<String>>()
            o.optJSONObject("historyEngine")?.let { hej ->
                hej.keys().forEach { k ->
                    val arr = hej.getJSONArray(k)
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) list.add(arr.getString(i))
                    historyEngine[k] = list
                }
            }
            val locked = mutableSetOf<String>()
            o.optJSONArray("locked")?.let { arr ->
                for (i in 0 until arr.length()) locked.add(arr.getString(i))
            }
            return Note(
                id = o.getLong("id"),
                title = o.getString("title"),
                createdAt = o.getLong("createdAt"),
                original = o.getString("original"),
                recordMode = o.optString("recordMode", ""),
                isLecture = o.optBoolean("isLecture", false),
                audioPath = o.optString("audioPath").takeIf { it.isNotBlank() && it != "null" },
                refinedText = o.optString("refinedText").takeIf { it.isNotBlank() && it != "null" },
                isRefined = o.optBoolean("isRefined", false),
                variants = variants,
                history = history,
                historyIndex = historyIndex,
                historyEngine = historyEngine
            ).also { it.locked.addAll(locked) }
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

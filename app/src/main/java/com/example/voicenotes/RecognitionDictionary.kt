package com.example.voicenotes

import android.content.Context
import org.json.JSONObject

/**
 * Пост-обработка распознанного текста по словарям (как «Пользовательские слова»
 * в Live Transcribe + встроенный словарь частых ошибок распознавания).
 * Работает ОФЛАЙН, мгновенно, по правилам — надёжно.
 */
object RecognitionDictionary {

    // Встроенный словарь частых ошибок распознавания русской речи.
    // Ключ — что распознаётся ошибочно, значение — правильное.
    // Собран из реальных ошибок Vosk/Whisper (пополняется).
    private val commonFixes: Map<String, String> = mapOf(
        // технические термины
        "искуственный" to "искусственный",
        "исковой" to "языковой", "исковую" to "языковую",
        "распознования" to "распознавания", "распознование" to "распознавание",
        "квин" to "Qwen", "куин" to "Qwen",
        "вайлдберис" to "Wildberries", "вайлдберриз" to "Wildberries",
        "озон" to "Ozon",
        // созвучные ошибки
        "аршином" to "аршином",
        "робота" to "работа",
        // единицы и числа словами оставляем как есть
    )

    // Пользовательский словарь (добавляется в настройках).
    private var userWords: MutableMap<String, String> = mutableMapOf()

    fun load(context: Context) {
        try {
            val f = context.getSharedPreferences("dict", Context.MODE_PRIVATE)
            val json = f.getString("user_words", "{}") ?: "{}"
            val obj = JSONObject(json)
            userWords = mutableMapOf()
            obj.keys().forEach { k -> userWords[k.lowercase()] = obj.getString(k) }
        } catch (_: Exception) {}
    }

    fun addUserWord(context: Context, wrong: String, correct: String) {
        userWords[wrong.lowercase()] = correct
        try {
            val obj = JSONObject()
            userWords.forEach { (k, v) -> obj.put(k, v) }
            context.getSharedPreferences("dict", Context.MODE_PRIVATE)
                .edit().putString("user_words", obj.toString()).apply()
        } catch (_: Exception) {}
    }

    fun userWordList(): Map<String, String> = userWords.toMap()

    fun removeUserWord(context: Context, wrong: String) {
        userWords.remove(wrong.lowercase())
        try {
            val obj = JSONObject()
            userWords.forEach { (k, v) -> obj.put(k, v) }
            context.getSharedPreferences("dict", Context.MODE_PRIVATE)
                .edit().putString("user_words", obj.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Применяет словари к тексту: заменяет ошибочные слова на правильные. */
    fun apply(text: String): String {
        if (text.isBlank()) return text
        var result = text
        // сначала пользовательский словарь (приоритет), потом встроенный
        for ((wrong, correct) in userWords) {
            result = replaceWord(result, wrong, correct)
        }
        for ((wrong, correct) in commonFixes) {
            result = replaceWord(result, wrong, correct)
        }
        return result
    }

    // Замена слова целиком (не части слова), сохраняя регистр первой буквы.
    private fun replaceWord(text: String, wrong: String, correct: String): String {
        val re = Regex("(?i)\\b${Regex.escape(wrong)}\\b")
        return re.replace(text) { m ->
            // если оригинал с заглавной — заменяем с заглавной
            if (m.value.firstOrNull()?.isUpperCase() == true)
                correct.replaceFirstChar { it.uppercase() }
            else correct
        }
    }
}

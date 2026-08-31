package com.example.voicenotes

import android.content.Context

/** Настройки приложения. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("vn_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v).apply()

    /** Пауза для авто-остановки записи, сек. 0 = непрерывно (до кнопки «Стоп»). */
    var pauseSeconds: Int
        get() = prefs.getInt("pause_seconds", 0)
        set(v) = prefs.edit().putInt("pause_seconds", v).apply()

    var saveAudio: Boolean
        get() = prefs.getBoolean("save_audio", false)
        set(v) = prefs.edit().putBoolean("save_audio", v).apply()

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", false)
        set(v) = prefs.edit().putBoolean("dark_theme", v).apply()

    /** Досчитывать остальные ступени сжатия в фоне после записи. */
    var precomputeAll: Boolean
        get() = prefs.getBoolean("precompute_all", true)
        set(v) = prefs.edit().putBoolean("precompute_all", v).apply()

    /** Движок распознавания: false = Google (точный, без аудио), true = Vosk (офлайн + аудио). */
    var useVosk: Boolean
        get() = prefs.getBoolean("use_vosk", false)
        set(v) = prefs.edit().putBoolean("use_vosk", v).apply()

    /** Размер шрифта в заметках, в поинтах. */
    var fontSize: Int
        get() = prefs.getInt("font_size", 17)
        set(v) = prefs.edit().putInt("font_size", v).apply()

    /** Сортировка списка: true = новые сверху. */
    var newestFirst: Boolean
        get() = prefs.getBoolean("newest_first", true)
        set(v) = prefs.edit().putBoolean("newest_first", v).apply()

    /** Спрашивать подтверждение перед удалением. */
    var confirmDelete: Boolean
        get() = prefs.getBoolean("confirm_delete", true)
        set(v) = prefs.edit().putBoolean("confirm_delete", v).apply()

    /** Язык распознавания (BCP-47), пусто = системный. */
    var recognitionLang: String
        get() = prefs.getString("rec_lang", "") ?: ""
        set(v) = prefs.edit().putString("rec_lang", v).apply()

    /** Уточнять офлайн-запись через Whisper (точнее Vosk). */
    var useWhisper: Boolean
        get() = prefs.getBoolean("use_whisper", true)
        set(v) = prefs.edit().putBoolean("use_whisper", v).apply()

    /** Размер модели Whisper: "tiny"/"base"/"small". */
    var whisperModel: String
        get() = prefs.getString("whisper_model", "base") ?: "base"
        set(v) = prefs.edit().putString("whisper_model", v).apply()

    /** Запускать обработку ИИ автоматически после записи (иначе — по кнопке «Отправить в ИИ»). */
    var autoAi: Boolean
        get() = prefs.getBoolean("auto_ai", true)
        set(v) = prefs.edit().putBoolean("auto_ai", v).apply()

    val useAI get() = apiKey.isNotBlank()
}

/** Хранилище заметок в одном JSON-файле приложения. */
class NoteStore(context: Context) {
    private val file = context.filesDir.resolve("notes.json")

    fun load(): MutableList<Note> = try {
        if (file.exists()) Note.listFromJson(file.readText()) else mutableListOf()
    } catch (_: Exception) { mutableListOf() }

    fun save(notes: List<Note>) {
        try { file.writeText(Note.listToJson(notes)) } catch (_: Exception) {}
    }
}

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

package com.example.voicenotes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model

/** Singleton: держит загруженную модель Vosk (тяжёлый объект, грузим один раз). */
object VoskHolder {
    @Volatile private var model: Model? = null
    @Volatile private var loadedDir: String? = null

    suspend fun getModel(context: Context): Model = withContext(Dispatchers.IO) {
        val dir = VoskModelManager.modelDir(context)
        val cached = model
        // Модель сменили (маленькая ↔ большая) — старую отпускаем и грузим нужную.
        if (cached != null && loadedDir == dir.absolutePath) return@withContext cached
        if (cached != null) { try { cached.close() } catch (_: Throwable) {}; model = null; loadedDir = null }
        val m = Model(dir.absolutePath)
        model = m; loadedDir = dir.absolutePath
        m
    }

    fun isLoaded() = model != null
}

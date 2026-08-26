package com.example.voicenotes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model

/** Singleton: держит загруженную модель Vosk (тяжёлый объект, грузим один раз). */
object VoskHolder {
    @Volatile private var model: Model? = null

    suspend fun getModel(context: Context): Model = withContext(Dispatchers.IO) {
        model ?: run {
            val dir = VoskModelManager.modelDir(context)
            val m = Model(dir.absolutePath)
            model = m
            m
        }
    }

    fun isLoaded() = model != null
}

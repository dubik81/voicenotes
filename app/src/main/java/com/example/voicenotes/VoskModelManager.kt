package com.example.voicenotes

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Загрузка и распаковка русской модели Vosk при первом запуске.
 * Модель маленькая (~45 МБ), качество ниже Google, зато офлайн и позволяет
 * одновременно писать аудио.
 */
object VoskModelManager {

    // Официальная маленькая русская модель Vosk.
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val MODEL_DIR_NAME = "vosk-model-small-ru-0.22"

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isReady(context: Context): Boolean {
        val d = modelDir(context)
        // Признак готовности: есть подпапка am/ (акустическая модель)
        return d.exists() && File(d, "am").exists()
    }

    /**
     * Скачивает и распаковывает модель. progress: 0..100 (по скачиванию).
     * Кидает исключение с понятным текстом при ошибке.
     */
    suspend fun download(context: Context, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (isReady(context)) return@withContext
        val zipFile = File(context.cacheDir, "vosk-model.zip")
        try {
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
            }
            val total = conn.contentLength.toLong().coerceAtLeast(1)
            conn.inputStream.use { input ->
                FileOutputStream(zipFile).use { out ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        progress(((done * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            unzip(zipFile, context.filesDir)
        } finally {
            zipFile.delete()
        }
        if (!isReady(context)) throw RuntimeException("Модель распакована некорректно")
    }

    private fun unzip(zip: File, targetDir: File) {
        ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(8192)
                        var r: Int
                        while (zis.read(buf).also { r = it } != -1) fos.write(buf, 0, r)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}

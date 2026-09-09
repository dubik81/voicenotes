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

    // Маленькая (~50 МБ, быстрая) и большая (~1.8 ГБ, точнее) русские модели Vosk.
    private const val SMALL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    private const val SMALL_DIR = "vosk-model-small-ru-0.22"
    private const val BIG_URL = "https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip"
    private const val BIG_DIR = "vosk-model-ru-0.42"

    // Какую модель использовать (задаётся из настроек).
    @Volatile var useBig: Boolean = false

    private fun url() = if (useBig) BIG_URL else SMALL_URL
    private fun dirName() = if (useBig) BIG_DIR else SMALL_DIR
    private val MODEL_URL get() = url()
    private val MODEL_DIR_NAME get() = dirName()

    fun modelDir(context: Context): File = File(context.filesDir, dirName())

    // ══ ЗАЩИТА ОТ ВЫЛЕТА НА БОЛЬШОЙ МОДЕЛИ (v118) ═════════════════════════════
    // Большая Vosk (1.8 ГБ на диске) при загрузке разворачивается в памяти и на
    // телефоне с занятой памятью Android просто УБИВАЕТ процесс. Это не исключение
    // Java — перехватчик крашей такое поймать не может, в логе просто обрыв.
    //
    // Проверки «сколько свободно памяти» недостаточно: в снимках окружения у одного
    // и того же телефона свободно то 2717 МБ, то 3698 — порог угадать нельзя, и на
    // границе приложение падает через раз.
    //
    // Поэтому МАРКЕР НА ДИСКЕ: перед загрузкой создаём файл, после успешной загрузки
    // удаляем. Если при следующем запуске файл на месте — прошлая попытка не
    // вернулась, то есть процесс убили. Тогда большая модель отключается насовсем
    // (до ручного включения в настройках). Это работает независимо от того, каким
    // образом приложение умерло.
    private fun bigMarker(context: Context) = File(context.filesDir, "vosk_big_loading.marker")

    /** Пометить начало загрузки большой модели (маркер живёт до успешного конца). */
    fun markBigLoadStart(context: Context) {
        try { bigMarker(context).writeText(System.currentTimeMillis().toString()) } catch (_: Throwable) {}
    }

    /** Загрузка прошла — снимаем маркер. */
    fun markBigLoadOk(context: Context) {
        try { bigMarker(context).delete() } catch (_: Throwable) {}
    }

    /** Осталась ли метка незавершённой загрузки (значит, в прошлый раз был вылет). */
    fun bigLoadCrashed(context: Context): Boolean = try { bigMarker(context).exists() } catch (_: Throwable) { false }

    fun clearBigCrashMark(context: Context) = markBigLoadOk(context)

    /**
     * Сколько памяти нужно, чтобы браться за большую модель. Считаем от РЕАЛЬНОГО
     * размера скачанной модели на диске, а не от константы: при загрузке нужен запас
     * примерно вдвое плюс место самому приложению.
     */
    fun bigNeedsMb(context: Context): Long {
        val dir = File(context.filesDir, BIG_DIR)
        val onDisk = try { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024) } catch (_: Throwable) { 1800L }
        return onDisk * 2 + 700
    }

    fun isReady(context: Context): Boolean {
        val d = modelDir(context)
        return d.exists() && File(d, "am").exists()
    }

    /** Готова ли конкретная модель (для показа в настройках). */
    fun isReadySize(context: Context, big: Boolean): Boolean {
        val d = File(context.filesDir, if (big) BIG_DIR else SMALL_DIR)
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

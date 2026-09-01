package com.example.voicenotes

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Чёрный ящик» — диагностический лог для отладки.
 * Пишет события, действия пользователя, ошибки, какой движок отработал.
 * Включается в zip-экспорт заметки, чтобы разработчик видел точную картину.
 *
 * Кольцевой буфер (последние N событий), чтобы не разрастался.
 */
object Diagnostics {
    private const val MAX = 300
    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Категории для удобного чтения.
    fun action(msg: String) = log("ДЕЙСТВИЕ", msg)      // нажатия кнопок, переключения
    fun event(msg: String) = log("СОБЫТИЕ", msg)        // старт/конец распознавания, ИИ
    fun engine(msg: String) = log("ДВИЖОК", msg)        // какой движок отработал
    fun error(msg: String) = log("ОШИБКА", msg)         // ошибки, коды
    fun info(msg: String) = log("ИНФО", msg)            // окружение, версии

    @Synchronized
    private fun log(cat: String, msg: String) {
        val line = "${fmt.format(Date())} [$cat] $msg"
        buffer.addLast(line)
        while (buffer.size > MAX) buffer.removeFirst()
    }

    @Synchronized
    fun dump(): String = buffer.joinToString("\n")

    @Synchronized
    fun clear() = buffer.clear()

    /** Записать снимок окружения (вызывать при старте/экспорте). */
    fun snapshot(context: Context, settings: Settings) {
        info("=== СНИМОК ОКРУЖЕНИЯ ===")
        info("устройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        info("движок речи: ${if (settings.useVosk) "Vosk(офл)" else "Google(онл)"}, Whisper=${settings.useWhisper}(${settings.whisperModel})")
        info("движок смысла: ${if (settings.localAi) "локальный(${settings.localAiModel})" else "облачный"}, автозапуск ИИ=${settings.autoAi}")
        info("ключ OpenRouter: ${if (settings.apiKey.isNotBlank()) "есть" else "нет"}")
        info("Vosk скачан: ${VoskModelManager.isReady(context)}")
        info("Whisper скачан: ${WhisperModelManager.isReady(context, settings.whisperModel)}")
        info("Локальный ИИ скачан: ${LocalAiModelManager.isReady(context, settings.localAiModel)}")
        info("Локальный ИИ статус: ${LocalAiEngine.lastStatus}")
    }
}

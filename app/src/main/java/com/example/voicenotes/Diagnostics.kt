package com.example.voicenotes

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Чёрный ящик» — диагностический лог для отладки.
 * Пишет события, действия пользователя, ошибки, какой движок отработал.
 * Включается в zip-экспорт заметки, чтобы разработчик видел точную картину.
 *
 * v116: лог ПИШЕТСЯ НА ДИСК построчно (diag_live.txt). Раньше он жил только в памяти
 * и при вылете приложения (особенно НАТИВНОМ — Vosk/ExecuTorch, который Java-перехватчик
 * не ловит) пропадал целиком. Теперь при следующем запуске хвост прошлой сессии
 * подшивается в лог с пометкой, завершилась ли она нормально или аварийно.
 */
object Diagnostics {
    private const val MAX = 400
    private const val PREV_TAIL = 60
    private val buffer = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // Файлы на диске (инициализируются в init()).
    @Volatile private var liveFile: File? = null
    @Volatile private var prevSession: String = ""       // хвост прошлой сессии (для экспорта)
    @Volatile private var lastCrashText: String = ""     // содержимое last_crash.txt (если был Java-краш)
    @Volatile var previousRunCrashed: Boolean = false     // прошлая сессия оборвалась без «нормального выхода»
        private set

    private const val END_MARK = "=== НОРМАЛЬНЫЙ ВЫХОД ==="
    private const val START_MARK = "=== СТАРТ ПРИЛОЖЕНИЯ ==="

    // Категории для удобного чтения.
    fun action(msg: String) = log("ДЕЙСТВИЕ", msg)      // нажатия кнопок, переключения
    fun event(msg: String) = log("СОБЫТИЕ", msg)        // старт/конец распознавания, ИИ
    fun engine(msg: String) = log("ДВИЖОК", msg)        // какой движок отработал
    fun error(msg: String) = log("ОШИБКА", msg)         // ошибки, коды
    fun info(msg: String) = log("ИНФО", msg)            // окружение, версии

    /**
     * Инициализация при старте приложения: читает хвост прошлой сессии, определяет,
     * был ли аварийный обрыв, и открывает новый живой файл.
     */
    @Synchronized
    fun init(context: Context) {
        if (liveFile != null) { log("ИНФО", "Activity пересоздана (поворот/восстановление) — лог продолжается"); return }
        try {
            val live = File(context.filesDir, "diag_live.txt")
            val crash = File(context.filesDir, "last_crash.txt")
            if (live.exists()) {
                val lines = live.readLines()
                val normalEnd = lines.lastOrNull()?.contains(END_MARK) == true
                previousRunCrashed = lines.isNotEmpty() && !normalEnd
                val tail = lines.takeLast(PREV_TAIL)
                prevSession = buildString {
                    append("=== ПРОШЛАЯ СЕССИЯ (последние ${tail.size} строк) — ")
                    append(if (previousRunCrashed) "ЗАВЕРШИЛАСЬ АВАРИЙНО (вылет/убийство процесса, «нормального выхода» нет)"
                           else "завершилась нормально")
                    append(" ===\n")
                    append(tail.joinToString("\n"))
                }
            }
            if (crash.exists()) {
                lastCrashText = "=== last_crash.txt (Java-краш, перехвачен) ===\n" + crash.readText().takeLast(6000)
                // Оставляем файл до следующего краша не удаляем: он полезен в экспорте.
            }
            live.writeText("")
            liveFile = live
        } catch (_: Throwable) {}
        log("ИНФО", START_MARK)
        if (previousRunCrashed) log("ОШИБКА", "Прошлый запуск оборвался аварийно — смотри блок «ПРОШЛАЯ СЕССИЯ» в экспорте")
    }

    /** Пометка нормального выхода (вызывать из onDestroy/onStop). */
    fun markNormalExit() = log("ИНФО", END_MARK)

    @Synchronized
    private fun log(cat: String, msg: String) {
        val line = "${fmt.format(Date())} [$cat] $msg"
        buffer.addLast(line)
        while (buffer.size > MAX) buffer.removeFirst()
        // На диск — сразу (строка короткая, это дёшево; зато переживает вылет).
        try { liveFile?.appendText(line + "\n") } catch (_: Throwable) {}
    }

    @Synchronized
    fun dump(): String = buffer.joinToString("\n")

    /** Полный дамп для экспорта: прошлая сессия + Java-краш + текущая сессия. */
    @Synchronized
    fun dumpForExport(): String = buildString {
        if (prevSession.isNotBlank()) { append(prevSession).append("\n\n") }
        if (lastCrashText.isNotBlank()) { append(lastCrashText).append("\n\n") }
        append("=== ТЕКУЩАЯ СЕССИЯ ===\n")
        append(buffer.joinToString("\n"))
    }

    @Synchronized
    fun clear() = buffer.clear()

    /** Записать снимок окружения (вызывать при старте/экспорте). */
    fun snapshot(context: Context, settings: Settings) {
        info("=== СНИМОК ОКРУЖЕНИЯ ===")
        info("версия приложения: ${appVersion(context)}")
        info("устройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        info("память: свободно ${availMemMb(context)} МБ")
        info("движок речи: ${if (settings.useVosk) "Vosk(офл)" else "Google(онл)"}, Vosk большая=${settings.voskBig} (скачана: big=${VoskModelManager.isReadySize(context, true)}, small=${VoskModelManager.isReadySize(context, false)}), Whisper=${settings.useWhisper}(${settings.whisperModel})")
        info("движок смысла: ${if (settings.localAi) "локальный(${settings.localAiModel})" else "облачный"}, автозапуск ИИ=${settings.autoAi}")
        info("ключ OpenRouter: ${if (settings.apiKey.isNotBlank()) "есть" else "нет"}")
        info("Whisper скачан: ${WhisperModelManager.isReady(context, settings.whisperModel)}")
        info("Локальный ИИ скачан: ${LocalAiModelManager.isReady(context, settings.localAiModel)}, токенизатор: ${LocalAiModelManager.tokenizerFile(context, settings.localAiModel).let { if (it.exists()) "${it.name} (${it.length()} б)" else "НЕТ" }}")
        info("Локальный ИИ статус: ${LocalAiEngine.lastStatus}; рабочий вызов: ${LocalAiEngine.workingCall}")
    }

    fun availMemMb(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        mi.availMem / (1024 * 1024)
    } catch (_: Throwable) { -1 }

    fun appVersion(context: Context): String = try {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        "${p.versionName} (${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(p)})"
    } catch (_: Throwable) { "?" }
}

package com.example.voicenotes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    note: Note,
    settings: Settings,
    processor: VariantProcessor,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    var levelIdx by remember { mutableStateOf(0) }
    var toneIdx by remember { mutableStateOf(1) }   // NEUTRAL
    val level = Level.fromIndex(levelIdx)
    val tone = Tone.fromIndex(toneIdx)

    var original by remember(note.id) { mutableStateOf(note.original) }
    var isListening by remember { mutableStateOf(false) }
    var liveText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(if (note.original.isBlank()) "Нажмите «Запись»" else "Готово") }
    var showRename by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // тикер, чтобы UI перечитывал note.variants при обновлениях процессора
    var refreshTick by remember { mutableStateOf(0) }

    val accent = if (level.isRed) Palette.Red else Palette.Green

    // Текст для показа.
    val shown: String = when {
        isListening -> liveText.ifBlank { "…" }
        original.isBlank() -> ""
        level == Level.VERBATIM -> {
            refreshTick
            // Вариант ИИ с умной пунктуацией (из variants) или офлайн-пунктуатор.
            note.variants[note.variantKey(Level.VERBATIM, tone)] ?: Punctuator.punctuate(original)
        }
        else -> { refreshTick; note.getVariant(level, tone) ?: "" }
    }
    val currentReady = level == Level.VERBATIM || note.getVariant(level, tone) != null

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { hasPermission = it }

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    val audioPlayer = remember { AudioPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy(); audioPlayer.stop() } }

    fun persist() {
        // Сохраняем пунктуированный текст (иначе теряется пунктуация).
        note.original = Punctuator.punctuate(original)
        onChanged()
    }

    // После нового текста: сбрасываем варианты и запускаем полный фоновый расчёт.
    fun startProcessingAll() {
        processor.reset(note.id)
        note.variants.clear()
        persist()
        processor.ensureAll(note, level, tone)
    }

    fun onRecognized(text: String) {
        original = if (original.isBlank()) text else "$original $text"
        if (note.title == "Заметка" || note.title == "Лекция") {
            val t = original.take(30).trim()
            if (t.isNotBlank()) note.title = t
        }
        // Во время записи только накапливаем текст. Обработку ИИ НЕ запускаем —
        // иначе на каждый кусок речи шёл бы запрос (трата лимита и мигание текста).
        note.original = Punctuator.punctuate(original)
        persist()
    }

    // Запуск обработки ИИ — вызывается ОДИН раз после остановки записи.
    fun processAfterRecording() {
        if (original.isBlank()) return
        startProcessingAll()
    }

    // При открытии заметки: продолжаем недосчитанное (только если автозапуск ИИ включён).
    LaunchedEffect(note.id) {
        if (note.original.isNotBlank() && settings.autoAi) {
            processor.ensureAll(note, level, tone)
        }
    }

    // Периодически перечитываем статусы, пока идёт активная обработка.
    LaunchedEffect(note.id) {
        while (true) {
            kotlinx.coroutines.delay(250)
            refreshTick++
        }
    }

    var keepListening by remember { mutableStateOf(false) }
    // Лекция: переключатель онлайн(Google)/офлайн(Vosk+Whisper), меняется между кусками.
    var isOnline by remember { mutableStateOf(note.recordMode == "google") }
    // Работа со смыслом: локальный ИИ (офл) или облачный (онл). Состояние экрана
    // (иначе переключатель не перерисовывается сразу при нажатии).
    var localAi by remember { mutableStateOf(settings.localAi) }
    var showMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showLegend by remember { mutableStateOf(false) }

    fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        val ms = settings.pauseSeconds * 1000L
        if (ms > 0) {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, ms)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, ms)
        }
    }

    fun startListening() {
        if (recognizer == null) { status = "Распознавание недоступно"; return }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: android.os.Bundle?) { status = "Говорите…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {
                if (keepListening && (e == SpeechRecognizer.ERROR_NO_MATCH ||
                            e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                    recognizer.startListening(buildIntent())
                } else {
                    isListening = false
                    if (keepListening) status = "Не расслышал, повторите"
                }
            }
            override fun onPartialResults(p: android.os.Bundle?) {
                val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) liveText = t
            }
            override fun onResults(results: android.os.Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                liveText = ""
                if (t.isNotBlank()) onRecognized(t)
                if (keepListening) recognizer.startListening(buildIntent())
                else { isListening = false; if (t.isBlank()) status = "Ничего не распознано" }
            }
            override fun onEvent(e: Int, p: android.os.Bundle?) {}
        })
        liveText = ""
        isListening = true
        keepListening = settings.pauseSeconds == 0
        // Google держит микрофон эксклюзивно — параллельно писать звук нельзя
        // (это ломало распознавание). В Google-режиме аудио не записываем.
        recognizer.startListening(buildIntent())
    }

    fun stopListening() {
        keepListening = false
        recognizer?.stopListening()
        isListening = false
        // Обработка ИИ — только если включён автозапуск (иначе по кнопке ✨).
        if (settings.autoAi) processAfterRecording()
    }

    // ── Vosk ──
    var voskEngine by remember { mutableStateOf<VoskEngine?>(null) }
    var downloadProgress by remember { mutableStateOf(-1) }
    var lastSpeechAt by remember { mutableStateOf(0L) }
    // ── Whisper (точное уточнение) ──
    var whisperRunning by remember { mutableStateOf(false) }
    var whisperDownload by remember { mutableStateOf(-1) }
    var aiRunning by remember { mutableStateOf(false) }
    var whisperText by remember { mutableStateOf<String?>(null) }  // второй текст от Whisper
    var voskRerunning by remember { mutableStateOf(false) }        // идёт перераспознавание Vosk
    // Индикатор в углу: "vosk"/"whisper"/"ai-cloud"/"ai-local"/"" — что сейчас работает.
    var cornerIndicator by remember { mutableStateOf("") }

    LaunchedEffect(isListening, settings.pauseSeconds) {
        val pauseMs = settings.pauseSeconds * 1000L
        if (isListening && settings.useVosk && pauseMs > 0) {
            while (isListening) {
                kotlinx.coroutines.delay(500)
                if (lastSpeechAt > 0 && System.currentTimeMillis() - lastSpeechAt > pauseMs) {
                    voskEngine?.stop(); voskEngine = null
                    isListening = false
                    status = "Пауза — запись остановлена"
                    break
                }
            }
        }
    }

    fun startVosk() {
        scope.launch {
            if (!VoskModelManager.isReady(context)) {
                status = "Скачиваю модель…"; downloadProgress = 0
                try { VoskModelManager.download(context) { p -> downloadProgress = p } }
                catch (e: Exception) { status = "Не удалось скачать модель: ${e.message}"; downloadProgress = -1; return@launch }
                downloadProgress = -1
            }
            status = "Готовлю распознавание…"
            val model = try { VoskHolder.getModel(context) }
            catch (e: Exception) { status = "Ошибка модели: ${e.message}"; return@launch }
            val audioFile = if (settings.saveAudio)
                File(context.filesDir, "audio_${note.id}.wav").also { note.audioPath = it.absolutePath }
            else null
            lastSpeechAt = System.currentTimeMillis()
            val engine = VoskEngine(model, audioFile)
            voskEngine = engine
            isListening = true
            status = "Говорите… (Vosk)"
            engine.start(
                onPartial = { p -> liveText = p; lastSpeechAt = System.currentTimeMillis() },
                onFinal = { t -> if (t.isNotBlank()) { onRecognized(t); lastSpeechAt = System.currentTimeMillis() }; liveText = "" },
                onError = { msg -> status = msg; isListening = false }
            )
        }
    }

    // Ансамбль Vosk+Whisper → ИИ собирает лучший текст → обработка вариантов.
    // «Обновить» — работает по контексту текущей кнопки:
    // Дословно+офлайн → перераспознать аудио (Vosk+Whisper).
    // Смыслы → переосмыслить текущий текст (локальный или облачный ИИ).
    fun updateCurrent() {
        if (level == Level.VERBATIM) {
            val path = note.audioPath ?: return
            if (note.recordMode == "google" || !File(path).exists()) return
            voskRerunning = true; cornerIndicator = "vosk"
            scope.launch {
                try {
                    val model = VoskHolder.getModel(context)
                    val better = RecognitionEnsemble.refine(model, File(path), thorough = true)
                    if (!better.isNullOrBlank()) {
                        original = better; note.original = Punctuator.punctuate(better)
                    }
                    cornerIndicator = "whisper"
                    val wt = WhisperEngine.transcribe(context, path, settings.whisperModel)
                    if (!wt.isNullOrBlank()) whisperText = wt
                    note.variants.clear(); processor.reset(note.id)
                    status = "Дословный текст обновлён"
                } catch (e: Exception) {
                    status = "Ошибка: ${e.message}"
                } finally { voskRerunning = false; cornerIndicator = "" }
            }
        } else {
            if (original.isBlank()) return
            aiRunning = true
            cornerIndicator = if (settings.localAi) "ai-local" else "ai-cloud"
            processor.regenerateOne(note, level, tone) {
                onChanged(); refreshTick++
                aiRunning = false; cornerIndicator = ""; status = "Готово"
            }
        }
    }

    // Запускается автоматически (если autoAi) или кнопкой «Отправить в ИИ».
    // Отправка ансамбля (Vosk + Whisper) в ИИ: сборка лучшего текста + варианты.
    // Whisper-текст уже получен функцией runWhisper. По кнопке ✨ или авто.
    fun sendToAi() {
        if (original.isBlank()) return
        val voskText = original
        aiRunning = true
        cornerIndicator = if (settings.localAi) "ai-local" else "ai-cloud"
        scope.launch {
            try {
                var assembled = voskText
                val wt = whisperText
                if (!wt.isNullOrBlank() && settings.useAI) {
                    status = "Собираю точный текст (ансамбль)…"
                    try {
                        val result = if (settings.localAi) {
                            // Локальный ИИ на устройстве (офлайн). При сбое — откат на облако.
                            LocalAiEngine.generate(context, AiClient.assembleSystemPrompt(),
                                "Вариант 1 (Vosk):\n$voskText\n\nВариант 2 (Whisper):\n$wt",
                                settings.localAiModel)
                                ?: AiClient.assembleFromTwo(voskText, wt, settings.apiKey)
                        } else {
                            AiClient.assembleFromTwo(voskText, wt, settings.apiKey)
                        }
                        assembled = if (result.isNotBlank() && result.length >= voskText.length / 2)
                            result else wt
                    } catch (_: Exception) { assembled = wt }
                } else if (!wt.isNullOrBlank()) {
                    assembled = wt
                }
                if (assembled.isNotBlank()) {
                    original = assembled
                    note.original = Punctuator.punctuate(assembled)
                }
                processAfterRecording()
                status = "Готово"
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally { aiRunning = false; cornerIndicator = "" }
        }
    }

    // Whisper по записанному аудио — запускается САМ после записи (не зависит от ИИ).
    // Даёт второй текст. Если автозапуск ИИ включён — сразу шлём ансамбль в ИИ.
    fun runWhisper(thenAi: Boolean) {
        val path = note.audioPath
        if (!settings.useWhisper || path == null || !File(path).exists()) {
            // Whisper недоступен — если нужен авто-ИИ, шлём только Vosk-текст
            if (thenAi) sendToAi()
            return
        }
        whisperRunning = true
        scope.launch {
            try {
                val modelId = settings.whisperModel
                if (!WhisperModelManager.isReady(context, modelId)) {
                    status = "Скачиваю модель Whisper…"
                    whisperDownload = 0
                    WhisperModelManager.download(context, modelId) { p -> whisperDownload = p }
                    whisperDownload = -1
                }
                status = "Уточняю через Whisper…"
                val wt = WhisperEngine.transcribe(context, path, modelId)
                if (!wt.isNullOrBlank()) {
                    whisperText = wt
                    status = "Whisper готов"
                } else status = "Whisper не распознал"
            } catch (e: Exception) {
                status = "Whisper: ${e.message}"
                whisperDownload = -1
            } finally {
                whisperRunning = false
                if (thenAi) sendToAi()  // авто-режим: сразу в ИИ
            }
        }
    }

    fun stopVosk() {
        voskEngine?.stop(); voskEngine = null
        isListening = false; liveText = ""; persist()
        // Whisper запускается САМ после записи. Если автозапуск ИИ — следом идёт ансамбль→ИИ.
        runWhisper(thenAi = settings.autoAi)
    }

    fun startRecording() {
        val useOffline = !isOnline
        if (useOffline) startVosk() else startListening()
    }
    fun stopRecording() {
        val useOffline = !isOnline
        if (useOffline) stopVosk() else stopListening()
    }

    fun doExportZip() {
        try {
            val file = NoteExporter.exportFull(context, note)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Экспорт заметки"))
        } catch (e: Exception) { status = "Ошибка экспорта: ${e.message}" }
    }
    fun doExportWord() {
        try {
            val file = DocxExporter.export(context, note)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Экспорт в Word"))
        } catch (e: Exception) { status = "Ошибка Word: ${e.message}" }
    }
    fun doUpdateText() {
        val path = note.audioPath ?: return
        if (!File(path).exists()) return
        status = "Перераспознаю аудио (Vosk)…"
        scope.launch {
            try {
                // 1) Vosk заново (дотошный ансамбль с усилением)
                val model = VoskHolder.getModel(context)
                val better = RecognitionEnsemble.refine(model, File(path), thorough = true)
                if (!better.isNullOrBlank()) {
                    original = better
                    note.original = Punctuator.punctuate(better)
                }
                // 2) Whisper заново (и если авто-ИИ — следом ансамбль→ИИ)
                whisperText = null
                runWhisper(thenAi = settings.autoAi)
                status = "Текст обновлён из аудио"
            } catch (e: Exception) { status = "Ошибка: ${e.message}" }
        }
    }

    if (showRename) {
        RenameDialog(note.title, onSave = {
            note.title = it.ifBlank { "Заметка" }; onChanged(); showRename = false
        }, onDismiss = { showRename = false })
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Удалить заметку?") },
            text = { Text("Заметка и её аудиозапись будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Удалить", color = Palette.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") }
            }
        )
    }

    if (showLegend) {
        AlertDialog(
            onDismissRequest = { showLegend = false },
            title = { Text("Что означают значки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Готовность вариантов:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("✓ готово — вариант посчитан", color = Palette.Green, fontSize = 12.sp)
                    Text("⏳ считается — идёт обработка", color = Palette.Amber, fontSize = 12.sp)
                    Text("• в очереди — ждёт обработки", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    Text("⚠ ошибка — не удалось посчитать", color = Palette.Red, fontSize = 12.sp)
                    HorizontalDivider()
                    Text("Режим заметки:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("🎤 офлайн — распознавание на телефоне (с аудио)", fontSize = 12.sp)
                    Text("🌐 онлайн — распознавание через Google (без аудио)", fontSize = 12.sp)
                    Text("🎓 лекция — режим стенограммы", fontSize = 12.sp)
                    HorizontalDivider()
                    Text("Кнопки в поле:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("🎤 запись / ⏹ стоп", fontSize = 12.sp)
                    Text("✦ ИИ — отправить текст в обработку", color = Palette.Amber, fontSize = 12.sp)
                    Text("‹ ↻ › — другой вариант и история версий", fontSize = 12.sp)
                    HorizontalDivider()
                    Text("Переключатели:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("Речь — движок распознавания (Офл/Онл)", fontSize = 12.sp)
                    Text("Смысл — движок обработки текста (Офл/Онл)", fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showLegend = false }) { Text("Закрыть") } }
        )
    }

    if (showInfo) {
        val dateFmt = remember { java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", java.util.Locale.getDefault()) }
        val words = note.original.split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val audioInfo = note.audioPath?.let { p ->
            val f = File(p)
            if (f.exists()) "${(f.length() / 1024 / 16).coerceAtLeast(1)} сек (прибл.)" else "нет"
        } ?: "нет"
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("Информация") },
            text = {
                Column {
                    Text("Тип: ${if (note.isLecture) "Лекция" else "Заметка"}")
                    Text("Режим: ${if (note.recordMode == "google") "онлайн" else "офлайн"}")
                    Text("Создана: ${dateFmt.format(java.util.Date(note.createdAt))}")
                    Text("Слов: $words")
                    Text("Символов: ${note.original.length}")
                    Text("Аудио: $audioInfo")
                }
            },
            confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Закрыть") } }
        )
    }

    val processing = !isListening && original.isNotBlank() && !currentReady

    Scaffold(containerColor = cs.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

            Row(
                Modifier.fillMaxWidth().background(Palette.Ink)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { persist(); onBack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                }
                // Заголовок — доминанта, клик = переименовать
                Text(
                    note.title.take(18),
                    color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showRename = true }.padding(vertical = 4.dp)
                )
                Spacer(Modifier.width(8.dp))
                val modeLabel = when {
                    note.isLecture -> "лекция"
                    note.recordMode == "google" -> "онлайн"
                    else -> "офлайн"
                }
                Text(modeLabel, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                // Меню «три точки»
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, "Меню", tint = Color.White)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Экспорт (текст + аудио)") },
                            leadingIcon = { Icon(Icons.Filled.Archive, null) },
                            onClick = { showMenu = false; doExportZip() })
                        if (note.isLecture) {
                            DropdownMenuItem(
                                text = { Text("Экспорт Word (.docx)") },
                                leadingIcon = { Icon(Icons.Filled.Description, null) },
                                onClick = { showMenu = false; doExportWord() })
                        }
                        DropdownMenuItem(
                            text = { Text("Информация") },
                            leadingIcon = { Icon(Icons.Filled.Info, null) },
                            onClick = { showMenu = false; showInfo = true })
                        DropdownMenuItem(
                            text = { Text("Что означают значки") },
                            leadingIcon = { Icon(Icons.Filled.Help, null) },
                            onClick = { showMenu = false; showLegend = true })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Удалить", color = Palette.Red) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Palette.Red) },
                            onClick = { showMenu = false
                                if (settings.confirmDelete) showDeleteConfirm = true else onDelete() })
                    }
                }
            }

            // Одна строка: «Речь» (движок распознавания) и «Смысл» (движок ИИ).
            if (!isListening) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Речь в текст: Офлайн (Vosk+Whisper) / Онлайн (Google)
                    Text("Речь", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    SegOffOn(
                        offSelected = !isOnline,
                        onOff = { isOnline = false; note.recordMode = if (note.isLecture) "lecture" else "vosk" },
                        onOn = { isOnline = true; note.recordMode = "google" }
                    )
                    Spacer(Modifier.width(4.dp))
                    // Работа со смыслом: Офлайн (локальный ИИ) / Онлайн (облачный)
                    Text("Смысл", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    SegOffOn(
                        offSelected = localAi,
                        onOff = { localAi = true; settings.localAi = true },
                        onOn = { localAi = false; settings.localAi = false }
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                    Surface(color = cs.surface, shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxSize()) {
                    when {
                        isListening -> Text(
                            liveText.ifBlank { "Слушаю…" },
                            color = cs.onSurface, fontSize = settings.fontSize.sp,
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                        )
                        processing -> {
                            val d = processor.doneCount(note.id)
                            val tot = processor.totalCount(note.id)
                            refreshTick
                            ShimmerText(
                                label = when {
                                    settings.useAI && tot > 0 -> "ИИ обрабатывает варианты: $d из $tot…"
                                    settings.useAI -> "ИИ обрабатывает…"
                                    else -> "Обрабатываю…"
                                },
                                accent = accent
                            )
                        }
                        shown.isBlank() -> Text("Текст появится здесь.", color = cs.onSurfaceVariant,
                            fontSize = 15.sp, modifier = Modifier.padding(20.dp))
                        else -> SelectionContainer {
                            Text(shown, color = cs.onSurface, fontSize = settings.fontSize.sp,
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                    .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 80.dp))
                        }
                    }
                    }
                    // Индикатор в правом верхнем углу: что сейчас работает.
                    val indText = when {
                        cornerIndicator == "vosk" -> "✦ Vosk"
                        cornerIndicator == "whisper" || whisperRunning ->
                            if (whisperDownload in 0..99) "⬇ ${whisperDownload}%" else "✦ Whisper"
                        cornerIndicator == "ai-cloud" -> "✦ ИИ (облако)"
                        cornerIndicator == "ai-local" -> "✦ ИИ (на устройстве)"
                        else -> ""
                    }
                    if (indText.isNotBlank()) {
                        val blink = rememberInfiniteTransition(label = "ind")
                        val a by blink.animateFloat(
                            initialValue = 0.3f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "a")
                        Row(
                            Modifier.align(Alignment.TopEnd).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(indText, color = Palette.Amber.copy(alpha = a),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    // Готовы ли смыслы (хоть один вариант CLEAN/BRIEF/GIST)?
                    val smyslyReady = Level.entries.any { l ->
                        l != Level.VERBATIM && note.getVariant(l, tone) != null
                    }
                    // Показывать ли «Обновить» в текущем контексте.
                    val showUpdate = when {
                        level == Level.VERBATIM -> note.recordMode != "google" &&
                                note.audioPath != null && File(note.audioPath!!).exists()
                        else -> smyslyReady && settings.useAI
                    }
                    val showAiBtn = !showUpdate && original.isNotBlank() && settings.useAI &&
                            !settings.autoAi && !smyslyReady

                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Стрелки истории версий (в смыслах, если есть история)
                        if (level != Level.VERBATIM && shown.isNotBlank() && settings.useAI &&
                            (note.canGoBack(level, tone) || note.canGoForward(level, tone))) {
                            Surface(color = Palette.Ink, shape = RoundedCornerShape(16.dp),
                                shadowElevation = 6.dp, modifier = Modifier.height(56.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(width = 40.dp, height = 56.dp)
                                        .clickable(enabled = note.canGoBack(level, tone)) {
                                            note.goBack(level, tone); onChanged(); refreshTick++ },
                                        contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.ChevronLeft, "Назад",
                                            tint = if (note.canGoBack(level, tone)) Color.White else Color.White.copy(alpha = 0.3f))
                                    }
                                    Box(Modifier.size(width = 40.dp, height = 56.dp)
                                        .clickable(enabled = note.canGoForward(level, tone)) {
                                            note.goForward(level, tone); onChanged(); refreshTick++ },
                                        contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.ChevronRight, "Вперёд",
                                            tint = if (note.canGoForward(level, tone)) Color.White else Color.White.copy(alpha = 0.3f))
                                    }
                                }
                            }
                        }
                        // «Обновить» — переосмыслить/перераспознать текущее (переливается при работе)
                        if (showUpdate) {
                            val busy = aiRunning || voskRerunning
                            FloatingActionButton(
                                onClick = { if (!busy) updateCurrent() },
                                containerColor = if (busy) Palette.Amber else Palette.Ink,
                                contentColor = Color.White
                            ) { Icon(Icons.Filled.Autorenew, "Обновить") }
                        }
                        // «ИИ» — запустить первичную обработку (только если смыслов ещё нет)
                        if (showAiBtn) {
                            FloatingActionButton(
                                onClick = { if (!aiRunning) sendToAi() },
                                containerColor = Palette.Amber,
                                contentColor = Color.White
                            ) {
                                Icon(Icons.Filled.AutoAwesome,
                                    if (aiRunning) "Обработка…" else "Отправить в ИИ")
                            }
                        }
                        // Запись
                        FloatingActionButton(
                            onClick = {
                                if (!hasPermission) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                else if (isListening) stopRecording()
                                else startRecording()
                            },
                            containerColor = if (isListening) Palette.Red else Palette.Ink,
                            contentColor = Color.White
                        ) {
                            Icon(
                                if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                                if (isListening) "Остановить" else "Запись"
                            )
                        }
                    }
            }

            Surface(color = cs.surface, shadowElevation = 12.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {

                    // refreshTick заставляет бейджи готовности обновляться по мере расчёта
                    val tick = refreshTick
                    LevelStepper(
                        selected = levelIdx,
                        accent = accent,
                        readyState = { i -> tick; variantStateFor(note, processor, Level.fromIndex(i), tone) }
                    ) { levelIdx = it }
                    Text(TextCondenser.zoneHint(level), color = accent, fontSize = 12.sp)

                    Spacer(Modifier.height(8.dp))

                    // Тон: скрыт в режиме лекции, неактивен при «Дословно».
                    if (!note.isLecture) {
                        ToneStepper(
                            selected = toneIdx,
                            enabled = level != Level.VERBATIM,
                            readyState = { i -> tick; variantStateFor(note, processor, level, Tone.fromIndex(i)) }
                        ) { toneIdx = it }
                        Spacer(Modifier.height(6.dp))
                    }

                    // Честный статус: реальный ход обработки из процессора.
                    val done = processor.doneCount(note.id)
                    val total = processor.totalCount(note.id)
                    val active = processor.isActive(note.id)
                    val liveStatus = when {
                        isListening -> status
                        downloadProgress in 0..100 -> status
                        original.isBlank() -> "Нажмите «Запись»"
                        active && total > 0 -> "Обрабатываю варианты: $done из $total"
                        total > 0 && done >= total -> "Все варианты готовы"
                        total > 0 && done < total && processor.lastAiError != null ->
                            "ИИ: ${processor.lastAiError}"
                        total > 0 && done < total -> "Готово: $done из $total"
                        else -> status
                    }
                    refreshTick // подписка на обновления
                    Text(liveStatus, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                    if (downloadProgress in 0..100) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { downloadProgress / 100f },
                            modifier = Modifier.fillMaxWidth(), color = Palette.Amber)
                        Text("Загрузка модели: $downloadProgress%", color = cs.onSurfaceVariant, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(10.dp))

                    // Компактный ряд действий: Прослушать (если есть аудио), Копировать, Поделиться.
                    val audioPath = note.audioPath
                    val hasAudio = note.recordMode != "google" &&
                            audioPath != null && File(audioPath).exists()
                    Surface(color = cs.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically) {
                            if (hasAudio && audioPath != null) {
                                IconButton(onClick = {
                                    if (isPlaying) { audioPlayer.stop(); isPlaying = false }
                                    else { val ok = audioPlayer.play(audioPath) { isPlaying = false }
                                        isPlaying = ok; if (!ok) status = "Не удалось воспроизвести" }
                                }) {
                                    Icon(if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                        "Прослушать", tint = cs.onSurface)
                                }
                            }
                            IconButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("Заметка", shown))
                                status = "Скопировано"
                            }, enabled = shown.isNotBlank()) {
                                Icon(Icons.Filled.ContentCopy, "Копировать", tint = cs.onSurface)
                            }
                            IconButton(onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shown)
                                }, "Поделиться"))
                            }, enabled = shown.isNotBlank()) {
                                Icon(Icons.Filled.Share, "Поделиться", tint = cs.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Состояние варианта для индикатора на кнопке. */
private fun variantStateFor(
    note: Note, processor: VariantProcessor, l: Level, t: Tone
): VariantProcessor.State {
    if (l == Level.VERBATIM) return VariantProcessor.State.DONE
    if (note.getVariant(l, t) != null) return VariantProcessor.State.DONE
    return processor.stateOf(note.id, l, t) ?: VariantProcessor.State.QUEUED
}

@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Название заметки") },
        text = {
            OutlinedTextField(value = v, onValueChange = { v = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(v) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun SegOffOn(offSelected: Boolean, onOff: () -> Unit, onOn: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(18.dp), color = cs.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
        Row {
            Surface(
                color = if (offSelected) Palette.Ink else Color.Transparent,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onOff)
            ) {
                Text("Офл", fontSize = 12.sp,
                    color = if (offSelected) Color.White else cs.onSurfaceVariant,
                    fontWeight = if (offSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
            }
            Surface(
                color = if (!offSelected) Palette.Ink else Color.Transparent,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onOn)
            ) {
                Text("Онл", fontSize = 12.sp,
                    color = if (!offSelected) Color.White else cs.onSurfaceVariant,
                    fontWeight = if (!offSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

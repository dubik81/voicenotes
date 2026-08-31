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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
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
        level == Level.VERBATIM -> Punctuator.punctuate(original)
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

    // При открытии заметки: продолжаем недосчитанное (готовое НЕ трогаем).
    LaunchedEffect(note.id) {
        if (note.original.isNotBlank()) {
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
    var showMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

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
        // Запускаем обработку ИИ один раз после остановки.
        processAfterRecording()
    }

    // ── Vosk ──
    var voskEngine by remember { mutableStateOf<VoskEngine?>(null) }
    var downloadProgress by remember { mutableStateOf(-1) }
    var lastSpeechAt by remember { mutableStateOf(0L) }
    // ── Whisper (точное уточнение) ──
    var whisperRunning by remember { mutableStateOf(false) }
    var whisperDownload by remember { mutableStateOf(-1) }
    var aiRunning by remember { mutableStateOf(false) }

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
    // Запускается автоматически (если autoAi) или кнопкой «Отправить в ИИ».
    fun sendToAi() {
        if (original.isBlank()) return
        val path = note.audioPath
        aiRunning = true
        scope.launch {
            try {
                val voskText = original
                var assembled = voskText
                // 1) Whisper по аудио (если включён и есть файл) → второй вариант
                if (settings.useWhisper && path != null && File(path).exists()) {
                    whisperRunning = true
                    val modelId = settings.whisperModel
                    if (!WhisperModelManager.isReady(context, modelId)) {
                        status = "Скачиваю модель Whisper…"
                        whisperDownload = 0
                        WhisperModelManager.download(context, modelId) { p -> whisperDownload = p }
                        whisperDownload = -1
                    }
                    status = "Распознаю через Whisper…"
                    val whisperText = WhisperEngine.transcribe(context, path, modelId)
                    whisperRunning = false
                    // 2) Ансамбль: два текста → ИИ собирает лучший
                    if (!whisperText.isNullOrBlank() && settings.useAI) {
                        status = "Собираю точный текст (ансамбль)…"
                        try {
                            assembled = AiClient.assembleFromTwo(voskText, whisperText, settings.apiKey)
                        } catch (_: Exception) {
                            assembled = whisperText  // если сборка не удалась — берём Whisper
                        }
                    } else if (!whisperText.isNullOrBlank()) {
                        assembled = whisperText
                    }
                }
                // применяем собранный текст
                if (assembled.isNotBlank()) {
                    original = assembled
                    note.original = Punctuator.punctuate(assembled)
                }
                // 3) обработка вариантов (ступени/тон или стенограмма)
                processAfterRecording()
                status = "Готово"
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                whisperRunning = false
                aiRunning = false
            }
        }
    }

    fun stopVosk() {
        voskEngine?.stop(); voskEngine = null
        isListening = false; liveText = ""; persist()
        // Vosk-черновик сохранён. Дальше — ансамбль+ИИ: авто или по кнопке.
        if (settings.autoAi) sendToAi()
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
        status = "Перераспознаю аудио…"
        scope.launch {
            try {
                val model = VoskHolder.getModel(context)
                val better = RecognitionEnsemble.refine(model, File(path), thorough = true)
                if (!better.isNullOrBlank()) {
                    original = better; note.original = Punctuator.punctuate(better)
                    startProcessingAll(); status = "Текст обновлён из аудио"
                } else status = "Не удалось улучшить"
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
                    .padding(horizontal = 8.dp, vertical = 12.dp),
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
                        if (note.recordMode != "google" && note.audioPath != null) {
                            DropdownMenuItem(
                                text = { Text("Обновить текст из аудио") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { showMenu = false; doUpdateText() })
                        }
                        DropdownMenuItem(
                            text = { Text("Информация") },
                            leadingIcon = { Icon(Icons.Filled.Info, null) },
                            onClick = { showMenu = false; showInfo = true })
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Удалить", color = Palette.Red) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Palette.Red) },
                            onClick = { showMenu = false
                                if (settings.confirmDelete) showDeleteConfirm = true else onDelete() })
                    }
                }
            }

            // Верхняя панель: переключатель Онлайн/Офлайн (для всех заметок, меняется между кусками).
            if (!isListening) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Режим", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    // сегмент офлайн/онлайн
                    Surface(shape = RoundedCornerShape(18.dp), color = cs.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant)) {
                        Row {
                            val offSel = !isOnline
                            Surface(
                                color = if (offSel) Palette.Ink else Color.Transparent,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                                    .clickable { isOnline = false; note.recordMode = if (note.isLecture) "lecture" else "vosk" }
                            ) {
                                Text("Офлайн", fontSize = 13.sp,
                                    color = if (offSel) Color.White else cs.onSurfaceVariant,
                                    fontWeight = if (offSel) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                            }
                            Surface(
                                color = if (isOnline) Palette.Ink else Color.Transparent,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.clip(RoundedCornerShape(18.dp))
                                    .clickable { isOnline = true; note.recordMode = "google" }
                            ) {
                                Text("Онлайн", fontSize = 13.sp,
                                    color = if (isOnline) Color.White else cs.onSurfaceVariant,
                                    fontWeight = if (isOnline) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                            }
                        }
                    }
                }
            }

            // «Другой вариант» со стрелками истории — в верхней зоне (не для Дословно).
            if (level != Level.VERBATIM && shown.isNotBlank() && settings.useAI && !isListening) {
                var regenerating by remember(note.id, levelIdx, toneIdx) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { note.goBack(level, tone); onChanged(); refreshTick++ },
                        enabled = note.canGoBack(level, tone) && !regenerating,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(width = 44.dp, height = 40.dp)
                    ) { Text("‹", fontSize = 18.sp) }
                    OutlinedButton(
                        onClick = {
                            regenerating = true
                            processor.regenerateOne(note, level, tone) {
                                onChanged(); refreshTick++; regenerating = false
                            }
                        },
                        enabled = !regenerating,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.weight(1f).height(40.dp)
                    ) { Text(if (regenerating) "Генерирую…" else "↻ Другой вариант",
                        fontSize = 13.sp, maxLines = 1) }
                    OutlinedButton(
                        onClick = { note.goForward(level, tone); onChanged(); refreshTick++ },
                        enabled = note.canGoForward(level, tone) && !regenerating,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(width = 44.dp, height = 40.dp)
                    ) { Text("›", fontSize = 18.sp) }
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
                    // Мигающая пиктограмма Whisper — текст сейчас улучшится.
                    if (whisperRunning) {
                        val blink = rememberInfiniteTransition(label = "wh")
                        val a by blink.animateFloat(
                            initialValue = 0.3f, targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "a")
                        Row(
                            Modifier.align(Alignment.TopEnd).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (whisperDownload in 0..99) "⬇ ${whisperDownload}%" else "✦ Whisper",
                                color = Palette.Amber.copy(alpha = a),
                                fontSize = 12.sp, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // Кнопки в правом нижнем углу поля: [✨ ИИ] [🎤 запись]
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // «Отправить в ИИ» — акцентная, главная фишка (искра)
                        if (original.isNotBlank() && settings.useAI) {
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
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {

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
                    // Легенда индикации готовности вариантов.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("✓ готово", color = Palette.Green, fontSize = 9.sp)
                        Text("⏳ считается", color = Palette.Amber, fontSize = 9.sp)
                        Text("• в очереди", color = cs.onSurfaceVariant, fontSize = 9.sp)
                        Text("⚠ ошибка", color = Palette.Red, fontSize = 9.sp)
                    }
                    Spacer(Modifier.height(6.dp))
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

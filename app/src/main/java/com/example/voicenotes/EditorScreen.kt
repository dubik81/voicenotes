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
import androidx.compose.foundation.background
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
        note.original = original
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
        if (note.title == "Заметка") {
            note.title = original.take(30).trim().ifBlank { "Заметка" }
        }
        // Сохраняем СРАЗУ пунктуированный текст (чистый и на экране, и в экспорте).
        note.original = Punctuator.punctuate(original)
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
        recognizer.startListening(buildIntent())
    }

    fun stopListening() {
        keepListening = false
        recognizer?.stopListening()
        isListening = false
    }

    // ── Vosk ──
    var voskEngine by remember { mutableStateOf<VoskEngine?>(null) }
    var downloadProgress by remember { mutableStateOf(-1) }
    var lastSpeechAt by remember { mutableStateOf(0L) }
    // Двухступенчатое распознавание: статус уточнения через Whisper.
    var refining by remember { mutableStateOf(false) }       // идёт облачное уточнение
    var refinedReady by remember { mutableStateOf(note.refinedText != null && !note.isRefined) }

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

    // Облачное уточнение через Whisper: шлём записанное аудио, точный текст в refinedText.
    fun refineWithWhisper() {
        val path = note.audioPath ?: return
        if (!settings.useAI) return  // нужен ключ OpenRouter
        val file = File(path)
        if (!file.exists() || file.length() < 44) return
        refining = true
        scope.launch {
            try {
                val precise = Transcriber.transcribe(file, settings.apiKey, "ru")
                if (precise.isNotBlank()) {
                    note.refinedText = Punctuator.punctuate(precise)
                    refinedReady = true
                    persist()
                }
            } catch (e: Exception) {
                status = "Уточнение не удалось: ${e.message}"
            } finally {
                refining = false
            }
        }
    }

    fun stopVosk() {
        voskEngine?.stop(); voskEngine = null
        isListening = false; liveText = ""; persist()
        // автоматически шлём на облачное уточнение
        refineWithWhisper()
    }

    // Применить уточнённый текст как основной и пересчитать варианты.
    fun applyRefined() {
        val refined = note.refinedText ?: return
        original = refined
        note.original = refined
        note.isRefined = true
        refinedReady = false
        startProcessingAll()   // пересчёт всех вариантов с точного текста
    }

    fun startRecording() { if (settings.useVosk) startVosk() else startListening() }
    fun stopRecording() { if (settings.useVosk) stopVosk() else stopListening() }

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

    val processing = !isListening && original.isNotBlank() && !currentReady

    Scaffold(containerColor = cs.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

            Row(
                Modifier.fillMaxWidth().background(Palette.Ink)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { persist(); onBack() }) { Text("‹ Назад", color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showRename = true }) {
                    Text(note.title.take(20), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (settings.confirmDelete) showDeleteConfirm = true else onDelete()
                }) { Text("Удалить", color = Palette.Red) }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                Column(Modifier.fillMaxSize()) {
                    // Индикатор двухступенчатого распознавания (только в Vosk-режиме).
                    if (settings.useVosk && original.isNotBlank()) {
                        val label = when {
                            refining -> "⏳ Уточняю распознавание через ИИ…"
                            refinedReady -> "✓ Готов уточнённый текст — применить?"
                            note.isRefined -> "✓ Текст уточнён"
                            else -> "черновой текст (Vosk)"
                        }
                        val color = when {
                            refining -> Palette.Amber
                            refinedReady -> Palette.Green
                            note.isRefined -> Palette.Green
                            else -> cs.onSurfaceVariant
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            if (refinedReady) {
                                Button(
                                    onClick = { applyRefined() },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Green),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) { Text("Применить", fontSize = 12.sp, color = Color.White) }
                            }
                        }
                    }
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
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp))
                        }
                    }
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

                    // Тон неактивен при «Дословно».
                    ToneStepper(
                        selected = toneIdx,
                        enabled = level != Level.VERBATIM,
                        readyState = { i -> tick; variantStateFor(note, processor, level, Tone.fromIndex(i)) }
                    ) { toneIdx = it }

                    Spacer(Modifier.height(6.dp))
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

                    Button(
                        onClick = {
                            if (!hasPermission) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else if (isListening) stopRecording()
                            else startRecording()
                        },
                        enabled = downloadProgress < 0,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isListening) Palette.Red else Palette.Ink),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(if (isListening) "Остановить" else "Запись",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }

                    Spacer(Modifier.height(8.dp))

                    val audioPath = note.audioPath
                    if (audioPath != null && File(audioPath).exists()) {
                        OutlinedButton(
                            onClick = {
                                if (isPlaying) { audioPlayer.stop(); isPlaying = false }
                                else { val ok = audioPlayer.play(audioPath) { isPlaying = false }
                                    isPlaying = ok; if (!ok) status = "Не удалось воспроизвести" }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isPlaying) "⏹ Остановить воспроизведение" else "▶ Прослушать запись",
                                fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // «Другой вариант» — переделать только текущий текст (не для Дословно).
                    if (level != Level.VERBATIM && shown.isNotBlank() && settings.useAI) {
                        OutlinedButton(
                            onClick = {
                                status = "Готовлю другой вариант…"
                                processor.regenerateOne(note, level, tone) {
                                    onChanged(); status = "Готово"
                                }
                            },
                            enabled = !isListening,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("↻ Другой вариант", fontSize = 13.sp) }
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Заметка", shown))
                            status = "Скопировано"
                        }, enabled = shown.isNotBlank(), shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)) { Text("Копировать", fontSize = 13.sp) }

                        OutlinedButton(onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shown)
                            }
                            context.startActivity(Intent.createChooser(share, "Поделиться"))
                        }, enabled = shown.isNotBlank(), shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)) { Text("Поделиться", fontSize = 13.sp) }
                    }

                    Spacer(Modifier.height(8.dp))
                    // Экспорт всей заметки в JSON-файл (для разбора).
                    OutlinedButton(
                        onClick = {
                            try {
                                val file = NoteExporter.exportFull(context, note)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", file)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Экспорт заметки"))
                            } catch (e: Exception) { status = "Ошибка экспорта: ${e.message}" }
                        },
                        enabled = original.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("⤓ Экспорт (текст + аудио, zip)", fontSize = 13.sp) }
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

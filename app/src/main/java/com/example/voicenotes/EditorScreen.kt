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
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    note: Note,
    settings: Settings,
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

    var isListening by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var liveText by remember { mutableStateOf("") }        // живой текст во время записи
    var status by remember { mutableStateOf(if (note.original.isBlank()) "Нажмите «Запись»" else "Готово") }
    var showRename by remember { mutableStateOf(false) }

    val accent = if (level.isRed) Palette.Red else Palette.Green

    // Текст, который показываем: во время записи — живой; иначе — вариант по ступени.
    val shown = when {
        isListening -> liveText.ifBlank { "…" }
        else -> note.getVariant(level, tone) ?: ""
    }
    val needsCompute = !isListening && note.original.isNotBlank() &&
            note.getVariant(level, tone) == null

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
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    // Вычислить один вариант (ИИ или правила).
    suspend fun computeVariant(l: Level, t: Tone): String {
        return if (settings.useAI && l != Level.VERBATIM) {
            try { AiClient.process(note.original, l, t, settings.apiKey) }
            catch (e: Exception) { status = e.message ?: "Ошибка ИИ"; TextCondenser.condense(note.original, l) }
        } else TextCondenser.condense(note.original, l)
    }

    // Досчитать текущую ступень, затем в фоне остальные.
    fun recomputeFrom(startLevel: Level, t: Tone) {
        if (note.original.isBlank()) return
        scope.launch {
            isProcessing = true
            status = "Обработка…"
            // текущая ступень первой
            note.putVariant(startLevel, t, computeVariant(startLevel, t))
            onChanged()
            isProcessing = false
            status = "Готово"
            // остальные ступени в фоне
            if (settings.precomputeAll) {
                for (l in Level.entries) {
                    if (l == startLevel) continue
                    if (note.getVariant(l, t) == null) {
                        note.putVariant(l, t, computeVariant(l, t))
                        onChanged()
                    }
                }
            }
        }
    }

    fun onRecognized(text: String) {
        note.original = if (note.original.isBlank()) text
                        else "${note.original} $text"
        // авто-имя из первых слов, если ещё дефолтное
        if (note.title == "Заметка") {
            note.title = note.original.take(30).trim().ifBlank { "Заметка" }
        }
        note.variants.clear()  // текст изменился — старые варианты недействительны
        onChanged()
        recomputeFrom(level, tone)
    }

    fun startListening() {
        if (recognizer == null) { status = "Распознавание недоступно"; return }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: android.os.Bundle?) { status = "Говорите…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { isListening = false; status = "Не расслышал, повторите" }
            override fun onPartialResults(p: android.os.Bundle?) {
                val t = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) liveText = t
            }
            override fun onResults(results: android.os.Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                isListening = false
                liveText = ""
                if (t.isNotBlank()) onRecognized(t) else status = "Ничего не распознано"
            }
            override fun onEvent(e: Int, p: android.os.Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        liveText = ""
        isListening = true
        recognizer.startListening(intent)
    }

    if (showRename) {
        RenameDialog(note.title, onSave = {
            note.title = it.ifBlank { "Заметка" }; onChanged(); showRename = false
        }, onDismiss = { showRename = false })
    }

    Scaffold(containerColor = cs.background) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

            // Шапка редактора
            Row(
                Modifier.fillMaxWidth()
                    .background(Palette.Ink)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("‹ Назад", color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showRename = true }) {
                    Text(note.title.take(20), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("Удалить", color = Palette.Red) }
            }

            // Текст
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                Surface(
                    color = cs.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (shown.isBlank() && !isProcessing) {
                        Text("Текст появится здесь.", color = cs.onSurfaceVariant,
                            fontSize = 15.sp, modifier = Modifier.padding(20.dp))
                    } else {
                        SelectionContainer {
                            Text(
                                if (needsCompute) "…" else shown,
                                color = cs.onSurface, fontSize = 17.sp,
                                modifier = Modifier.fillMaxSize()
                                    .verticalScroll(rememberScrollState()).padding(20.dp)
                            )
                        }
                    }
                }
                if (isProcessing || needsCompute) {
                    LinearProgressIndicator(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 24.dp),
                        color = accent
                    )
                }
            }

            // Нижняя панель
            Surface(color = cs.surface, shadowElevation = 12.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {

                    LevelStepper(levelIdx, accent) {
                        levelIdx = it
                        if (note.getVariant(Level.fromIndex(it), tone) == null && !isListening)
                            recomputeFrom(Level.fromIndex(it), tone)
                    }
                    Text(TextCondenser.zoneHint(level), color = accent, fontSize = 12.sp)

                    Spacer(Modifier.height(8.dp))

                    ToneStepper(toneIdx) {
                        toneIdx = it
                        note.variants.clear()
                        if (!isListening) recomputeFrom(level, Tone.fromIndex(it))
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(status, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (!hasPermission) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else if (isListening) { recognizer?.stopListening(); isListening = false }
                            else startListening()
                        },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isListening) Palette.Red else Palette.Ink),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(if (isListening) "Остановить" else "Запись",
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconAction("Копировать", Modifier.weight(1f),
                            enabled = shown.isNotBlank()) {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Заметка", shown))
                            status = "Скопировано"
                        }
                        IconAction("Поделиться", Modifier.weight(1f),
                            enabled = shown.isNotBlank()) {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shown)
                            }
                            context.startActivity(Intent.createChooser(share, "Поделиться"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconAction(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        modifier = modifier) {
        Text(label, fontSize = 13.sp, maxLines = 1)
    }
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

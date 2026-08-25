package com.example.voicenotes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface(Modifier.fillMaxSize()) { VoiceNotesScreen() } }
        }
    }
}

private const val PREFS = "voicenotes_prefs"
private const val KEY_APIKEY = "gemini_api_key"

@Composable
fun VoiceNotesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var originalText by remember { mutableStateOf("") }      // сырая расшифровка
    var displayText by remember { mutableStateOf("") }        // то, что видит пользователь
    var concentration by remember { mutableStateOf(0f) }
    var isListening by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Нажмите микрофон, чтобы начать") }
    var showSettings by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(prefs.getString(KEY_APIKEY, "") ?: "") }

    val useAI = apiKey.isNotBlank()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context))
            SpeechRecognizer.createSpeechRecognizer(context) else null
    }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    // Пересчёт результата. Для правил — мгновенно. Для ИИ — по кнопке "Обработать".
    fun recomputeRuleBased() {
        displayText = TextCondenser.condense(originalText, concentration)
    }

    fun processWithAI() {
        if (originalText.isBlank()) return
        isProcessing = true
        status = "ИИ обрабатывает…"
        scope.launch {
            try {
                displayText = GeminiClient.process(originalText, concentration, apiKey)
                status = "Готово (ИИ)."
            } catch (e: Exception) {
                status = e.message ?: "Ошибка обработки"
                if (displayText.isBlank()) displayText = TextCondenser.condense(originalText, concentration)
            } finally {
                isProcessing = false
            }
        }
    }

    fun onNewTranscript(text: String) {
        originalText = if (originalText.isBlank()) text else "$originalText. $text"
        if (useAI) processWithAI() else recomputeRuleBased()
    }

    fun startListening() {
        if (recognizer == null) { status = "Распознавание речи недоступно"; return }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { status = "Говорите…" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(r: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { status = "Обработка речи…" }
            override fun onError(e: Int) { isListening = false; status = "Не расслышал. Попробуйте ещё раз." }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                isListening = false
                if (text.isNotBlank()) onNewTranscript(text)
                else status = "Ничего не распознано."
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        isListening = true
        recognizer.startListening(intent)
    }

    if (showSettings) {
        SettingsDialog(
            initialKey = apiKey,
            onSave = { newKey ->
                apiKey = newKey.trim()
                prefs.edit().putString(KEY_APIKEY, apiKey).apply()
                showSettings = false
                status = if (apiKey.isNotBlank()) "Режим ИИ включён." else "Режим правил (без ИИ)."
            },
            onDismiss = { showSettings = false }
        )
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Смысл-заметки", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { showSettings = true }) {
                Text(if (useAI) "⚙ ИИ" else "⚙ Настройки")
            }
        }

        Spacer(Modifier.height(8.dp))

        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            OutlinedTextField(
                value = displayText,
                onValueChange = { },
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                placeholder = { Text("Здесь появится обработанный текст…") }
            )
        }

        Spacer(Modifier.height(12.dp))

        ConcentrationSlider(
            value = concentration,
            onValueChange = { concentration = it },
            onFinished = { if (!useAI) recomputeRuleBased() }
        )
        Spacer(Modifier.height(4.dp))
        val inRed = concentration >= TextCondenser.THRESHOLD
        Text(
            TextCondenser.zoneHint(concentration),
            fontSize = 12.sp,
            color = if (inRed) Color(0xFFC62828)
                    else Color(0xFF2E7D32)
        )
        Text("Концентрация: ${(concentration * 100).roundToInt()}%   •   " +
                if (useAI) "режим ИИ" else "режим правил",
            fontSize = 12.sp, color = Color.Gray)

        Spacer(Modifier.height(6.dp))
        Text(status, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
        Spacer(Modifier.height(10.dp))

        // Крупная главная кнопка записи — её видно сразу.
        Button(
            onClick = {
                if (!hasPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else if (isListening) { recognizer?.stopListening(); isListening = false }
                else startListening()
            },
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) Color(0xFFE53935) else Color(0xFF3F51B5)
            )
        ) {
            Text(
                if (isListening) "⏹  Остановить запись" else "🎤  Начать запись",
                fontSize = 17.sp, fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(8.dp))

        // Второй ряд: обработать (если ИИ), копировать, поделиться, очистить.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (useAI) {
                OutlinedButton(
                    onClick = { processWithAI() },
                    enabled = originalText.isNotBlank() && !isProcessing && !isListening,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isProcessing) CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Обработать")
                }
            }

            OutlinedButton(
                onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    clip.setPrimaryClip(
                        android.content.ClipData.newPlainText("Заметка", displayText))
                    status = "Скопировано в буфер."
                },
                enabled = displayText.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("Копировать") }

            OutlinedButton(
                onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, displayText)
                    }
                    context.startActivity(Intent.createChooser(share, "Поделиться заметкой"))
                },
                enabled = displayText.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("Отправить") }
        }

        Spacer(Modifier.height(6.dp))

        TextButton(
            onClick = { originalText = ""; displayText = ""; status = "Очищено." },
            enabled = originalText.isNotBlank() && !isProcessing,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Очистить заметку", color = Color(0xFFC62828)) }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ConcentrationSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    val green = Color(0xFF4CAF50)
    val red = Color(0xFFE53935)
    val threshold = TextCondenser.THRESHOLD

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Дословно", fontSize = 11.sp, color = green, fontWeight = FontWeight.Medium)
            Text("Общий смысл", fontSize = 11.sp, color = red, fontWeight = FontWeight.Medium)
        }

        // Цветная дорожка с риской порога, нарисованная под ползунком.
        Box(Modifier.fillMaxWidth().height(40.dp)) {
            Canvas(
                Modifier.fillMaxWidth().height(6.dp).align(Alignment.Center)
            ) {
                val w = size.width
                val h = size.height
                val split = w * threshold
                drawRoundRect(
                    color = green.copy(alpha = 0.35f),
                    size = androidx.compose.ui.geometry.Size(split, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2)
                )
                drawRect(
                    color = red.copy(alpha = 0.35f),
                    topLeft = androidx.compose.ui.geometry.Offset(split, 0f),
                    size = androidx.compose.ui.geometry.Size(w - split, h)
                )
                // риска-порог
                drawRect(
                    color = Color(0xFF757575),
                    topLeft = androidx.compose.ui.geometry.Offset(split - 1.5f, -6f),
                    size = androidx.compose.ui.geometry.Size(3f, h + 12f)
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onFinished,
                colors = SliderDefaults.colors(
                    thumbColor = if (value >= threshold) red else green,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    initialKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var key by remember { mutableStateOf(initialKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ключ Gemini (необязательно)") },
        text = {
            Column {
                Text(
                    "Без ключа приложение чистит текст по правилам бесплатно.\n\n" +
                    "Вставьте бесплатный ключ Gemini — и ползунок начнёт делать " +
                    "настоящий пересказ смыслом. Ключ хранится только на телефоне.\n\n" +
                    "Получить ключ: aistudio.google.com/apikey (бесплатно, без карты).",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Ключ (начинается с AIza…)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key) }) { Text("Сохранить") } },
        dismissButton = {
            TextButton(onClick = { onSave("") }) { Text("Убрать ключ") }
        }
    )
}

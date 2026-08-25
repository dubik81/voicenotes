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
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
        setContent { MaterialTheme { VoiceNotesScreen() } }
    }
}

private const val PREFS = "voicenotes_prefs"
private const val KEY_APIKEY = "openrouter_api_key"

// Палитра
private val Ink = Color(0xFF1A1A2E)
private val GreenZone = Color(0xFF2E9E6B)
private val RedZone = Color(0xFFD84B4B)
private val Cloud = Color(0xFFF5F6FA)

@Composable
fun VoiceNotesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var originalText by remember { mutableStateOf("") }
    var displayText by remember { mutableStateOf("") }
    var concentration by remember { mutableStateOf(0f) }
    var isListening by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Нажмите «Начать запись»") }
    var showSettings by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(prefs.getString(KEY_APIKEY, "") ?: "") }

    val useAI = apiKey.isNotBlank()
    val inRed = concentration >= AiClient.THRESHOLD
    val accent by animateColorAsState(if (inRed) RedZone else GreenZone, label = "accent")

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

    fun recomputeRuleBased() {
        displayText = TextCondenser.condense(originalText, concentration)
    }

    fun processWithAI() {
        if (originalText.isBlank()) return
        isProcessing = true
        status = "ИИ обрабатывает…"
        scope.launch {
            try {
                displayText = AiClient.process(originalText, concentration, apiKey)
                status = "Готово (ИИ)"
            } catch (e: Exception) {
                status = e.message ?: "Ошибка обработки"
                if (displayText.isBlank()) recomputeRuleBased()
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
            override fun onEndOfSpeech() { status = "Распознаю…" }
            override fun onError(e: Int) { isListening = false; status = "Не расслышал, повторите" }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                isListening = false
                if (text.isNotBlank()) onNewTranscript(text) else status = "Ничего не распознано"
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
                status = if (apiKey.isNotBlank()) "Режим ИИ включён" else "Режим правил"
            },
            onDismiss = { showSettings = false }
        )
    }

    Surface(Modifier.fillMaxSize(), color = Cloud) {
        Column(Modifier.fillMaxSize()) {

            // ── Шапка с градиентом ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Ink, Ink.copy(alpha = 0.88f))))
                    .padding(horizontal = 20.dp)
                    .padding(top = 28.dp, bottom = 18.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Смысл", color = Color.White, fontSize = 26.sp,
                            fontWeight = FontWeight.Bold)
                        Text(if (useAI) "ИИ-режим • OpenRouter" else "бесплатный режим",
                            color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    TextButton(onClick = { showSettings = true }) {
                        Text("Настройки", color = Color.White.copy(alpha = 0.85f))
                    }
                }
            }

            // ── Область текста (единственное, что прокручивается) ──
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
            ) {
                if (displayText.isBlank()) {
                    Text(
                        "Здесь появится обработанный текст.\n\nНажмите «Начать запись» и продиктуйте заметку.",
                        color = Color(0xFF9AA0AD), fontSize = 15.sp,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    SelectionContainer {
                        Text(
                            displayText,
                            color = Ink, fontSize = 17.sp,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(20.dp)
                        )
                    }
                }
            }

            // ── Нижняя закреплённая панель: ползунок + кнопки ──
            Surface(color = Color.White, shadowElevation = 12.dp) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {

                    ConcentrationSlider(concentration, accent,
                        onValueChange = { concentration = it },
                        onFinished = { if (!useAI) recomputeRuleBased() })

                    Text(
                        TextCondenser.zoneHint(concentration),
                        color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium
                    )
                    Text(
                        "${(concentration * 100).roundToInt()}% • $status",
                        color = Color(0xFF9AA0AD), fontSize = 11.sp, maxLines = 1
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (!hasPermission)
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else if (isListening) { recognizer?.stopListening(); isListening = false }
                            else startListening()
                        },
                        enabled = !isProcessing,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isListening) RedZone else Ink),
                        modifier = Modifier.fillMaxWidth().height(54.dp)
                    ) {
                        Text(if (isListening) "Остановить" else "Начать запись",
                            fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (useAI) {
                            OutlinedButton(
                                onClick = { processWithAI() },
                                enabled = originalText.isNotBlank() && !isProcessing && !isListening,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isProcessing)
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("Обработать")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                clip.setPrimaryClip(
                                    android.content.ClipData.newPlainText("Заметка", displayText))
                                status = "Скопировано"
                            },
                            enabled = displayText.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("Копировать") }

                        OutlinedButton(
                            onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, displayText)
                                }
                                context.startActivity(Intent.createChooser(share, "Поделиться"))
                            },
                            enabled = displayText.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) { Text("Отправить") }
                    }

                    if (originalText.isNotBlank()) {
                        TextButton(
                            onClick = { originalText = ""; displayText = ""; status = "Очищено" },
                            enabled = !isProcessing,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Очистить", color = RedZone) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConcentrationSlider(
    value: Float,
    accent: Color,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    val threshold = AiClient.THRESHOLD
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Дословно", fontSize = 11.sp, color = GreenZone, fontWeight = FontWeight.Medium)
            Text("Общий смысл", fontSize = 11.sp, color = RedZone, fontWeight = FontWeight.Medium)
        }
        Box(Modifier.fillMaxWidth().height(36.dp)) {
            Canvas(Modifier.fillMaxWidth().height(6.dp).align(Alignment.Center)) {
                val w = size.width; val h = size.height; val split = w * threshold
                drawRoundRect(
                    color = GreenZone.copy(alpha = 0.30f),
                    size = androidx.compose.ui.geometry.Size(split, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2))
                drawRect(
                    color = RedZone.copy(alpha = 0.30f),
                    topLeft = androidx.compose.ui.geometry.Offset(split, 0f),
                    size = androidx.compose.ui.geometry.Size(w - split, h))
                drawRect(
                    color = Color(0xFF757575),
                    topLeft = androidx.compose.ui.geometry.Offset(split - 1.5f, -6f),
                    size = androidx.compose.ui.geometry.Size(3f, h + 12f))
            }
            Slider(
                value = value, onValueChange = onValueChange, onValueChangeFinished = onFinished,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent),
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
        title = { Text("Ключ OpenRouter (необязательно)") },
        text = {
            Column {
                Text(
                    "Без ключа приложение чистит текст по правилам бесплатно.\n\n" +
                    "Вставьте бесплатный ключ OpenRouter — и ползунок начнёт делать " +
                    "настоящий пересказ смыслом. Ключ хранится только на телефоне.\n\n" +
                    "Получить ключ: openrouter.ai/keys (бесплатно, без карты).",
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("Ключ (sk-or-…)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()

package com.example.voicenotes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Состояния загрузки моделей (для индикаторов).
    var whisperDl by remember { mutableStateOf(-1) }
    var whisperDlError by remember { mutableStateOf<String?>(null) }
    var modelsTick by remember { mutableStateOf(0) }  // обновить статусы
    var localAiModel by remember { mutableStateOf(settings.localAiModel) }
    var localAiDl by remember { mutableStateOf(-1) }
    var localAiErr by remember { mutableStateOf<String?>(null) }

    var key by remember { mutableStateOf(settings.apiKey) }
    var pause by remember { mutableStateOf(settings.pauseSeconds) }
    var saveAudio by remember { mutableStateOf(settings.saveAudio) }
    var useWhisper by remember { mutableStateOf(settings.useWhisper) }
    var autoAi by remember { mutableStateOf(settings.autoAi) }
    var whisperModel by remember { mutableStateOf(settings.whisperModel) }
    var precompute by remember { mutableStateOf(settings.precomputeAll) }
    var fontSize by remember { mutableStateOf(settings.fontSize) }
    var newestFirst by remember { mutableStateOf(settings.newestFirst) }
    var confirmDelete by remember { mutableStateOf(settings.confirmDelete) }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun saveAll() {
        settings.apiKey = key.trim()
        settings.pauseSeconds = pause
        settings.saveAudio = saveAudio
        settings.useWhisper = useWhisper
        settings.autoAi = autoAi
        settings.whisperModel = whisperModel
        settings.precomputeAll = precompute
        settings.fontSize = fontSize
        settings.newestFirst = newestFirst
        settings.confirmDelete = confirmDelete
    }

    Dialog(
        onDismissRequest = { saveAll(); onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = cs.background,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(Modifier.fillMaxSize()) {

                // Шапка
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Palette.Ink, Palette.InkSoft)))
                        .padding(horizontal = 20.dp).padding(top = 28.dp, bottom = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Настройки", color = Color.White, fontSize = 22.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { saveAll(); onClose() }) {
                        Text("Готово", color = Palette.Amber, fontWeight = FontWeight.SemiBold)
                    }
                }

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    // ── ИИ ──
                    SectionTitle("Искусственный интеллект")
                    SettingCard {
                        Text("Ключ OpenRouter", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Text("Без ключа работает бесплатный режим на правилах. " +
                             "Получить: openrouter.ai/keys",
                            fontSize = 11.sp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = key, onValueChange = { key = it; testResult = null },
                            label = { Text("sk-or-…") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    testing = true; testResult = null
                                    scope.launch {
                                        settings.apiKey = key.trim()
                                        testResult = AiClient.testKey(key.trim())
                                        testing = false
                                    }
                                },
                                enabled = !testing,
                                colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink)
                            ) {
                                if (testing) CircularProgressIndicator(
                                    Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                else Text("Проверить ключ", color = Color.White)
                            }
                        }
                        testResult?.let { (ok, msg) ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                (if (ok) "✓ " else "✗ ") + msg,
                                color = if (ok) Palette.Green else Palette.Red,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // ── Распознавание ──
                    SectionTitle("Распознавание речи")
                    SettingCard {
                        Text("Режим записи выбирается на главном экране кнопками " +
                             "«Онлайн» (Google, точнее) и «Офлайн» (Vosk, с записью аудио). " +
                             "Модель Vosk (~45 МБ) скачается при первом офлайн-запуске.",
                            fontSize = 11.sp, color = cs.onSurfaceVariant)
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Text("Авто-остановка при паузе", fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "Непрерывно", 3 to "3с", 5 to "5с", 8 to "8с").forEach { (v, lbl) ->
                                FilterChip(selected = pause == v, onClick = { pause = v },
                                    label = { Text(lbl, fontSize = 11.sp) })
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        ToggleRow("Сохранять аудио (офлайн-режим)", saveAudio) { saveAudio = it }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        // Whisper — точное офлайн-уточнение
                        ToggleRow("Уточнять через Whisper (точнее)", useWhisper) { useWhisper = it }
                        Text("После офлайн-записи текст автоматически уточняется точной " +
                             "офлайн-моделью Whisper. Модель скачается один раз.",
                            fontSize = 11.sp, color = cs.onSurfaceVariant)
                        if (useWhisper) {
                            Spacer(Modifier.height(8.dp))
                            Text("Модель Whisper:", fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "tiny" to "Tiny 75МБ",
                                    "base" to "Base 142МБ",
                                    "small" to "Small 466МБ"
                                ).forEach { (id, lbl) ->
                                    FilterChip(selected = whisperModel == id,
                                        onClick = { whisperModel = id },
                                        label = { Text(lbl, fontSize = 10.sp) })
                                }
                            }
                        }
                    }

                    // ── Обработка ──
                    SectionTitle("Модели распознавания")
                    SettingCard {
                        modelsTick // подписка на обновление статусов
                        // Vosk
                        val voskReady = VoskModelManager.isReady(context)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Vosk (офлайн, черновик)", fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                                modifier = Modifier.weight(1f))
                            Text(if (voskReady) "✓ скачана" else "не скачана",
                                fontSize = 12.sp,
                                color = if (voskReady) Palette.Green else cs.onSurfaceVariant)
                        }
                        Text("~45 МБ. Скачивается при первой офлайн-записи.",
                            fontSize = 10.sp, color = cs.onSurfaceVariant)

                        HorizontalDivider(Modifier.padding(vertical = 10.dp))

                        // Whisper
                        val whModelId = whisperModel
                        val whInfo = WhisperModelManager.MODELS[whModelId]
                        val whReady = WhisperModelManager.isReady(context, whModelId)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Whisper (${whModelId}, точный)", fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold, color = cs.onSurface,
                                modifier = Modifier.weight(1f))
                            Text(if (whReady) "✓ скачана" else "не скачана",
                                fontSize = 12.sp,
                                color = if (whReady) Palette.Green else cs.onSurfaceVariant)
                        }
                        Text("~${whInfo?.sizeMb ?: 142} МБ. Скачивается с huggingface.co (нужен интернет).",
                            fontSize = 10.sp, color = cs.onSurfaceVariant)

                        if (whisperDl in 0..100) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { whisperDl / 100f },
                                modifier = Modifier.fillMaxWidth(), color = Palette.Amber)
                            Text("Загрузка: $whisperDl%", fontSize = 10.sp, color = cs.onSurfaceVariant)
                        }
                        whisperDlError?.let { err ->
                            Spacer(Modifier.height(6.dp))
                            Text("✗ $err", color = Palette.Red, fontSize = 11.sp)
                        }

                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                whisperDlError = null
                                scope.launch {
                                    try {
                                        whisperDl = 0
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            WhisperModelManager.download(context, whModelId) { p -> whisperDl = p }
                                        }
                                        whisperDl = -1
                                        modelsTick++
                                    } catch (e: Exception) {
                                        whisperDl = -1
                                        whisperDlError = e.message ?: "Ошибка загрузки"
                                    }
                                }
                            },
                            enabled = whisperDl < 0 && !whReady,
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (whReady) "Модель Whisper скачана" else "Скачать модель Whisper",
                                color = Color.White, fontSize = 13.sp)
                        }
                    }

                    // ── Локальный ИИ (работа со смыслом офлайн) ──
                    SectionTitle("Локальный ИИ (офлайн-обработка)")
                    SettingCard {
                        modelsTick
                        Text("Обрабатывает текст на устройстве без интернета. Выбери и скачай модель. " +
                             "Если модель не скачана — используется облачный ИИ.",
                            fontSize = 11.sp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Модель:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Spacer(Modifier.height(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LocalAiModelManager.MODELS.forEach { (id, info) ->
                                val sel = localAiModel == id
                                Surface(
                                    color = if (sel) Palette.Ink.copy(alpha = 0.1f) else cs.surface,
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp,
                                        if (sel) Palette.Ink else cs.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { localAiModel = id; settings.localAiModel = id }
                                ) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(info.label, fontSize = 12.sp,
                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                            color = cs.onSurface, modifier = Modifier.weight(1f))
                                        if (LocalAiModelManager.isReady(context, id))
                                            Text("✓", color = Palette.Green, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                        if (localAiDl in 0..100) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { localAiDl / 100f },
                                modifier = Modifier.fillMaxWidth(), color = Palette.Amber)
                            Text("Загрузка: $localAiDl%", fontSize = 10.sp, color = cs.onSurfaceVariant)
                        }
                        localAiErr?.let { Text("✗ $it", color = Palette.Red, fontSize = 11.sp) }
                        Spacer(Modifier.height(8.dp))
                        val localReady = LocalAiModelManager.isReady(context, localAiModel)
                        Button(
                            onClick = {
                                localAiErr = null
                                scope.launch {
                                    try {
                                        localAiDl = 0
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            LocalAiModelManager.download(context, localAiModel) { p -> localAiDl = p }
                                        }
                                        localAiDl = -1; modelsTick++
                                    } catch (e: Exception) {
                                        localAiDl = -1; localAiErr = e.message ?: "Ошибка загрузки"
                                    }
                                }
                            },
                            enabled = localAiDl < 0 && !localReady,
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (localReady) "Модель ИИ скачана" else "Скачать модель ИИ",
                                color = Color.White, fontSize = 13.sp)
                        }
                    }

                    SectionTitle("Обработка текста")
                    SettingCard {
                        ToggleRow("ИИ запускается автоматически после записи", autoAi) { autoAi = it }
                        Text("Если выключено — обработка ИИ (Vosk+Whisper→ансамбль) запускается " +
                             "вручную кнопкой ✨ рядом с записью.",
                            fontSize = 11.sp, color = cs.onSurfaceVariant)
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        ToggleRow("Досчитывать все варианты в фоне", precompute) { precompute = it }
                    }

                    // ── Заметки ──
                    SectionTitle("Заметки")
                    SettingCard {
                        ToggleRow("Новые заметки сверху", newestFirst) { newestFirst = it }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        ToggleRow("Спрашивать перед удалением", confirmDelete) { confirmDelete = it }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Text("Размер шрифта: $fontSize", fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Slider(
                            value = fontSize.toFloat(),
                            onValueChange = { fontSize = it.toInt() },
                            valueRange = 13f..24f,
                            colors = SliderDefaults.colors(thumbColor = Palette.Ink,
                                activeTrackColor = Palette.Ink)
                        )
                        Text("Пример текста этого размера.", fontSize = fontSize.sp, color = cs.onSurface)
                    }

                    // ── Вид ──
                    SectionTitle("Оформление")
                    SettingCard {
                        ToggleRow("Тёмная тема", dark, onDarkChange)
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        color = Palette.Amber, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
private fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surface, shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

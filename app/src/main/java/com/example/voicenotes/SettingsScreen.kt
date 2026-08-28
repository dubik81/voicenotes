package com.example.voicenotes

import androidx.compose.foundation.background
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

    var key by remember { mutableStateOf(settings.apiKey) }
    var pause by remember { mutableStateOf(settings.pauseSeconds) }
    var saveAudio by remember { mutableStateOf(settings.saveAudio) }
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
                    }

                    // ── Обработка ──
                    SectionTitle("Обработка текста")
                    SettingCard {
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

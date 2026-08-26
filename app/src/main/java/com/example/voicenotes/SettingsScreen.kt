package com.example.voicenotes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onClose: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var key by remember { mutableStateOf(settings.apiKey) }
    var pause by remember { mutableStateOf(settings.pauseSeconds) }
    var saveAudio by remember { mutableStateOf(settings.saveAudio) }
    var precompute by remember { mutableStateOf(settings.precomputeAll) }

    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(18.dp), color = cs.surface) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text("Настройки", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = cs.onSurface)
                Spacer(Modifier.height(16.dp))

                Text("Ключ OpenRouter (для ИИ-режима)", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Text("Без ключа работает бесплатный режим на правилах. Ключ: openrouter.ai/keys",
                    fontSize = 11.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("sk-or-…") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(18.dp))
                Text("Авто-остановка записи при паузе", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Непрерывно", 3 to "3 сек", 5 to "5 сек", 8 to "8 сек").forEach { (v, lbl) ->
                        FilterChip(selected = pause == v, onClick = { pause = v }, label = { Text(lbl, fontSize = 11.sp) })
                    }
                }

                Spacer(Modifier.height(18.dp))
                ToggleRow("Тёмная тема", dark, onDarkChange)
                ToggleRow("Сохранять аудио к заметкам", saveAudio) { saveAudio = it }
                ToggleRow("Досчитывать все ступени в фоне", precompute) { precompute = it }

                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("Отмена") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            settings.apiKey = key.trim()
                            settings.pauseSeconds = pause
                            settings.saveAudio = saveAudio
                            settings.precomputeAll = precompute
                            onClose()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Ink)
                    ) { Text("Сохранить", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = cs.onSurface)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

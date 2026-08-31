package com.example.voicenotes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    notes: List<Note>,
    onOpen: (Note) -> Unit,
    onNew: (isLecture: Boolean) -> Unit,      // режим онлайн/офлайн выбирается внутри заметки
    onOpenSettings: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme

    val filtered = remember(notes, query) {
        if (query.isBlank()) notes
        else notes.filter {
            it.title.contains(query, true) || it.original.contains(query, true)
        }
    }

    Box(Modifier.fillMaxSize().background(cs.background)) {
        Column(Modifier.fillMaxSize()) {

            // ── Шапка: «Смысл · заметки» по общей базовой линии ──
            Row(
                Modifier.fillMaxWidth()
                    .background(Palette.Ink)
                    .padding(horizontal = 16.dp)
                    .padding(top = 20.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Text("Смысл", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("· заметки", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 2.dp))
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, "Настройки", tint = Color.White.copy(alpha = 0.75f))
                }
            }

            // ── Поиск ──
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск по заметкам") },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = cs.onSurfaceVariant) },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Palette.Amber,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = cs.surface,
                    unfocusedContainerColor = cs.surface
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // ── Заголовок секции ──
            if (filtered.isNotEmpty()) {
                Text("НЕДАВНИЕ", color = cs.onSurfaceVariant, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
            }

            // ── Список ──
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (notes.isEmpty()) "Пока пусто.\nНажмите «Заметка» или «Лекция»."
                        else "Ничего не найдено.",
                        color = cs.onSurfaceVariant, fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { note ->
                        NoteCard(note) { onOpen(note) }
                    }
                }
            }
        }

        // ── Две кнопки создания в правом нижнем углу (текст внутри) ──
        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CreateButton("Лекция", Icons.Filled.School, Palette.Ink) { onNew(true) }
            CreateButton("Заметка", Icons.Filled.Edit, Palette.Green) { onNew(false) }
        }
    }
}

@Composable
private fun CreateButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                         bg: Color, onClick: () -> Unit) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.width(128.dp).height(60.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dateFmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    val preview = note.original.take(80).ifBlank { "Пустая заметка" }
    // индикатор режима
    val (modeIcon, modeColor) = when {
        note.isLecture -> "🎓" to Palette.Amber
        note.recordMode == "google" -> "🌐" to cs.onSurfaceVariant
        else -> "🎤" to cs.onSurfaceVariant
    }

    Surface(
        color = cs.surface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // Название (доминанта) + мета справа
            Row(verticalAlignment = Alignment.Top) {
                Text(note.title, color = cs.onSurface, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                Text("$modeIcon ${dateFmt.format(Date(note.createdAt))}",
                    color = modeColor, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            // Превью (подчинённое, близко к названию)
            Text(preview, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
        }
    }
}

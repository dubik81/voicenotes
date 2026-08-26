package com.example.voicenotes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
    onNew: () -> Unit,
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

    Scaffold(
        containerColor = cs.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                containerColor = Palette.Ink,
                contentColor = Color.White,
                text = { Text("Новая запись") },
                icon = { Text("🎤", fontSize = 18.sp) }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

            // Шапка
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Palette.Ink, Palette.InkSoft)))
                    .padding(horizontal = 20.dp)
                    .padding(top = 28.dp, bottom = 20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Смысл", color = Color.White, fontSize = 28.sp,
                            fontWeight = FontWeight.Bold)
                        Text("голосовые заметки", color = Palette.Amber, fontSize = 13.sp)
                    }
                    TextButton(onClick = onOpenSettings) {
                        Text("Настройки", color = Color.White.copy(alpha = 0.85f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Поиск по заметкам") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.12f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Palette.Amber,
                        unfocusedBorderColor = Color.Transparent,
                        focusedPlaceholderColor = Color.White.copy(alpha = 0.6f),
                        unfocusedPlaceholderColor = Color.White.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (notes.isEmpty()) "Пока пусто.\nНажмите «Новая запись»."
                        else "Ничего не найдено.",
                        color = cs.onSurfaceVariant, fontSize = 15.sp
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { note ->
                        NoteCard(note) { onOpen(note) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dateFmt = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    val preview = note.original.take(90).ifBlank { "Пустая заметка" }

    Surface(
        color = cs.surface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(note.title, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(preview, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            Text(dateFmt.format(Date(note.createdAt)), color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

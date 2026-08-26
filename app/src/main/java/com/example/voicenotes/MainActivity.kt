package com.example.voicenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val store = remember { NoteStore(context) }

    var notes by remember { mutableStateOf(store.load()) }
    var dark by remember { mutableStateOf(settings.darkTheme) }
    var openNoteId by remember { mutableStateOf<Long?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    fun persist() { store.save(notes) }

    AppTheme(dark = dark) {
        val current = notes.find { it.id == openNoteId }
        if (current != null) {
            EditorScreen(
                note = current,
                settings = settings,
                onBack = { openNoteId = null; persist() },
                onChanged = { notes = notes.toMutableList(); persist() },
                onDelete = {
                    notes = notes.filter { it.id != current.id }.toMutableList()
                    openNoteId = null; persist()
                }
            )
        } else {
            NotesListScreen(
                notes = notes,
                onOpen = { openNoteId = it.id },
                onNew = {
                    val n = Note(
                        id = System.currentTimeMillis(),
                        title = "Заметка",
                        createdAt = System.currentTimeMillis(),
                        original = ""
                    )
                    notes = (mutableListOf(n) + notes).toMutableList()
                    openNoteId = n.id
                    persist()
                },
                onOpenSettings = { showSettings = true }
            )
        }

        if (showSettings) {
            SettingsScreen(
                settings = settings,
                dark = dark,
                onDarkChange = { dark = it; settings.darkTheme = it },
                onClose = { showSettings = false }
            )
        }
    }
}

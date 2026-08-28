package com.example.voicenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope

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
    val appScope = rememberCoroutineScope()

    var notes by remember { mutableStateOf(store.load()) }
    var dark by remember { mutableStateOf(settings.darkTheme) }
    var openNoteId by remember { mutableStateOf<Long?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    fun persist() { store.save(notes) }

    // Живучий процессор: доступ к заметкам + сохранение на диск.
    val processor = remember {
        VariantProcessor(
            scope = appScope,
            settings = settings,
            notesProvider = { notes },
            persist = {
                store.save(notes)
                notes = notes.toMutableList()  // триггер обновления UI
            }
        )
    }

    // Периодически продолжаем недосчитанное во ВСЕХ заметках (фон надёжного уровня:
    // работает пока приложение живо — открыто или свёрнуто).
    LaunchedEffect(Unit) {
        while (true) {
            processor.resumeAll()
            kotlinx.coroutines.delay(15000)
        }
    }

    AppTheme(dark = dark) {
        val current = notes.find { it.id == openNoteId }
        if (current != null) {
            EditorScreen(
                note = current,
                settings = settings,
                processor = processor,
                onBack = { openNoteId = null; persist() },
                onChanged = { notes = notes.toMutableList(); persist() },
                onDelete = {
                    current.audioPath?.let { path ->
                        try { java.io.File(path).delete() } catch (_: Exception) {}
                    }
                    processor.reset(current.id)
                    notes = notes.filter { it.id != current.id }.toMutableList()
                    openNoteId = null; persist()
                }
            )
        } else {
            val sorted = if (settings.newestFirst)
                notes.sortedByDescending { it.createdAt }
            else notes.sortedBy { it.createdAt }
            NotesListScreen(
                notes = sorted,
                onOpen = { openNoteId = it.id },
                onNew = { useVosk, isLecture ->
                    settings.useVosk = useVosk
                    val n = Note(
                        id = System.currentTimeMillis(),
                        title = if (isLecture) "Лекция" else "Заметка",
                        createdAt = System.currentTimeMillis(),
                        original = "",
                        recordMode = if (isLecture) "lecture" else if (useVosk) "vosk" else "google",
                        isLecture = isLecture
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

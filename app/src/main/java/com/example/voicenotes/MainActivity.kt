package com.example.voicenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

/** Держит экран включённым: 2 мин полной яркости, затем 0.5 мин приглушённо, потом отпускает. */
@Composable
fun KeepScreenOn(activity: ComponentActivity, resetKey: Int) {
    LaunchedEffect(resetKey) {
        val window = activity.window
        try {
            // включаем удержание + полная (обычная) яркость
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            kotlinx.coroutines.delay(120_000)  // 2 минуты ярко
            // приглушаем
            window.attributes = window.attributes.apply { screenBrightness = 0.15f }
            kotlinx.coroutines.delay(30_000)   // 0.5 минуты приглушённо
        } finally {
            // отпускаем — система гасит по своим настройкам, яркость возвращаем
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
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

    // Экран не гаснет: 2 мин ярко, 0.5 мин приглушённо. Сброс по касанию.
    var screenResetKey by remember { mutableStateOf(0) }
    val activity = context as? ComponentActivity
    if (activity != null) KeepScreenOn(activity, screenResetKey)

    fun persist() { store.save(notes) }

    // Живучий процессор: доступ к заметкам + сохранение на диск.
    val processor = remember {
        VariantProcessor(
            scope = appScope,
            settings = settings,
            context = context,
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
      androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    screenResetKey++  // любое касание продлевает подсветку
                }
            }
        }
      ) {
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
                onNew = { isLecture ->
                    val n = Note(
                        id = System.currentTimeMillis(),
                        title = if (isLecture) "Лекция" else "Заметка",
                        createdAt = System.currentTimeMillis(),
                        original = "",
                        recordMode = if (isLecture) "lecture" else "vosk",
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
                onClose = { showSettings = false },
                appScope = appScope
            )
        }
      }
    }
}

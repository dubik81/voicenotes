package com.example.voicenotes

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Экспорт заметки в читаемый JSON-файл для разбора. */
object NoteExporter {

    fun exportJson(context: Context, note: Note): File {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val root = JSONObject().apply {
            put("id", note.id)
            put("title", note.title)
            put("created", dateFmt.format(Date(note.createdAt)))
            put("original", note.original)
            put("original_length", note.original.length)
            put("refined_text", note.refinedText ?: JSONObject.NULL)
            put("is_refined_applied", note.isRefined)
            put("has_audio", note.audioPath != null)

            // Все варианты по ступеням и тонам, с длиной для контроля.
            val variants = JSONObject()
            for (l in Level.entries) {
                if (l == Level.VERBATIM) continue
                val byTone = JSONObject()
                for (t in Tone.entries) {
                    val v = note.getVariant(l, t)
                    if (v != null) {
                        byTone.put(t.name, JSONObject().apply {
                            put("text", v)
                            put("length", v.length)
                        })
                    } else {
                        byTone.put(t.name, JSONObject.NULL)
                    }
                }
                variants.put(l.name, byTone)
            }
            put("variants", variants)
        }

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTitle = note.title.take(20).replace(Regex("[^\\p{L}\\d]+"), "_")
        val file = File(dir, "zametka_${safeTitle}_${note.id}.json")
        file.writeText(root.toString(2))
        return file
    }
}

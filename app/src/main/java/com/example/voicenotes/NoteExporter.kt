package com.example.voicenotes

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Экспорт заметки. JSON с текстом + (если есть) аудио, упакованные в один zip. */
object NoteExporter {

    fun exportJson(context: Context, note: Note): File {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val root = JSONObject().apply {
            put("id", note.id)
            put("title", note.title)
            put("created", dateFmt.format(Date(note.createdAt)))
            put("is_lecture", note.isLecture)
            put("record_mode", note.recordMode)
            put("original", note.original)
            put("original_length", note.original.length)
            put("refined_text", note.refinedText ?: JSONObject.NULL)
            put("is_refined_applied", note.isRefined)
            put("has_audio", note.audioPath != null)

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
                    } else byTone.put(t.name, JSONObject.NULL)
                }
                variants.put(l.name, byTone)
            }
            put("variants", variants)

            // История редакций (все сохранённые версии каждого варианта).
            val hist = JSONObject()
            note.history.forEach { (key, list) ->
                val arr = org.json.JSONArray()
                list.forEach { arr.put(it) }
                hist.put(key, arr)
            }
            put("history", hist)
        }

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTitle = note.title.take(20).replace(Regex("[^\\p{L}\\d]+"), "_")
        val jsonFile = File(dir, "zametka_${safeTitle}_${note.id}.json")
        jsonFile.writeText(root.toString(2))
        return jsonFile
    }

    /** Полный комплект: JSON + аудио (если есть) в одном zip-архиве. */
    fun exportFull(context: Context, note: Note): File {
        val jsonFile = exportJson(context, note)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTitle = note.title.take(20).replace(Regex("[^\\p{L}\\d]+"), "_")
        val zipFile = File(dir, "zametka_${safeTitle}_${note.id}.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            // JSON
            zos.putNextEntry(ZipEntry(jsonFile.name))
            zos.write(jsonFile.readBytes())
            zos.closeEntry()
            // аудио, если есть и это офлайн-заметка (в онлайне звук не пишется)
            if (note.recordMode != "google") {
                note.audioPath?.let { path ->
                    val audio = File(path)
                    if (audio.exists() && audio.length() > 44) {
                        zos.putNextEntry(ZipEntry("audio_${note.id}.wav"))
                        zos.write(audio.readBytes())
                        zos.closeEntry()
                    }
                }
            }
        }
        return zipFile
    }
}

package com.example.voicenotes

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Генерация настоящего .docx (ZIP с XML) на Android без внешних библиотек.
 * Формирует стенограмму лекции: заголовок, абзацы, заголовки частей (## ), списки (- ).
 *
 * Формат текста на входе (из стенограммы ИИ):
 *   "## Заголовок части"  -> заголовок
 *   "- пункт"             -> элемент списка
 *   обычная строка        -> абзац
 *   пустая строка         -> разрыв абзаца
 */
object DocxExporter {

    fun export(context: Context, note: Note): File {
        val body = buildBody(note.title, contentFor(note))
        val documentXml = DOC_TEMPLATE.replace("{{BODY}}", body)

        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeTitle = note.title.take(20).replace(Regex("[^\\p{L}\\d]+"), "_")
        val file = File(dir, "lekciya_${safeTitle}_${note.id}.docx")

        ZipOutputStream(FileOutputStream(file)).use { zos ->
            writeEntry(zos, "[Content_Types].xml", CONTENT_TYPES)
            writeEntry(zos, "_rels/.rels", RELS)
            writeEntry(zos, "word/document.xml", documentXml)
            writeEntry(zos, "word/_rels/document.xml.rels", DOC_RELS)
            writeEntry(zos, "word/styles.xml", STYLES)
        }
        return file
    }

    /** Берём лучший доступный текст: стенограмму (CLEAN) либо оригинал. */
    private fun contentFor(note: Note): String {
        return note.getVariant(Level.CLEAN, Tone.NEUTRAL)
            ?: note.original
    }

    private fun buildBody(title: String, content: String): String {
        val sb = StringBuilder()
        // Заголовок документа
        sb.append(headingPara(escape(title), "Title"))
        // Тело по строкам
        val lines = content.split("\n")
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {} // пустые пропускаем (абзацы и так разделены)
                line.startsWith("## ") -> sb.append(headingPara(escape(line.removePrefix("## ")), "Heading1"))
                line.startsWith("# ") -> sb.append(headingPara(escape(line.removePrefix("# ")), "Heading1"))
                line.startsWith("- ") -> sb.append(listPara(escape(line.removePrefix("- "))))
                line.startsWith("• ") -> sb.append(listPara(escape(line.removePrefix("• "))))
                else -> sb.append(normalPara(escape(line)))
            }
        }
        return sb.toString()
    }

    private fun headingPara(text: String, style: String): String =
        """<w:p><w:pPr><w:pStyle w:val="$style"/></w:pPr><w:r><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

    private fun normalPara(text: String): String =
        """<w:p><w:r><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

    private fun listPara(text: String): String =
        """<w:p><w:pPr><w:pStyle w:val="ListParagraph"/></w:pPr><w:r><w:t xml:space="preserve">•  $text</w:t></w:r></w:p>"""

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun writeEntry(zos: ZipOutputStream, name: String, content: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="40"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:pPr><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="30"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:pPr><w:ind w:left="720"/></w:pPr></w:style>
</w:styles>"""

    private const val DOC_TEMPLATE = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:body>
{{BODY}}
<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="850" w:bottom="1134" w:left="1134"/></w:sectPr>
</w:body>
</w:document>"""
}

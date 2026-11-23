package com.belugachat.app.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.MimeTypeMap
import com.belugachat.app.data.Part
import com.belugachat.app.llm.Content
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class AttachmentException(message: String) : Exception(message)

/** Copies picked files into app storage and turns them into model input. */
class AttachmentStore(private val context: Context) {
    private val dir = File(context.filesDir, "attachments").apply { mkdirs() }

    fun file(part: Part.File) = File(dir, part.path)

    suspend fun import(uri: Uri): Part.File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (size > MAX_BYTES) throw AttachmentException("$name is larger than 25 MB.")
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val kind = kindOf(mime, name) ?: throw AttachmentException("$name: this file type isn't supported yet. Try images, PDF, Word (.docx), Excel (.xlsx) or text files.")
        val id = UUID.randomUUID().toString()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw AttachmentException("Could not read $name.")
        if (bytes.size > MAX_BYTES) throw AttachmentException("$name is larger than 25 MB.")
        if (kind == Kind.IMAGE) {
            val (data, outMime) = prepareImage(bytes, mime)
            val path = "$id.${if (outMime == "image/png") "png" else "jpg"}"
            File(dir, path).writeBytes(data)
            Part.File(id, name, outMime, path, data.size.toLong())
        } else {
            val path = "$id.${ext.ifEmpty { "bin" }}"
            File(dir, path).writeBytes(bytes)
            Part.File(id, name, mime, path, bytes.size.toLong())
        }
    }

    /** Downscales large photos so they fit provider limits and cost fewer tokens. */
    private fun prepareImage(bytes: ByteArray, mime: String): Pair<ByteArray, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val passthrough = mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        if (passthrough && longest in 1..MAX_SIDE && bytes.size < 4_000_000) return bytes to mime
        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw AttachmentException("Could not decode the image.")
        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray() to "image/jpeg"
    }

    fun toContent(part: Part.File, allowBinaryPdf: Boolean = true): List<Content> {
        val file = file(part)
        if (!file.exists()) return listOf(Content.Text("[Attachment ${part.name} is no longer available]"))
        return when (kindOf(part.mime, part.name)) {
            Kind.IMAGE -> listOf(Content.Image(part.mime, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)))
            Kind.PDF -> if (allowBinaryPdf) {
                listOf(Content.Pdf(part.name, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)))
            } else {
                val extracted = pdfText(file)
                listOf(document(part.name, extracted.ifBlank { "[No selectable text found in this PDF. It may contain only scanned images.]" }))
            }
            Kind.DOCX -> listOf(document(part.name, docxText(file)))
            Kind.XLSX -> listOf(document(part.name, xlsxText(file)))
            Kind.TEXT -> listOf(document(part.name, file.readText().take(MAX_TEXT)))
            null -> emptyList()
        }
    }

    private fun document(name: String, text: String): Content.Text {
        val safeName = name.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val safeText = text.replace(Regex("</document>", RegexOption.IGNORE_CASE), "&lt;/document&gt;")
        return Content.Text("<document name=\"$safeName\">\n$safeText\n</document>")
    }

    fun delete(part: Part.File) {
        file(part).delete()
    }

    enum class Kind { IMAGE, PDF, DOCX, XLSX, TEXT }

    companion object {
        private const val MAX_BYTES = 25L * 1024 * 1024
        private const val MAX_SIDE = 1568
        private const val MAX_TEXT = 400_000
        private const val MAX_ZIP_XML_BYTES = 4 * 1024 * 1024
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private val TEXT_EXT = setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "log", "html", "htm",
            "css", "js", "ts", "tsx", "jsx", "py", "kt", "kts", "java", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php",
            "swift", "sh", "bash", "sql", "r", "lua", "dart", "scala", "gradle", "properties", "env", "tex", "srt", "vtt",
        )

        fun kindOf(mime: String, name: String): Kind? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                mime.startsWith("image/") && mime != "image/svg+xml" -> Kind.IMAGE
                mime == "application/pdf" || ext == "pdf" -> Kind.PDF
                ext == "docx" || mime == DOCX_MIME -> Kind.DOCX
                ext == "xlsx" || mime == XLSX_MIME -> Kind.XLSX
                mime.startsWith("text/") || ext in TEXT_EXT || mime in setOf("application/json", "application/xml", "application/x-yaml") -> Kind.TEXT
                else -> null
            }
        }

        internal fun pdfText(file: File): String = runCatching {
            PDDocument.load(file).use { doc ->
                PDFTextStripper().getText(doc).trim().take(MAX_TEXT)
            }
        }.getOrDefault("")

        internal fun docxText(file: File): String = runCatching {
            ZipFile(file).use { zip ->
                val bytes = readBoundedEntry(zip, "word/document.xml") ?: return@use ""
                val doc = parseXml(bytes)
                val out = StringBuilder()
                fun appendInline(node: Node, target: StringBuilder) {
                    val children = node.childNodes
                    for (i in 0 until children.length) {
                        val child = children.item(i)
                        if (child.nodeType != Node.ELEMENT_NODE) continue
                        when (child.localName ?: child.nodeName.substringAfter(':')) {
                            "t", "delText" -> target.append(child.textContent.orEmpty())
                            "tab" -> target.append('\t')
                            "br", "cr" -> target.append('\n')
                            else -> appendInline(child, target)
                        }
                    }
                }
                fun walk(node: Node) {
                    val children = node.childNodes
                    for (i in 0 until children.length) {
                        val child = children.item(i)
                        if (child.nodeType != Node.ELEMENT_NODE) continue
                        when (child.localName ?: child.nodeName.substringAfter(':')) {
                            "tr" -> {
                                val cells = mutableListOf<String>()
                                val trChildren = child.childNodes
                                for (j in 0 until trChildren.length) {
                                    val cell = trChildren.item(j)
                                    val cellName = cell.localName ?: cell.nodeName.substringAfter(':')
                                    if (cell.nodeType == Node.ELEMENT_NODE && cellName == "tc") {
                                        val cellBuf = StringBuilder()
                                        val cellChildren = cell.childNodes
                                        for (k in 0 until cellChildren.length) {
                                            val p = cellChildren.item(k)
                                            val pName = p.localName ?: p.nodeName.substringAfter(':')
                                            if (p.nodeType == Node.ELEMENT_NODE && pName == "p") {
                                                if (cellBuf.isNotEmpty()) cellBuf.append(' ')
                                                appendInline(p, cellBuf)
                                            }
                                        }
                                        cells += cellBuf.toString().replace(Regex("\\s+"), " ").trim()
                                    }
                                }
                                if (cells.any { it.isNotEmpty() }) {
                                    out.append(cells.joinToString("\t")).append('\n')
                                }
                            }
                            "p" -> {
                                val pBuf = StringBuilder()
                                appendInline(child, pBuf)
                                val line = pBuf.toString().trim()
                                if (line.isNotEmpty()) out.append(line).append('\n')
                            }
                            else -> walk(child)
                        }
                    }
                }
                walk(doc.documentElement)
                out.toString().trim().take(MAX_TEXT)
            }
        }.getOrDefault("[Could not read this Word document]")

        internal fun xlsxText(file: File): String = runCatching {
            ZipFile(file).use { zip ->
                val sharedStrings = readBoundedEntry(zip, "xl/sharedStrings.xml")?.let(::parseSharedStrings).orEmpty()
                val sheetTargets = resolveXlsxSheets(zip)
                if (sheetTargets.isEmpty()) return@use "[Empty Excel workbook]"
                val rendered = sheetTargets.mapNotNull { (name, entryPath) ->
                    val bytes = readBoundedEntry(zip, entryPath) ?: return@mapNotNull null
                    val body = parseWorksheet(parseXml(bytes), sharedStrings)
                    if (body.isBlank()) null else "## $name\n$body"
                }
                if (rendered.isEmpty()) "[Empty Excel workbook]"
                else rendered.joinToString("\n\n").take(MAX_TEXT)
            }
        }.getOrDefault("[Could not read this Excel workbook]")

        private fun readBoundedEntry(zip: ZipFile, entryName: String): ByteArray? {
            val entry = zip.getEntry(entryName) ?: return null
            return zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                var total = 0
                while (total < MAX_ZIP_XML_BYTES) {
                    val toRead = minOf(buffer.size, MAX_ZIP_XML_BYTES - total)
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    total += read
                }
                out.toByteArray()
            }
        }

        private fun parseXml(bytes: ByteArray): Document {
            val header = bytes.copyOfRange(0, minOf(bytes.size, 4096)).toString(Charsets.UTF_8)
            if (header.contains("<!DOCTYPE", ignoreCase = true) || header.contains("<!ENTITY", ignoreCase = true)) {
                throw IllegalArgumentException("DOCTYPE and entity declarations are not permitted")
            }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { isXIncludeAware = false }
                runCatching { isExpandEntityReferences = false }
            }
            return factory.newDocumentBuilder().parse(bytes.inputStream())
        }

        private fun parseSharedStrings(bytes: ByteArray): List<String> {
            val doc = parseXml(bytes)
            val nodes = doc.getElementsByTagNameNS("*", "si")
            val result = ArrayList<String>(nodes.length)
            for (i in 0 until nodes.length) {
                val si = nodes.item(i) as? Element ?: continue
                val tNodes = si.getElementsByTagNameNS("*", "t")
                val sb = StringBuilder()
                for (j in 0 until tNodes.length) {
                    sb.append(tNodes.item(j).textContent.orEmpty())
                }
                result += sb.toString()
            }
            return result
        }

        private fun resolveXlsxSheets(zip: ZipFile): List<Pair<String, String>> {
            val relsById = readBoundedEntry(zip, "xl/_rels/workbook.xml.rels")?.let { bytes ->
                val relDoc = parseXml(bytes)
                val relNodes = relDoc.getElementsByTagNameNS("*", "Relationship")
                buildMap {
                    for (i in 0 until relNodes.length) {
                        val el = relNodes.item(i) as? Element ?: continue
                        val id = el.getAttribute("Id")
                        val target = el.getAttribute("Target")
                        if (id.isNotBlank() && target.isNotBlank()) {
                            val normalized = target.removePrefix("/").let { if (it.startsWith("xl/")) it else "xl/$it" }
                            put(id, normalized)
                        }
                    }
                }
            }.orEmpty()

            val sheetEntries = zip.entries().asSequence()
                .map { it.name }
                .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .sortedBy { it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
                .toList()

            val workbookBytes = readBoundedEntry(zip, "xl/workbook.xml") ?: return sheetEntries.mapIndexed { idx, path ->
                "Sheet ${idx + 1}" to path
            }
            val wbDoc = parseXml(workbookBytes)
            val sheetNodes = wbDoc.getElementsByTagNameNS("*", "sheet")
            val resolved = mutableListOf<Pair<String, String>>()
            for (i in 0 until sheetNodes.length) {
                val el = sheetNodes.item(i) as? Element ?: continue
                val name = el.getAttribute("name").ifBlank { "Sheet ${i + 1}" }
                val rId = el.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    .ifBlank { el.getAttribute("r:id") }
                val target = relsById[rId] ?: sheetEntries.getOrNull(i)
                if (target != null) resolved += name to target
            }
            return resolved.ifEmpty { sheetEntries.mapIndexed { idx, path -> "Sheet ${idx + 1}" to path } }
        }

        private fun parseWorksheet(doc: Document, sharedStrings: List<String>): String {
            val rows = doc.getElementsByTagNameNS("*", "row")
            val lines = mutableListOf<String>()
            for (r in 0 until rows.length) {
                val rowEl = rows.item(r) as? Element ?: continue
                val cells = rowEl.getElementsByTagNameNS("*", "c")
                val rowValues = mutableListOf<String>()
                var expectedCol = 0
                for (cIdx in 0 until cells.length) {
                    val cell = cells.item(cIdx) as? Element ?: continue
                    val ref = cell.getAttribute("r")
                    val col = columnIndex(ref)
                    if (col != null && col > expectedCol && (col - expectedCol) <= 16) {
                        repeat(col - expectedCol) { rowValues += "" }
                    }
                    expectedCol = (col ?: expectedCol) + 1
                    rowValues += extractCellValue(cell, sharedStrings)
                }
                while (rowValues.isNotEmpty() && rowValues.last().isEmpty()) rowValues.removeAt(rowValues.lastIndex)
                if (rowValues.any { it.isNotBlank() }) {
                    lines += rowValues.joinToString("\t")
                }
            }
            return lines.joinToString("\n")
        }

        private fun extractCellValue(cell: Element, sharedStrings: List<String>): String {
            val type = cell.getAttribute("t")
            return when (type) {
                "s" -> {
                    val idx = firstTagText(cell, "v")?.trim()?.toIntOrNull() ?: return ""
                    sharedStrings.getOrElse(idx) { "" }
                }
                "inlineStr" -> {
                    val isEl = cell.getElementsByTagNameNS("*", "is").item(0) as? Element ?: return ""
                    val tNodes = isEl.getElementsByTagNameNS("*", "t")
                    if (tNodes.length > 0) {
                        buildString { for (i in 0 until tNodes.length) append(tNodes.item(i).textContent.orEmpty()) }
                    } else {
                        isEl.textContent.orEmpty()
                    }
                }
                "b" -> when (firstTagText(cell, "v")?.trim()) {
                    "1" -> "TRUE"
                    "0" -> "FALSE"
                    else -> ""
                }
                else -> firstTagText(cell, "v").orEmpty()
            }.replace(Regex("\\s+"), " ").trim()
        }

        private fun firstTagText(parent: Element, localName: String): String? =
            parent.getElementsByTagNameNS("*", localName).item(0)?.textContent

        private fun columnIndex(ref: String): Int? {
            if (ref.isBlank()) return null
            var col = 0
            var found = false
            for (ch in ref) {
                if (ch in 'A'..'Z') {
                    col = col * 26 + (ch - 'A' + 1)
                    found = true
                } else if (ch in 'a'..'z') {
                    col = col * 26 + (ch - 'a' + 1)
                    found = true
                } else break
            }
            return if (found) col - 1 else null
        }
    }
}

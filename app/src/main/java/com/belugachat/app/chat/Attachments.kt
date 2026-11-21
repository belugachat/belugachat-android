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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

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
        val kind = kindOf(mime, name) ?: throw AttachmentException("$name: this file type isn't supported yet. Try images, PDF, Word (.docx) or text files.")
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

    fun toContent(part: Part.File): List<Content> {
        val file = file(part)
        if (!file.exists()) return listOf(Content.Text("[Attachment ${part.name} is no longer available]"))
        return when (kindOf(part.mime, part.name)) {
            Kind.IMAGE -> listOf(Content.Image(part.mime, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)))
            Kind.PDF -> listOf(Content.Pdf(part.name, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)))
            Kind.DOCX -> listOf(document(part.name, docxText(file)))
            Kind.TEXT -> listOf(document(part.name, file.readText().take(MAX_TEXT)))
            null -> emptyList()
        }
    }

    private fun document(name: String, text: String): Content.Text {
        val safeName = name.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        val safeText = text.replace(Regex("</document>", RegexOption.IGNORE_CASE), "&lt;/document&gt;")
        return Content.Text("<document name=\"$safeName\">\n$safeText\n</document>")
    }

    private fun docxText(file: File): String = runCatching {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return@use ""
            val xml = zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                var total = 0
                while (total < MAX_DOCX_XML_BYTES) {
                    val toRead = minOf(buffer.size, MAX_DOCX_XML_BYTES - total)
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                    total += read
                }
                out.toString("UTF-8")
            }
            xml.replace(Regex("</w:p>"), "\n")
                .replace(Regex("<w:tab/>"), "\t")
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .take(MAX_TEXT)
        }
    }.getOrDefault("[Could not read this Word document]")

    fun delete(part: Part.File) {
        file(part).delete()
    }

    enum class Kind { IMAGE, PDF, DOCX, TEXT }

    companion object {
        private const val MAX_BYTES = 25L * 1024 * 1024
        private const val MAX_SIDE = 1568
        private const val MAX_TEXT = 400_000
        private const val MAX_DOCX_XML_BYTES = 4 * 1024 * 1024
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
                ext == "docx" -> Kind.DOCX
                mime.startsWith("text/") || ext in TEXT_EXT || mime in setOf("application/json", "application/xml", "application/x-yaml") -> Kind.TEXT
                else -> null
            }
        }
    }
}

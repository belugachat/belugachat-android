package com.belugachat.app.ext

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class RepoFile(val path: String, val bytes: ByteArray)

data class ParsedSkill(
    val name: String,
    val description: String,
    val body: String,
    /** Supporting files relative to the skill folder (SKILL.md excluded). */
    val files: List<RepoFile>,
)

class SkillImportException(message: String) : Exception(message)

object SkillParser {
    private const val MAX_FILE = 512 * 1024
    private const val MAX_TOTAL = 40L * 1024 * 1024
    private val nameRegex = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

    /** Splits `---` YAML frontmatter from the markdown body. Supports plain, quoted and block scalars. */
    fun frontmatter(content: String): Pair<Map<String, String>, String> {
        val text = content.removePrefix("\uFEFF").replace("\r\n", "\n")
        if (!text.startsWith("---\n")) return emptyMap<String, String>() to text
        val end = text.indexOf("\n---", 4)
        if (end == -1) return emptyMap<String, String>() to text
        val yaml = text.substring(4, end)
        val body = text.substring(end + 4).removePrefix("\n").trimStart('\n')
        val map = linkedMapOf<String, String>()
        val lines = yaml.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val match = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(line)
            if (match == null) { i++; continue }
            val key = match.groupValues[1]
            var value = match.groupValues[2].trim()
            if (value == ">" || value == "|" || value == ">-" || value == "|-" || value.isEmpty()) {
                val folded = value.startsWith(">")
                val block = mutableListOf<String>()
                i++
                while (i < lines.size && (lines[i].startsWith(" ") || lines[i].startsWith("\t") || lines[i].isBlank())) {
                    block += lines[i].trim()
                    i++
                }
                value = if (folded || value.isEmpty()) block.joinToString(" ").trim() else block.joinToString("\n").trim()
                map[key] = value
                continue
            }
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length - 1).replace("\\\"", "\"").replace("''", "'")
            }
            map[key] = value
            i++
        }
        return map to body
    }

    fun slug(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(64).ifEmpty { "skill" }

    /** Finds every SKILL.md among [files] and turns each folder into a skill. */
    fun parse(files: List<RepoFile>): List<ParsedSkill> {
        val skillFiles = files.filter { it.path == "SKILL.md" || it.path.endsWith("/SKILL.md") }
        if (skillFiles.isEmpty()) throw SkillImportException("No SKILL.md was found.")
        return skillFiles.map { skillFile ->
            val root = skillFile.path.removeSuffix("SKILL.md")
            val (meta, body) = frontmatter(skillFile.bytes.decodeToString())
            val folderName = root.trimEnd('/').substringAfterLast('/')
            val name = slug(meta["name"]?.takeIf { it.isNotBlank() } ?: folderName.ifEmpty { "skill" })
            if (!nameRegex.matches(name)) throw SkillImportException("Invalid skill name: $name")
            val description = meta["description"].orEmpty()
            val supporting = files
                .filter { it.path.startsWith(root) && it !== skillFile && !it.path.removePrefix(root).contains("SKILL.md") }
                .map { RepoFile(it.path.removePrefix(root), it.bytes) }
            ParsedSkill(name, description, body.trim(), supporting)
        }
    }

    /** Reads a zip, keeping only files that pass [keep]. Paths have the archive's top folder preserved. */
    fun readZip(input: InputStream, keep: (String) -> Boolean = { true }): List<RepoFile> {
        val files = mutableListOf<RepoFile>()
        var total = 0L
        val buffer = ByteArray(8192)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val path = entry.name.replace('\\', '/')
                if (entry.isDirectory || path.contains("../") || path.startsWith("/") || !keep(path)) continue
                val bytes = readBounded(zip, MAX_FILE, buffer) ?: continue
                total += bytes.size
                if (total > MAX_TOTAL) throw SkillImportException("The archive is too large.")
                files += RepoFile(path, bytes)
            }
        }
        return files
    }

    private fun readBounded(input: InputStream, maxBytes: Int, buffer: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** Removes the single top-level folder GitHub adds to source archives. */
    fun stripTopFolder(files: List<RepoFile>): List<RepoFile> {
        val tops = files.map { it.path.substringBefore('/') }.toSet()
        if (tops.size != 1 || files.any { !it.path.contains('/') }) return files
        val top = tops.first() + "/"
        return files.map { RepoFile(it.path.removePrefix(top), it.bytes) }
    }

    fun isText(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("md", "txt", "py", "js", "ts", "json", "yaml", "yml", "sh", "html", "css", "xml", "csv", "toml", "ini", "cfg", "xsd", "rb", "go", "java", "kt", "rs", "sql", "")
    }
}

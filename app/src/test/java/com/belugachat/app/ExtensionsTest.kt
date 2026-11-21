package com.belugachat.app

import com.belugachat.app.ext.McpClient
import com.belugachat.app.ext.RepoFile
import com.belugachat.app.ext.SkillImportException
import com.belugachat.app.ext.SkillParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionsTest {
    @Test fun frontmatterHandlesQuotesAndBlockScalars() {
        val (meta, body) = SkillParser.frontmatter(
            "---\nname: \"pdf-tools\"\ndescription: >\n  Work with PDFs\n  and forms.\nlicense: MIT\n---\n\n# Body\nText",
        )
        assertEquals("pdf-tools", meta["name"])
        assertEquals("Work with PDFs and forms.", meta["description"])
        assertEquals("# Body\nText", body)
    }

    @Test fun parsesSkillsFromZipWithSupportingFiles() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(path: String, text: String) { zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
            add("repo-main/skills/xlsx/SKILL.md", "---\nname: xlsx\ndescription: Spreadsheets\n---\nUse openpyxl.")
            add("repo-main/skills/xlsx/reference.md", "ref")
            add("repo-main/skills/docx/SKILL.md", "---\nname: docx\n---\nWord docs")
            add("repo-main/README.md", "readme")
        }
        val files = SkillParser.stripTopFolder(SkillParser.readZip(out.toByteArray().inputStream()))
        val skills = SkillParser.parse(files).associateBy { it.name }
        assertEquals(setOf("xlsx", "docx"), skills.keys)
        assertEquals("Spreadsheets", skills.getValue("xlsx").description)
        assertEquals(listOf("reference.md"), skills.getValue("xlsx").files.map { it.path })
        assertEquals("Use openpyxl.", skills.getValue("xlsx").body)
    }

    @Test fun readZipSkipsOversizedEntriesWithoutDroppingValidSkills() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("skill/SKILL.md"))
            zip.write("---\nname: safe\n---\nHello".toByteArray())
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("skill/huge.txt"))
            zip.write(ByteArray(600 * 1024) { 'a'.code.toByte() })
            zip.closeEntry()
        }
        val files = SkillParser.readZip(out.toByteArray().inputStream())
        assertEquals(listOf("skill/SKILL.md"), files.map { it.path })
    }

    @Test(expected = SkillImportException::class)
    fun rejectsArchivesWithoutSkill() {
        SkillParser.parse(listOf(RepoFile("README.md", "x".toByteArray())))
    }

    @Test fun mcpClientInitializesListsAndCallsOverJsonAndSse() = runBlocking {
        val server = MockWebServer()
        val seen = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                seen += (request.getHeader("mcp-session-id") ?: "-") + " " + body
                return when {
                    body.contains("\"initialize\"") -> MockResponse().setHeader("mcp-session-id", "s1")
                        .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{}}}""")
                    body.contains("notifications/initialized") -> MockResponse().setResponseCode(202)
                    body.contains("tools/list") -> MockResponse().setHeader("content-type", "text/event-stream")
                        .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo\",\"inputSchema\":{\"type\":\"object\"}}]}}\n\n")
                    body.contains("tools/call") -> MockResponse()
                        .setBody("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"pong"}],"isError":false}}""")
                    else -> MockResponse().setResponseCode(400)
                }
            }
        }
        server.start()
        val client = McpClient(server.url("/mcp").toString(), mapOf("Authorization" to "Bearer t"))
        val tools = client.listTools()
        assertEquals("echo", tools.single().name)
        val result = client.callTool("echo", JsonObject(emptyMap()))
        assertEquals("pong", result.text)
        assertFalse(result.isError)
        assertTrue(seen.drop(1).all { it.startsWith("s1 ") })
        assertEquals("Bearer t", server.takeRequest().getHeader("Authorization"))
        server.shutdown()
    }
}

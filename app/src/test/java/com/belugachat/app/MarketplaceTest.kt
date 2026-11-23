package com.belugachat.app

import com.belugachat.app.ext.MarketDetails
import com.belugachat.app.ext.MarketItem
import com.belugachat.app.ext.MarketSource
import com.belugachat.app.ext.Marketplace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MarketplaceTest {
    @Test fun extractsSignInFieldsFromMcpConfigs() {
        val config = Json.parseToJsonElement(
            """{"url":"https://mcp.example.com/mcp","headers":{
                "Authorization":"Bearer ${'$'}{TOKEN}",
                "X-API-Key":"static-value",
                "X-Optional":""
            }}""",
        ).jsonObject
        val specs = MarketDetails.headersFromConfigs(listOf(config)).associateBy { it.name }
        assertTrue(specs.getValue("Authorization").required)
        assertTrue(specs.getValue("Authorization").secret)
        assertEquals(null, specs.getValue("Authorization").value)
        assertFalse(specs.getValue("X-API-Key").required)
        assertTrue(specs.getValue("X-API-Key").secret)
        assertEquals("static-value", specs.getValue("X-API-Key").value)
        assertTrue(specs.getValue("X-Optional").required)
    }

    @Test fun detectsEveryPlaceholderStyle() {
        val config = Json.parseToJsonElement(
            """{"url":"https://mcp.example.com/mcp","headers":{
                "Plain":"Bearer ${'$'}TOKEN",
                "Braced":"${'$'}{TOKEN}",
                "Bare":"{TOKEN}",
                "Static":"Bearer abc"
            }}""",
        ).jsonObject
        val specs = MarketDetails.headersFromConfigs(listOf(config)).associateBy { it.name }
        assertTrue(specs.getValue("Plain").required)
        assertTrue(specs.getValue("Braced").required)
        assertTrue(specs.getValue("Bare").required)
        assertFalse(specs.getValue("Static").required)
        assertEquals("Bearer abc", specs.getValue("Static").value)
    }

    @Test fun mergesSuppliedHeadersOverPlaceholders() {
        val merged = MarketDetails.mergeHeaders(
            mapOf("Authorization" to "Bearer \${TOKEN}", "X-Fixed" to "abc", "X-Blank" to ""),
            mapOf("Authorization" to "Bearer real-token"),
        )
        assertEquals("Bearer real-token", merged["Authorization"])
        assertEquals("abc", merged["X-Fixed"])
        assertFalse(merged.containsKey("X-Blank"))
    }

    @Test fun detailEnrichesPluginWithReadmeComponentsAndAuth() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(Buffer().write(pluginZip()))
        }
        server.start()
        val base = server.url("/").toString().removeSuffix("/")
        val marketplace = Marketplace(OkHttpClient(), rawBase = base, codeloadBase = base)
        val item = MarketItem(
            source = MarketSource.PLUGINS,
            marketplace = "owner/repo",
            id = "demo",
            name = "Demo plugin",
            description = "A test plugin",
            version = "1.0.0",
            author = "Tester",
            category = "official",
            installable = true,
            raw = Json.parseToJsonElement("""{"name":"demo"}""").jsonObject,
        )

        val detail = marketplace.detail(item)
        assertEquals("# Demo plugin\n\nUseful instructions.", detail.readme)
        assertEquals(1, detail.components["skills"])
        assertEquals(1, detail.components["mcp_servers"])
        assertEquals("https://mcp.example.com/mcp", detail.remoteUrl)
        val auth = detail.headers.associateBy { it.name }
        assertTrue(auth.getValue("Authorization").required)
        assertTrue(auth.getValue("Authorization").secret)
        assertEquals("static-value", auth.getValue("X-API-Key").value)
        assertTrue(detail.needsSignIn)
        server.shutdown()
    }

    @Test fun featuredPluginsAreInstallableAndHaveUniqueIds() {
        val featured = Marketplace.featuredPlugins
        assertTrue(featured.isNotEmpty())
        assertEquals(featured.size, featured.map { it.lockId }.toSet().size)
        featured.forEach { item ->
            assertEquals(MarketSource.PLUGINS, item.source)
            assertTrue(item.installable)
            assertEquals(item.id, item.raw["name"]?.jsonPrimitive?.content)
        }
    }

    @Test fun featuredMcpServersAreKeylessInstallableAndIncludeExa() {
        val featured = Marketplace.featuredMcpServers
        assertTrue(featured.isNotEmpty())
        assertEquals(featured.size, featured.map { it.lockId }.toSet().size)
        featured.forEach { item ->
            assertEquals(MarketSource.MCP, item.source)
            assertTrue(item.installable)
            assertFalse(item.needsSignIn)
            assertTrue(item.remoteUrl!!.startsWith("https://"))
        }
        val exa = featured.firstOrNull { it.id == "ai.exa/exa" }
        assertEquals("Exa", exa?.name)
        assertEquals("https://mcp.exa.ai/mcp", exa?.remoteUrl)
    }

    @Test fun rankMcpItemsBoostsExactAndPrefixMatchesAboveSubstrings() {
        fun server(id: String, name: String, installable: Boolean = true) = MarketItem(
            source = MarketSource.MCP,
            marketplace = "registry",
            id = id,
            name = name,
            description = "",
            version = null,
            author = null,
            category = null,
            installable = installable,
            remoteUrl = if (installable) "https://example.com/mcp" else null,
        )
        val ranked = Marketplace.rankMcpItems(
            listOf(
                server("com.example/apexai", "Apex AI"),
                server("com.example/exa-local", "Exa", installable = false),
                server("com.example/exam-prep", "Exam Prep"),
                server("ai.exa/exa", "Exa"),
                server("com.example/exa-docs", "Exa Docs"),
            ),
            query = "exa",
        )
        assertEquals(
            listOf("ai.exa/exa", "com.example/exa-docs", "com.example/exam-prep", "com.example/apexai", "com.example/exa-local"),
            ranked.map { it.id },
        )
    }

    private fun pluginZip(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            add("repo-main/README.md", "# Demo plugin\n\nUseful instructions.")
            add(
                "repo-main/skills/demo/SKILL.md",
                "---\nname: demo\ndescription: Demo skill\n---\nFollow these steps.",
            )
            add(
                "repo-main/.mcp.json",
                """{"mcpServers":{"demo":{"url":"https://mcp.example.com/mcp","headers":{
                    "Authorization":"Bearer ${'$'}{TOKEN}","X-API-Key":"static-value"
                }}}}""",
            )
        }
        return out.toByteArray()
    }
}

package com.belugachat.app.tools

import com.belugachat.app.ext.McpClient
import com.belugachat.app.llm.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URLDecoder

class WebTools(
    private val http: OkHttpClient = Http.client,
    private val dnsResolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val addressFilter: (InetAddress) -> Boolean = ::isBlockedAddress,
    private val exaMcpUrl: String = DEFAULT_EXA_MCP_URL,
    private val duckDuckGoUrl: String = DEFAULT_DDG_URL,
) {
    private val exaClient: McpClient by lazy { McpClient(exaMcpUrl, emptyMap(), http) }

    private val fetchHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

    suspend fun search(query: String, maxResults: Int): String {
        val clamped = maxResults.coerceIn(1, 10)
        val exaResult = try {
            searchExa(query, clamped)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (exaResult != null) return exaResult
        return searchDuckDuckGo(query, clamped)
    }

    private suspend fun searchExa(query: String, maxResults: Int): String? {
        val result = exaClient.callTool(
            "web_search_exa",
            buildJsonObject {
                put("query", query)
                put("objective", "Find accurate, relevant, and up-to-date results for: $query")
                put("numResults", maxResults)
            },
        )
        if (result.isError) return null
        val text = result.text.trim()
        if (text.isBlank() || text == "(no output)") return null
        return text.take(MAX_CHARS)
    }

    private suspend fun searchDuckDuckGo(query: String, maxResults: Int): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(duckDuckGoUrl)
            .header("user-agent", userAgent)
            .post(FormBody.Builder().add("q", query).build())
            .build()
        val html = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Search failed: HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val results = Jsoup.parse(html).select(".result").mapNotNull { result ->
            val link = result.selectFirst(".result__a") ?: return@mapNotNull null
            val url = unwrap(link.attr("href"))
            if (url.isBlank() || url.contains("duckduckgo.com/y.js")) return@mapNotNull null
            Triple(link.text(), url, result.selectFirst(".result__snippet")?.text().orEmpty())
        }.take(maxResults)
        if (results.isEmpty()) "No results for \"$query\"."
        else results.mapIndexed { i, (title, url, snippet) -> "${i + 1}. $title\n$url\n$snippet" }.joinToString("\n\n")
    }

    private fun unwrap(href: String): String {
        val absolute = if (href.startsWith("//")) "https:$href" else href
        val url = absolute.toHttpUrlOrNull() ?: return absolute
        return url.queryParameter("uddg")?.let { URLDecoder.decode(it, "UTF-8") } ?: absolute
    }

    suspend fun fetch(target: String): String = withContext(Dispatchers.IO) {
        var currentUrl = parseAllowedUrl(target)
        var redirects = 0

        while (true) {
            val validatedAddresses = validateHost(currentUrl.host)
            val hopClient = fetchHttp.newBuilder()
                .dns(
                    object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            if (hostname.equals(currentUrl.host, ignoreCase = true)) {
                                validatedAddresses
                            } else {
                                validateHost(hostname)
                            }
                    },
                )
                .build()
            val request = Request.Builder().url(currentUrl).header("user-agent", userAgent).get().build()
            val nextUrl: HttpUrl? = hopClient.newCall(request).execute().use { response ->
                if (response.code in REDIRECT_CODES) {
                    val location = response.header("location")
                        ?: throw IllegalStateException("Redirect without Location header for $currentUrl")
                    if (++redirects > MAX_REDIRECTS) {
                        throw IllegalStateException("Too many redirects (max $MAX_REDIRECTS)")
                    }
                    val resolved = currentUrl.resolve(location)
                        ?: throw IllegalArgumentException("Invalid redirect URL: $location")
                    if (resolved.scheme != "http" && resolved.scheme != "https") {
                        throw IllegalArgumentException("Blocked redirect scheme: ${resolved.scheme}")
                    }
                    return@use resolved
                }
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} for $currentUrl")
                val type = response.header("content-type").orEmpty()
                val source = response.body?.source() ?: return@withContext "(empty response)"
                source.request(MAX_BYTES)
                val bytes = source.buffer.readByteArray(minOf(source.buffer.size, MAX_BYTES))
                val text = bytes.decodeToString()
                val readable = if (type.contains("html") || text.trimStart().startsWith("<")) {
                    val doc = Jsoup.parse(text, currentUrl.toString())
                    doc.select("script, style, noscript, svg, nav, footer, header, form").remove()
                    val title = doc.title()
                    val body = doc.body()?.wholeText()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString("\n").orEmpty()
                    if (title.isNotBlank()) "# $title\n\n$body" else body
                } else {
                    text
                }
                return@withContext readable.take(MAX_CHARS) + if (readable.length > MAX_CHARS) "\n\n[truncated]" else ""
            }
            if (nextUrl != null) currentUrl = nextUrl
        }
        @Suppress("UNREACHABLE_CODE")
        ""
    }

    private fun parseAllowedUrl(target: String): HttpUrl {
        val trimmed = target.trim()
        val url = trimmed.toHttpUrlOrNull() ?: throw IllegalArgumentException("Not a valid http(s) URL: $target")
        if (url.scheme != "http" && url.scheme != "https") {
            throw IllegalArgumentException("Only http(s) URLs are allowed: ${url.scheme}")
        }
        return url
    }

    private fun validateHost(host: String): List<InetAddress> {
        val addresses = dnsResolver(host)
        if (addresses.isEmpty()) throw IllegalArgumentException("Could not resolve host: $host")
        if (addresses.any(addressFilter)) {
            throw IllegalArgumentException("Access to private or local network address is blocked: $host")
        }
        return addresses
    }

    companion object {
        internal const val DEFAULT_EXA_MCP_URL = "https://mcp.exa.ai/mcp"
        internal const val DEFAULT_DDG_URL = "https://html.duckduckgo.com/html/"
        private const val MAX_BYTES = 3L * 1024 * 1024
        private const val MAX_CHARS = 30_000
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        internal fun isBlockedAddress(address: InetAddress): Boolean {
            if (
                address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress
            ) {
                return true
            }
            val bytes = address.address
            if (bytes.size == 4) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                val b2 = bytes[2].toInt() and 0xFF
                // Carrier-Grade NAT (100.64.0.0/10)
                if (b0 == 100 && (b1 and 0xC0) == 64) return true
                // IETF protocol assignments (192.0.0.0/24)
                if (b0 == 192 && b1 == 0 && b2 == 0) return true
                // Benchmarking network (198.18.0.0/15)
                if (b0 == 198 && (b1 and 0xFE) == 18) return true
                // Limited broadcast (255.255.255.255)
                if (bytes.all { (it.toInt() and 0xFF) == 255 }) return true
            }
            if (address is Inet6Address && bytes.size == 16) {
                // IPv6 Unique Local Address (fc00::/7)
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return true
                // IPv4-mapped IPv6 address (::ffff:0:0/96)
                val isV4Mapped = (0..9).all { bytes[it].toInt() == 0 } &&
                    bytes[10].toInt() == -1 && bytes[11].toInt() == -1
                if (isV4Mapped) {
                    val v4 = InetAddress.getByAddress(bytes.copyOfRange(12, 16))
                    return isBlockedAddress(v4)
                }
            }
            return false
        }
    }
}

package com.belugachat.app

import com.belugachat.app.update.AppUpdater
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppUpdaterTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun semVerComparisonHandlesNumericAndPrereleaseVersions() {
        assertTrue(AppUpdater.isNewer("1.0.1", "1.0.0"))
        assertFalse(AppUpdater.isNewer("1.0.0", "1.0.0"))
        assertFalse(AppUpdater.isNewer("1.0.0", "1.0.1"))
        assertTrue(AppUpdater.isNewer("1.10.0", "1.2.0"))
        assertFalse(AppUpdater.isNewer("1.2.0", "1.10.0"))
        assertTrue(AppUpdater.isNewer("1.2.0", "v1.2.0-beta"))
        assertTrue(AppUpdater.isNewer("v1.2.0-beta", "1.0.0"))
        assertFalse(AppUpdater.isNewer("v1.2.0-beta", "1.2.0"))
    }

    @Test
    fun parsesLatestReleaseAndSkipsDraftsPrereleasesAndMissingApks() {
        val valid = """
            {
              "tag_name": "v1.1.0",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/belugachat/belugachat-android/releases/tag/v1.1.0",
              "body": "## Changes\n- Better DeepSeek support",
              "assets": [
                {"name": "checksums.txt", "browser_download_url": "https://example.com/checksums.txt", "size": 128},
                {"name": "belugachat-1.1.0.apk", "browser_download_url": "https://example.com/belugachat-1.1.0.apk", "size": 12345678}
              ]
            }
        """.trimIndent()
        val parsed = AppUpdater.parseLatestRelease(valid)
        assertNotNull(parsed)
        assertEquals("1.1.0", parsed!!.version)
        assertEquals("v1.1.0", parsed.tagName)
        assertEquals("https://example.com/belugachat-1.1.0.apk", parsed.apkUrl)
        assertEquals(12345678L, parsed.apkSizeBytes)
        assertTrue(parsed.notes.contains("Better DeepSeek support"))

        assertNull(AppUpdater.parseLatestRelease("""{"tag_name":"v1.1.0","draft":true,"assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk","size":10}]}"""))
        assertNull(AppUpdater.parseLatestRelease("""{"tag_name":"v1.1.0","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk","size":10}]}"""))
        assertNull(AppUpdater.parseLatestRelease("""{"tag_name":"v1.1.0","draft":false,"prerelease":false,"assets":[{"name":"source.zip","browser_download_url":"https://x/s.zip","size":10}]}"""))
    }

    @Test
    fun parseLatestReleaseRequiresHttpsAndExtractsSha256Digest() {
        val insecure = """
            {
              "tag_name": "v1.1.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name": "app.apk", "browser_download_url": "http://example.com/app.apk", "size": 100}
              ]
            }
        """.trimIndent()
        assertNull(AppUpdater.parseLatestRelease(insecure, requireHttps = true))
        assertNotNull(AppUpdater.parseLatestRelease(insecure, requireHttps = false))

        val hash = "a".repeat(64)
        val withDigest = """
            {
              "tag_name": "v1.1.0",
              "draft": false,
              "prerelease": false,
              "assets": [
                {"name": "app.apk", "browser_download_url": "https://github.com/belugachat/belugachat-android/releases/download/v1.1.0/app.apk", "size": 100, "digest": "sha256:$hash"}
              ]
            }
        """.trimIndent()
        val parsed = AppUpdater.parseLatestRelease(withDigest)
        assertNotNull(parsed)
        assertEquals(hash, parsed!!.apkSha256)
    }

    @Test
    fun fetchesLatestReleaseFromMockServerWithExpectedHeaders() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "tag_name": "v1.2.0",
                  "draft": false,
                  "prerelease": false,
                  "html_url": "https://github.com/belugachat/belugachat-android/releases/tag/v1.2.0",
                  "body": "",
                  "assets": [
                    {"name": "belugachat-v1.2.0.apk", "browser_download_url": "${server.url("/dl/app.apk")}", "size": 5242880}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val updater = AppUpdater(
            context = null,
            settings = null,
            http = OkHttpClient(),
            apiBaseUrl = server.url("/").toString(),
            repo = "belugachat/belugachat-android",
            currentVersion = "1.0.0",
            enabled = true,
        )
        val release = updater.fetchLatestRelease()
        assertNotNull(release)
        assertEquals("1.2.0", release!!.version)
        assertEquals("Bug fixes and improvements.", release.notes)

        val request = server.takeRequest()
        assertEquals("/repos/belugachat/belugachat-android/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("BelugaChat-Android/1.0.0", request.getHeader("User-Agent"))
    }

    @Test
    fun fetchReturnsNullOn404() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val updater = AppUpdater(
            context = null,
            settings = null,
            http = OkHttpClient(),
            apiBaseUrl = server.url("/").toString(),
            repo = "belugachat/belugachat-android",
            currentVersion = "1.0.0",
            enabled = true,
        )
        assertNull(updater.fetchLatestRelease())
    }

    @Test
    fun autoCheckRespects24HourThrottleAndEnabledFlag() {
        val day = AppUpdater.CHECK_INTERVAL_MS
        assertTrue(AppUpdater.shouldAutoCheck(lastCheckMs = 0L, nowMs = 1_000_000L, enabled = true))
        assertFalse(AppUpdater.shouldAutoCheck(lastCheckMs = 1_000_000L, nowMs = 1_000_000L + day - 1L, enabled = true))
        assertTrue(AppUpdater.shouldAutoCheck(lastCheckMs = 1_000_000L, nowMs = 1_000_000L + day, enabled = true))
        assertFalse(AppUpdater.shouldAutoCheck(lastCheckMs = 0L, nowMs = 1_000_000L, enabled = false))
    }
}

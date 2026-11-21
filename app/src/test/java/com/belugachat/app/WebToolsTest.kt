package com.belugachat.app

import com.belugachat.app.tools.WebTools
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class WebToolsTest {
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
    fun blocksPrivateLoopbackLinkLocalAndUlaAddresses() {
        val blocked = listOf(
            "127.0.0.1",
            "127.0.1.1",
            "10.0.0.1",
            "100.64.0.1",
            "100.127.255.254",
            "172.16.0.1",
            "192.168.1.1",
            "198.18.0.1",
            "169.254.169.254",
            "0.0.0.0",
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "::",
            "fe80::1",
            "fc00::1",
            "fd12:3456:789a::1",
            "::ffff:127.0.0.1",
            "::ffff:10.0.0.5",
            "::ffff:100.64.0.1",
        )
        for (ip in blocked) {
            assertTrue("Expected $ip to be blocked", WebTools.isBlockedAddress(InetAddress.getByName(ip)))
        }
        assertFalse(WebTools.isBlockedAddress(InetAddress.getByName("93.184.216.34")))
        assertFalse(WebTools.isBlockedAddress(InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946")))
    }

    @Test
    fun fetchRejectsNonHttpSchemesAndLoopbackAddressesBeforeConnecting() = runBlocking {
        val tools = WebTools(OkHttpClient())
        for (target in listOf("file:///etc/passwd", "ftp://example.com/file")) {
            try {
                tools.fetch(target)
                fail("Expected $target to be rejected")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }

        val loopbackTools = WebTools(OkHttpClient(), dnsResolver = { listOf(InetAddress.getByName("127.0.0.1")) })
        try {
            loopbackTools.fetch("https://example.com/secret")
            fail("Expected loopback IP resolution to be blocked")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blocked"))
        }
    }

    @Test
    fun fetchBlocksRedirectToPrivateNetworkAddress() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "http://internal.local/admin"),
        )
        val publicIp = InetAddress.getByAddress("public.example", byteArrayOf(127, 0, 0, 1))
        val privateIp = InetAddress.getByAddress("internal.local", byteArrayOf(127, 0, 0, 1))
        val tools = WebTools(
            http = OkHttpClient(),
            dnsResolver = { host ->
                when (host) {
                    "localhost", "127.0.0.1" -> listOf(publicIp)
                    "internal.local" -> listOf(privateIp)
                    else -> listOf(publicIp)
                }
            },
            addressFilter = { addr -> addr.hostName != "public.example" && WebTools.isBlockedAddress(addr) },
        )
        try {
            tools.fetch(server.url("/start").toString())
            fail("Expected redirect to private address to be blocked")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("blocked"))
        }
    }

    @Test
    fun fetchPinsResolvedAddressesToPreventDnsRebinding() = runBlocking {
        server.enqueue(MockResponse().setBody("hello ok"))
        var lookups = 0
        val firstAddr = InetAddress.getByAddress("pinned.test", byteArrayOf(127, 0, 0, 1))
        val rebindAddr = InetAddress.getByAddress("rebind.test", byteArrayOf(127, 0, 0, 1))
        val tools = WebTools(
            http = OkHttpClient(),
            dnsResolver = {
                lookups += 1
                if (lookups == 1) listOf(firstAddr) else listOf(rebindAddr)
            },
            addressFilter = { addr -> addr.hostName != "pinned.test" && WebTools.isBlockedAddress(addr) },
        )
        val result = tools.fetch(server.url("/hello").toString())
        assertEquals("hello ok", result)
        assertEquals(1, lookups)
    }
}

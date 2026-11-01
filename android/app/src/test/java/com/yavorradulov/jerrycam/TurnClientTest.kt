package com.yavorradulov.jerrycam

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnClientTest {
    // Shape from https://developers.cloudflare.com/realtime/turn/generate-credentials/
    private val response = """
        {"iceServers": [
          {"urls": ["stun:stun.cloudflare.com:3478", "stun:stun.cloudflare.com:53"]},
          {"urls": ["turn:turn.cloudflare.com:3478?transport=udp", "turn:turn.cloudflare.com:53?transport=udp",
                    "turn:turn.cloudflare.com:3478?transport=tcp", "turn:turn.cloudflare.com:80?transport=tcp",
                    "turns:turn.cloudflare.com:5349?transport=tcp", "turns:turn.cloudflare.com:443?transport=tcp"],
           "username": "user-1", "credential": "secret-1"}
        ]}
    """.trimIndent()

    @Test
    fun parseDropsPort53AndKeepsCredentials() {
        val servers = parseIceServers(response)
        assertEquals(2, servers.size)
        assertFalse(servers.flatMap { it.urls }.any { Regex(":53(\\?|$)").containsMatchIn(it) })
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), servers[0].urls)
        assertEquals(5, servers[1].urls.size)
        assertEquals("user-1", servers[1].username)
        assertEquals("secret-1", servers[1].credential)
    }

    @Test
    fun fetchSendsBearerTokenAndTtl() = runTest {
        var seenUrl = ""
        var seenHeaders = emptyMap<String, String>()
        var seenBody = ""
        val client = TurnClient("key", "tok") { url, headers, body ->
            seenUrl = url; seenHeaders = headers; seenBody = body
            response
        }
        assertEquals(parseIceServers(response), client.fetchIceServers())
        assertEquals("https://rtc.live.cloudflare.com/v1/turn/keys/key/credentials/generate-ice-servers", seenUrl)
        assertEquals("Bearer tok", seenHeaders["Authorization"])
        assertEquals("application/json", seenHeaders["Content-Type"])
        assertTrue(seenBody, seenBody.replace(" ", "").contains("\"ttl\":86400"))
    }

    @Test
    fun fetchIceServersReturnsStunFallbackOnHttpError() = runTest {
        val client = TurnClient("key", "tok") { _, _, _ -> throw IOException("HTTP 401") }
        assertEquals(STUN_FALLBACK, client.fetchIceServers())
    }

    @Test
    fun fetchIceServersReturnsStunFallbackOnMalformedJson() = runTest {
        val client = TurnClient("key", "tok") { _, _, _ -> "not json" }
        assertEquals(STUN_FALLBACK, client.fetchIceServers())
    }

    @Test
    fun blankKeyReturnsStunFallbackWithoutRequest() = runTest {
        var requests = 0
        val client = TurnClient("", "") { _, _, _ -> requests++; response }
        assertEquals(STUN_FALLBACK, client.fetchIceServers())
        assertEquals(0, requests)
        assertEquals(listOf(IceServerSpec(listOf("stun:stun.cloudflare.com:3478"))), STUN_FALLBACK)
    }
}

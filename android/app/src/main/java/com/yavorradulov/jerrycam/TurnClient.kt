package com.yavorradulov.jerrycam

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Used when there is no TURN key or Cloudflare can't be reached: direct connections only. */
val STUN_FALLBACK = listOf(IceServerSpec(listOf("stun:stun.cloudflare.com:3478")))

// Port 53 is blocked by browsers, so the iPhone would only wait on it until it times out.
private val PORT_53 = Regex(":53(\\?|$)")

fun parseIceServers(json: String): List<IceServerSpec> {
    val array = JSONObject(json).getJSONArray("iceServers")
    return (0 until array.length()).mapNotNull { i ->
        val server = array.getJSONObject(i)
        val urls = when (val raw = server.get("urls")) {
            is JSONArray -> (0 until raw.length()).map { raw.getString(it) }
            else -> listOf(raw.toString())
        }.filterNot { PORT_53.containsMatchIn(it) }
        if (urls.isEmpty()) return@mapNotNull null
        IceServerSpec(urls, server.optString("username").ifEmpty { null }, server.optString("credential").ifEmpty { null })
    }
}

/** Fetches short-lived Cloudflare TURN credentials. Never throws: falls back to STUN only. */
class TurnClient(
    private val keyId: String,
    private val apiToken: String,
    private val post: suspend (url: String, headers: Map<String, String>, body: String) -> String = ::httpPost,
) {
    suspend fun fetchIceServers(): List<IceServerSpec> {
        if (keyId.isBlank() || apiToken.isBlank()) return STUN_FALLBACK
        return try {
            val response = post(
                "https://rtc.live.cloudflare.com/v1/turn/keys/$keyId/credentials/generate-ice-servers",
                mapOf("Authorization" to "Bearer $apiToken", "Content-Type" to "application/json"),
                """{"ttl":$CREDENTIAL_TTL_SECONDS}""",
            )
            parseIceServers(response).ifEmpty { STUN_FALLBACK }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "TURN credentials unavailable, using STUN only: $e")
            STUN_FALLBACK
        }
    }

    private companion object {
        const val CREDENTIAL_TTL_SECONDS = 86_400
    }
}

private suspend fun httpPost(url: String, headers: Map<String, String>, body: String): String =
    withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.doOutput = true
            headers.forEach(connection::setRequestProperty)
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

package com.pratham.cloudstorage

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.util.flattenEntries
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Base64

private val relayGson = Gson()

class RelayTunnelClient(
    relayBaseUrl: String,
    private val shareCode: String
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relayWebSocketUrl = relayBaseUrl.toWebSocketUrl(shareCode)

    private val relayClient = HttpClient(OkHttp) {
        install(WebSockets)
        expectSuccess = false
    }

    private val localNodeClient = HttpClient(OkHttp) {
        expectSuccess = false
    }

    fun start() {
        if (relayWebSocketUrl.isBlank()) {
            return
        }

        scope.launch {
            while (isActive) {
                try {
                    relayClient.webSocket(urlString = relayWebSocketUrl) {
                        Log.i(TAG, "Relay tunnel connected for $shareCode")
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                frame.readText().toEnvelopeOrNull()?.let { envelope ->
                                    if (envelope.type == "request") {
                                        val response = forwardToLocalNode(envelope)
                                        outgoing.send(Frame.Text(relayGson.toJson(response)))
                                    }
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Relay tunnel disconnected for $shareCode: ${error.message}")
                }

                if (isActive) {
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
        relayClient.close()
        localNodeClient.close()
    }

    private suspend fun forwardToLocalNode(request: RelayEnvelope): RelayEnvelope {
        val targetPath = request.path?.takeIf { it.isNotBlank() } ?: "/"
        val querySuffix = request.query?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        val targetUrl = "http://127.0.0.1:$DEFAULT_PORT$targetPath$querySuffix"

        return try {
            val statement = localNodeClient.prepareRequest(targetUrl) {
                this.method = HttpMethod.parse(request.method ?: HttpMethod.Get.value)
                request.headers.orEmpty().forEach { (key, value) ->
                    if (!isHopByHopHeader(key) && !key.equals(HttpHeaders.Host, ignoreCase = true)) {
                        header(key, value)
                    }
                }

                request.bodyBase64.decodeBase64()?.let { body ->
                    if (body.isNotEmpty()) {
                        setBody(body)
                    }
                }
            }.execute()

            val responseBody = statement.bodyAsChannel().readRemaining().readBytes()
            RelayEnvelope(
                type = "response",
                requestId = request.requestId,
                status = statement.status.value,
                headers = statement.headers.flattenEntries()
                    .filterNot { (key, _) ->
                        isHopByHopHeader(key) || key.equals(HttpHeaders.ContentLength, ignoreCase = true)
                    }
                    .associate { (key, value) -> key to value },
                bodyBase64 = responseBody.encodeBase64()
            )
        } catch (error: Exception) {
            Log.e(TAG, "Local node proxy failed: ${error.message}", error)
            RelayEnvelope(
                type = "response",
                requestId = request.requestId,
                status = 502,
                headers = mapOf(HttpHeaders.ContentType to "text/plain; charset=utf-8"),
                bodyBase64 = "Relay tunnel could not reach the local node.".encodeToByteArray().encodeBase64(),
                error = error.message
            )
        }
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val TAG = "RelayTunnelClient"
    }
}

private data class RelayEnvelope(
    val type: String,
    val requestId: String? = null,
    val method: String? = null,
    val path: String? = null,
    val query: String? = null,
    val headers: Map<String, String>? = null,
    val bodyBase64: String? = null,
    val status: Int? = null,
    val error: String? = null
)

private fun String.toEnvelopeOrNull(): RelayEnvelope? {
    return try {
        relayGson.fromJson(this, RelayEnvelope::class.java)
    } catch (_: JsonSyntaxException) {
        null
    }
}

private fun String.toWebSocketUrl(shareCode: String): String {
    val normalized = normalizeRelayBaseUrl(this)
    if (normalized.isBlank()) {
        return ""
    }

    val webSocketBase = when {
        normalized.startsWith("https://") -> normalized.replaceFirst("https://", "wss://")
        normalized.startsWith("http://") -> normalized.replaceFirst("http://", "ws://")
        else -> "wss://$normalized"
    }

    return "$webSocketBase/agent/connect?shareCode=$shareCode"
}

private fun String?.decodeBase64(): ByteArray? {
    if (this.isNullOrBlank()) {
        return null
    }
    return Base64.getDecoder().decode(this)
}

private fun ByteArray?.encodeBase64(): String? {
    if (this == null || isEmpty()) {
        return null
    }
    return Base64.getEncoder().encodeToString(this)
}

private fun isHopByHopHeader(name: String): Boolean {
    return name.equals(HttpHeaders.Connection, ignoreCase = true) ||
        name.equals("Keep-Alive", ignoreCase = true) ||
        name.equals(HttpHeaders.ProxyAuthenticate, ignoreCase = true) ||
        name.equals(HttpHeaders.ProxyAuthorization, ignoreCase = true) ||
        name.equals(HttpHeaders.TE, ignoreCase = true) ||
        name.equals(HttpHeaders.Trailer, ignoreCase = true) ||
        name.equals(HttpHeaders.TransferEncoding, ignoreCase = true) ||
        name.equals(HttpHeaders.Upgrade, ignoreCase = true)
}

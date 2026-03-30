package com.pratham.cloudstorage

import android.content.Context
import android.net.Uri
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
import java.util.concurrent.TimeUnit

// ──────────────────────────────────────────────────────────────────────────────
// Relay Tunnel Client — signaling + relay API fallback
//
// The primary happy path is WebRTC. When the browser cannot reach the Android
// node directly yet, the relay can still proxy /api/* requests over this same
// WebSocket and the client forwards them to the local Ktor node.
// ──────────────────────────────────────────────────────────────────────────────

enum class TunnelStatus {
    Offline,
    Connecting,
    Connected,
    Error
}

private val relayGson = Gson()

class RelayTunnelClient(
    private val context: Context,
    val relayBaseUrl: String,
    val shareCode: String,
    private val rootUri: Uri,
    private val onStatusChange: (TunnelStatus) -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relayWebSocketUrl = relayBaseUrl.toWebSocketUrl(shareCode.uppercase().trim())

    private val relayClient = HttpClient(OkHttp) {
        install(WebSockets) {
            maxFrameSize = 512 * 1024L
        }
        engine {
            config {
                pingInterval(15, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        expectSuccess = false
    }

    private val localNodeClient = HttpClient(OkHttp) {
        expectSuccess = false
    }

    private var webRTCPeer: WebRTCPeer? = null

    fun start() {
        if (relayWebSocketUrl.isBlank()) return

        scope.launch {
            onStatusChange(TunnelStatus.Connecting)
            var backoffMs = 1_000L

            while (isActive) {
                try {
                    relayClient.webSocket(urlString = relayWebSocketUrl) {
                        Log.i(TAG, "Relay signaling connected for $shareCode")
                        onStatusChange(TunnelStatus.Connected)
                        backoffMs = 1_000L

                        val peer = WebRTCPeer(context, rootUri) { signalJson ->
                            outgoing.send(Frame.Text(signalJson))
                        }
                        webRTCPeer = peer

                        outgoing.send(Frame.Text("""{"type":"register","nodeId":"$shareCode"}"""))

                        val keepaliveJob = launch {
                            while (isActive) {
                                delay(10_000)
                                try {
                                    outgoing.send(Frame.Text("""{"type":"heartbeat"}"""))
                                } catch (_: Exception) {
                                    break
                                }
                            }
                        }

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    val envelope = text.toEnvelopeOrNull()
                                    when (envelope?.type) {
                                        "signal" -> peer.handleSignalingMessage(text)
                                        "request" -> {
                                            val response = forwardToLocalNode(envelope)
                                            outgoing.send(Frame.Text(relayGson.toJson(response)))
                                        }
                                        "connected" -> Log.i(TAG, "Relay confirmed registration for $shareCode")
                                        else -> Log.d(TAG, "Unknown relay frame type: ${envelope?.type}")
                                    }
                                }
                            }
                        } finally {
                            keepaliveJob.cancel()
                            peer.destroy()
                            webRTCPeer = null
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Relay signaling disconnected for $shareCode: ${error.message}")
                    onStatusChange(TunnelStatus.Error)
                }

                if (isActive) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                    onStatusChange(TunnelStatus.Connecting)
                }
            }
        }
    }

    fun stop() {
        onStatusChange(TunnelStatus.Offline)
        webRTCPeer?.destroy()
        webRTCPeer = null
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
                method = HttpMethod.parse(request.method ?: HttpMethod.Get.value)
                request.headers.orEmpty().forEach { (key, value) ->
                    if (!isHopByHopHeader(key) &&
                        !key.equals(HttpHeaders.Host, ignoreCase = true) &&
                        !key.equals("X-Node-Id", ignoreCase = true)
                    ) {
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
                headers = mapOf(HttpHeaders.ContentType to "application/json; charset=utf-8"),
                bodyBase64 = """{"error":"local_node_unreachable"}""".encodeToByteArray().encodeBase64(),
                error = error.message
            )
        }
    }

    companion object {
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
    if (normalized.isBlank()) return ""

    val webSocketBase = when {
        normalized.startsWith("https://") -> normalized.replaceFirst("https://", "wss://")
        normalized.startsWith("http://") -> normalized.replaceFirst("http://", "ws://")
        else -> "wss://$normalized"
    }

    return "$webSocketBase/agent/connect?shareCode=$shareCode"
}

private fun String?.decodeBase64(): ByteArray? {
    if (this.isNullOrBlank()) return null
    return Base64.getDecoder().decode(this)
}

private fun ByteArray?.encodeBase64(): String? {
    if (this == null || isEmpty()) return null
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

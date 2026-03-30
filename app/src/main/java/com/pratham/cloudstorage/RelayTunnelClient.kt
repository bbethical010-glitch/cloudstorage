package com.pratham.cloudstorage

import android.util.Log
import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
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
// Relay Tunnel Client — Signaling-Only
//
// This client maintains a persistent WebSocket to the relay server for:
//   1. Node registration (advertising the share code)
//   2. WebRTC signaling (forwarding SDP offers/answers and ICE candidates)
//
// NO file data passes through this client. All file transfers happen
// directly between the browser and the WebRTCPeer via RTCDataChannel.
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
            maxFrameSize = 16 * 1024 * 1024L
        }
        engine {
            config {
                pingInterval(30, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        expectSuccess = false
    }

    private val localHttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        engine {
            config {
                retryOnConnectionFailure(true)
            }
        }
    }

    // The WebRTC peer that handles P2P connections with browsers
    private var webRTCPeer: WebRTCPeer? = null

    fun start() {
        if (relayWebSocketUrl.isBlank()) return

        scope.launch {
            onStatusChange(TunnelStatus.Connecting)
            var backoffMs = 1000L

            while (isActive) {
                try {
                    relayClient.webSocket(urlString = relayWebSocketUrl) {
                        Log.i(TAG, "Relay signaling connected for $shareCode")
                        onStatusChange(TunnelStatus.Connected)
                        backoffMs = 1000L

                        // Initialize WebRTC peer with a callback that sends signaling
                        // messages back through this WebSocket connection
                        val peer = WebRTCPeer(context, rootUri) { signalJson ->
                            outgoing.send(Frame.Text(signalJson))
                        }
                        webRTCPeer = peer

                        // Send registration handshake
                        outgoing.send(Frame.Text("""{"type":"register","nodeId":"$shareCode"}"""))

                        // Backup keep-alive heartbeat loop (15s)
                        // Using text frames is more robust across some proxies than pings
                        val keepaliveJob = launch {
                            while (isActive) {
                                delay(15_000)
                                try { outgoing.send(Frame.Text("""{"type":"heartbeat"}""")) }
                                catch (e: Exception) { break }
                            }
                        }

                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    val msg = text.toSignalOrNull() ?: continue

                                    when (msg.type) {
                                        // Relay forwards WebRTC signaling from browser → us
                                        "signal" -> {
                                            peer.handleSignalingMessage(text)
                                        }
                                        "connected" -> {
                                            Log.i(TAG, "Relay confirmed registration for $shareCode")
                                        }
                                        "http_request" -> {
                                            launch {
                                                outgoing.send(Frame.Text(forwardHttpRequestToLocalServer(msg)))
                                            }
                                        }
                                        else -> {
                                            Log.d(TAG, "Unknown message type: ${msg.type}")
                                        }
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
                    backoffMs = (backoffMs * 2).coerceAtMost(30000L)
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
        localHttpClient.close()
        relayClient.close()
    }

    private suspend fun forwardHttpRequestToLocalServer(message: SignalMessage): String {
        val requestId = message.requestId ?: return relayGson.toJson(
            mapOf("type" to "http_response", "requestId" to "", "status" to 400, "headers" to emptyMap<String, String>(), "body" to null)
        )
        val method = message.method ?: "GET"
        val path = message.path ?: "/api/status"
        val query = message.query.orEmpty()
        val querySuffix = if (query.isNotBlank()) "?$query" else ""
        val url = "http://127.0.0.1:$LOCAL_SERVER_PORT$path$querySuffix"
        val headers = message.headers.orEmpty()

        return try {
            localHttpClient.prepareRequest(url) {
                this.method = HttpMethod.parse(method)
                headers.forEach { (key, value) ->
                    if (!key.equals("Host", true) && !key.equals("Content-Length", true)) {
                        header(key, value)
                    }
                }
                val bodyB64 = message.body
                if (!bodyB64.isNullOrBlank()) {
                    val bodyBytes = Base64.getDecoder().decode(bodyB64)
                    val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                    if (contentType != null) {
                        setBody(ByteArrayContent(bodyBytes, ContentType.parse(contentType)))
                    } else {
                        setBody(bodyBytes)
                    }
                }
            }.execute { statement ->
                val responseHeaders = mutableMapOf<String, String>()
                statement.headers.forEach { name, values ->
                    if (!name.equals("Transfer-Encoding", true) && !name.equals("Connection", true)) {
                        responseHeaders[name] = values.joinToString(", ")
                    }
                }

                val bodyBytes = statement.bodyAsChannel().readRemaining().readBytes()
                relayGson.toJson(
                    mapOf(
                        "type" to "http_response",
                        "requestId" to requestId,
                        "status" to statement.status.value,
                        "headers" to responseHeaders,
                        "body" to if (bodyBytes.isNotEmpty()) Base64.getEncoder().encodeToString(bodyBytes) else null
                    )
                )
            }
        } catch (error: Exception) {
            relayGson.toJson(
                mapOf(
                    "type" to "http_response",
                    "requestId" to requestId,
                    "status" to 502,
                    "headers" to mapOf("Content-Type" to "application/json"),
                    "body" to Base64.getEncoder().encodeToString(
                        """{"error":"${error.message ?: "relay_proxy_error"}"}""".toByteArray()
                    )
                )
            )
        }
    }

    companion object {
        private const val TAG = "RelayTunnelClient"
        private const val LOCAL_SERVER_PORT = 8970
    }
}

// ── Signaling message format ─────────────────────────────────────────────────

private data class SignalMessage(
    val type: String,
    val browserId: String? = null,
    val signal: Map<String, Any>? = null,
    val shareCode: String? = null,
    val requestId: String? = null,
    val method: String? = null,
    val path: String? = null,
    val query: String? = null,
    val headers: Map<String, String>? = null,
    val body: String? = null
)

private fun String.toSignalOrNull(): SignalMessage? {
    return try {
        relayGson.fromJson(this, SignalMessage::class.java)
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

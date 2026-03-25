package com.pratham.cloudstorage

import android.util.Log
import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
            maxFrameSize = 512 * 1024L // Signaling messages are tiny
        }
        engine {
            config {
                pingInterval(30, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        expectSuccess = false
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
                                        else -> {
                                            Log.d(TAG, "Unknown message type: ${msg.type}")
                                        }
                                    }
                                }
                            }
                        } finally {
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
        relayClient.close()
    }

    companion object {
        private const val TAG = "RelayTunnelClient"
    }
}

// ── Signaling message format ─────────────────────────────────────────────────

private data class SignalMessage(
    val type: String,
    val browserId: String? = null,
    val signal: Map<String, Any>? = null,
    val shareCode: String? = null
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

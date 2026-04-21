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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
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
            maxFrameSize = 8 * 1024 * 1024L
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
    private val outgoingMutex = Mutex()
    private val streamSessions = ConcurrentHashMap<String, StreamingUploadProxySession>()

    private var webRTCPeer: WebRTCPeer? = null

    fun start() {
        if (relayWebSocketUrl.isBlank()) return

        scope.launch {
            onStatusChange(TunnelStatus.Connecting)
            var backoffMs = 1_000L

            while (isActive) {
                try {
                    relayClient.webSocket(urlString = relayWebSocketUrl) {
                        val sendTextSafely: suspend (String) -> Unit = { payload ->
                            outgoingMutex.withLock {
                                outgoing.send(Frame.Text(payload))
                            }
                        }

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
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        val envelope = text.toEnvelopeOrNull()
                                        when (envelope?.type) {
                                            "signal" -> peer.handleSignalingMessage(text)
                                            "request" -> {
                                                val response = forwardToLocalNode(envelope)
                                                sendTextSafely(relayGson.toJson(response))
                                            }
                                            "stream-request-start" -> {
                                                val requestId = envelope.requestId ?: continue
                                                initializeRelaySession(requestId, envelope, { Frame.Text(it) }, { Frame.Binary(true, it) })
                                            }
                                            "stream-request-end" -> {
                                                val requestId = envelope.requestId ?: continue
                                                streamSessions.remove(requestId)?.finish()
                                            }
                                            "connected" -> Log.i(TAG, "Relay confirmed registration for $shareCode")
                                            else -> Log.d(TAG, "Unknown relay frame type: ${envelope?.type}")
                                        }
                                    }
                                    is Frame.Binary -> {
                                        val payload = frame.data
                                        if (payload.size < 37) continue
                                        
                                        val requestId = payload.copyOfRange(0, 36).decodeToString().trim()
                                        val type = payload[36].toInt()
                                        val chunk = payload.copyOfRange(37, payload.size)
                                        
                                        if (type == 1) { // MSG_TYPE_START
                                            try {
                                                val meta = relayGson.fromJson(chunk.decodeToString(), RelayEnvelope::class.java)
                                                initializeRelaySession(requestId, meta, { Frame.Text(it) }, { Frame.Binary(true, it) })
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Failed to parse binary START metadata", e)
                                            }
                                        } else {
                                            streamSessions[requestId]?.handlePacket(type, chunk)
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        } finally {
                            keepaliveJob.cancel()
                            streamSessions.values.forEach { it.cancel() }
                            streamSessions.clear()
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

    private suspend fun io.ktor.websocket.WebSocketSession.initializeRelaySession(
        requestId: String,
        envelope: RelayEnvelope,
        textWrapper: (String) -> Frame,
        binaryWrapper: (ByteArray) -> Frame
    ) {
        val targetPath = envelope.path ?: "/api/upload/archive"
        val query = envelope.query.orEmpty()
        val method = envelope.method ?: HttpMethod.Post.value
        val headers = envelope.headers.orEmpty()
        val contentLength = envelope.contentLength ?: envelope.size ?: -1L

        streamSessions[requestId]?.cancel()
        streamSessions[requestId] = StreamingUploadProxySession(
            scope = scope,
            localClient = localNodeClient,
            method = method,
            path = targetPath,
            query = query,
            headers = headers,
            contentLength = contentLength,
            onResponse = { _, _, _ -> streamSessions.remove(requestId) },
            onFailure = { streamSessions.remove(requestId) },
            onSignal = { ackType, ackPayload ->
                val idBytes = requestId.padEnd(36, ' ').toByteArray().sliceArray(0..35)
                val packet = ByteArray(36 + 1 + ackPayload.size)
                System.arraycopy(idBytes, 0, packet, 0, 36)
                packet[36] = ackType.toByte()
                System.arraycopy(ackPayload, 0, packet, 37, ackPayload.size)
                
                scope.launch {
                    outgoingMutex.withLock {
                        outgoing.send(binaryWrapper(packet))
                    }
                }
            }
        )
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
    val contentLength: Long? = null,
    val size: Long? = null, // Alias for contentLength
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

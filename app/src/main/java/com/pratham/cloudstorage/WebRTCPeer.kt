package com.pratham.cloudstorage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import org.webrtc.*
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────────────────────────────
// WebRTC Peer — Android-side P2P connection handler
//
// This class manages PeerConnections initiated by browser clients. When a
// browser sends an SDP offer via the relay signaling channel, this peer
// creates an answer, exchanges ICE candidates, and opens a DataChannel.
//
// All API requests (file listing, upload, download) flow over the DataChannel
// using a simple JSON + binary chunking protocol. File bytes are read from
// the Android ContentResolver and sent in 64KB chunks to prevent OOM.
//
// NAT Traversal: Uses Google's public STUN server (stun.l.google.com:19302)
// to discover the device's public IP and port mapping. This enables direct
// connectivity even when the Android device is behind a home router.
// ──────────────────────────────────────────────────────────────────────────────

private const val TAG = "WebRTCPeer"
private const val CHUNK_SIZE = 64 * 1024 // 64KB chunks prevent DataChannel memory overflow

class WebRTCPeer(
    private val context: Context,
    private val rootUri: Uri,
    private val onSignal: suspend (String) -> Unit // Callback to send signaling messages via relay WS
) {
    private val gson = Gson()
    private val factory: PeerConnectionFactory
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val dataChannels = ConcurrentHashMap<String, DataChannel>()

    // HTTP client to forward DataChannel requests to the local Ktor server
    private val localClient = HttpClient(OkHttp) { expectSuccess = false }

    // ICE servers for NAT traversal — STUN helps discover public IP/port
    // behind NAT routers without needing a TURN server for most connections
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    init {
        // Initialize the WebRTC library — must happen once per process
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setFieldTrials("")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()

        Log.i(TAG, "WebRTC PeerConnectionFactory initialized")
    }

    /**
     * Handle an incoming signaling message from the relay WebSocket.
     * Expected format: {"type":"signal","browserId":"...","signal":{...}}
     */
    fun handleSignalingMessage(json: String) {
        try {
            val msg = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return
            val browserId = msg["browserId"] as? String ?: return
            val signalMap = msg["signal"] as? Map<*, *> ?: return
            val signalType = signalMap["type"] as? String ?: return

            when (signalType) {
                "offer" -> handleOffer(browserId, signalMap)
                "ice" -> handleIceCandidate(browserId, signalMap)
                else -> Log.w(TAG, "[SIGNAL_DEBUG] Unknown signal type: $signalType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[SIGNAL_DEBUG] Failed to parse signaling message", e)
        }
    }

    private fun handleOffer(browserId: String, signalMap: Map<*, *>) {
        val sdp = signalMap["sdp"] as? String ?: return
        Log.i(TAG, "[SIGNAL_DEBUG] OFFER_RECEIVED from $browserId")

        // Create a new PeerConnection for this browser
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                // Send each ICE candidate to the browser via the relay signaling channel
                val msg = mapOf(
                    "type" to "signal",
                    "browserId" to browserId,
                    "signal" to mapOf(
                        "type" to "ice",
                        "candidate" to candidate.sdp,
                        "sdpMid" to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex
                    )
                )
                kotlinx.coroutines.runBlocking {
                    onSignal(gson.toJson(msg))
                }
                Log.i(TAG, "[SIGNAL_DEBUG] ICE_SENT to $browserId")
            }

            override fun onDataChannel(dc: DataChannel) {
                Log.i(TAG, "[DC_DEBUG] DATA_CHANNEL_OPEN: ${dc.label()}")
                dataChannels[browserId] = dc
                setupDataChannel(browserId, dc)
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "[ICE_DEBUG] ICE connection state for $browserId: $state")
                if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED) {
                    cleanup(browserId)
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.i(TAG, "[SIGNAL_DEBUG] Signaling state for $browserId: $state")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.i(TAG, "[ICE_DEBUG] ICE gathering state for $browserId: $state")
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "[ICE_DEBUG] PeerConnection state for $browserId: $newState")
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }) ?: run {
            Log.e(TAG, "Failed to create PeerConnection for $browserId")
            return
        }

        peerConnections[browserId] = pc

        // Set the remote SDP offer
        val sessionDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.i(TAG, "[SIGNAL_DEBUG] Remote description (offer) set successfully for $browserId")
                // Create an SDP answer
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc.setLocalDescription(SimpleSdpObserver(), answer)

                        // Send the answer back to the browser via relay
                        val msg = mapOf(
                            "type" to "signal",
                            "browserId" to browserId,
                            "signal" to mapOf(
                                "type" to "answer",
                                "sdp" to answer.description
                            )
                        )
                        kotlinx.coroutines.runBlocking {
                            onSignal(gson.toJson(msg))
                        }
                        Log.i(TAG, "[SIGNAL_DEBUG] ANSWER_SENT to $browserId")
                    }
                    override fun onCreateFailure(error: String) {
                        Log.e(TAG, "[SIGNAL_DEBUG] Failed to create answer: $error")
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String) {}
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "[SIGNAL_DEBUG] Failed to set remote description: $error")
            }
            override fun onCreateSuccess(desc: SessionDescription) {}
            override fun onCreateFailure(error: String) {}
        }, sessionDesc)
    }

    private fun handleIceCandidate(browserId: String, signalMap: Map<*, *>) {
        val candidate = signalMap["candidate"] as? String ?: return
        val sdpMid = signalMap["sdpMid"] as? String ?: return
        val sdpMLineIndex = (signalMap["sdpMLineIndex"] as? Double)?.toInt() ?: return

        peerConnections[browserId]?.addIceCandidate(
            IceCandidate(sdpMid, sdpMLineIndex, candidate)
        )
    }

    /**
     * Set up the DataChannel to handle incoming API requests from the browser.
     * Each request is a JSON message; responses may be JSON (small) or chunked binary (large).
     */
    private fun setupDataChannel(browserId: String, dc: DataChannel) {
        dc.registerObserver(object : DataChannel.Observer {
            // Buffer for assembling upload chunks
            private val uploadBuffers = ConcurrentHashMap<String, java.io.ByteArrayOutputStream>()
            private val uploadRequests = ConcurrentHashMap<String, Map<*, *>>()

            override fun onMessage(buffer: DataChannel.Buffer) {
                try {
                    if (!buffer.binary) {
                        // JSON control message (API request or upload control)
                        val text = Charset.forName("UTF-8").decode(buffer.data).toString()
                        handleTextMessage(browserId, dc, text)
                    } else {
                        // Binary chunk — part of a file upload
                        handleBinaryChunk(browserId, dc, buffer.data)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[DC_DEBUG] DataChannel message error", e)
                }
            }

            private fun handleTextMessage(browserId: String, dc: DataChannel, text: String) {
                val msg = gson.fromJson(text, Map::class.java) as? Map<*, *> ?: return
                val type = msg["type"] as? String ?: return
                val reqId = msg["id"] as? String ?: ""

                when (type) {
                    "req" -> {
                        // Standard API request — forward to local Ktor server
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            forwardRequestToLocalServer(dc, msg)
                        }
                    }
                    "upload-start" -> {
                        // Begin accumulating upload chunks
                        uploadBuffers[reqId] = java.io.ByteArrayOutputStream()
                        uploadRequests[reqId] = msg
                        Log.d(TAG, "[DC_DEBUG] Upload started: $reqId")
                    }
                    "upload-end" -> {
                        // Upload complete — forward accumulated body to local server
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val body = uploadBuffers.remove(reqId)?.toByteArray() ?: ByteArray(0)
                            val mergedRequest = LinkedHashMap<Any?, Any?>()
                            uploadRequests.remove(reqId)?.forEach { (key, value) -> mergedRequest[key] = value }
                            msg.forEach { (key, value) -> mergedRequest[key] = value }
                            forwardUploadToLocalServer(dc, mergedRequest, body)
                        }
                    }
                }
            }

            private fun handleBinaryChunk(browserId: String, dc: DataChannel, data: ByteBuffer) {
                if (data.remaining() < 36) return
                val idBytes = ByteArray(36)
                data.get(idBytes)
                val reqId = String(idBytes, Charsets.UTF_8).trim()

                val remaining = ByteArray(data.remaining())
                data.get(remaining)

                uploadBuffers[reqId]?.write(remaining)
            }

            override fun onBufferedAmountChange(amount: Long) {}
            override fun onStateChange() {
                Log.i(TAG, "[DC_DEBUG] DataChannel state: ${dc.state()}")
            }
        })
    }

    /**
     * Forward an API request received over the DataChannel to the local Ktor server
     * and stream the response back over the DataChannel using chunked binary protocol.
     *
     * This is the core of the P2P architecture — the browser's request goes:
     * Browser DataChannel → this method → localhost:8080 → ContentResolver → response chunks → DataChannel → Browser
     *
     * Memory safety: Large files are streamed in 64KB chunks. We never hold
     * the entire file in memory, preventing OOM on the Android device.
     */
    private suspend fun forwardRequestToLocalServer(dc: DataChannel, request: Map<*, *>) {
        val reqId = request["id"] as? String ?: return
        val method = request["method"] as? String ?: "GET"
        val path = request["path"] as? String ?: "/"
        val query = request["query"] as? String ?: ""
        val headers = (request["headers"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            val value = v as? String ?: return@mapNotNull null
            key to value
        }?.toMap() ?: emptyMap()

        val querySuffix = if (query.isNotBlank()) "?$query" else ""
        val url = "http://127.0.0.1:$DEFAULT_PORT$path$querySuffix"

        try {
            localClient.prepareRequest(url) {
                this.method = HttpMethod.parse(method)
                headers.forEach { (k, v) ->
                    if (!k.equals("Host", true) && !k.equals("Content-Length", true)) {
                        header(k, v)
                    }
                }
                // If there's a body in the request (for POST/PUT), decode and set it
                val bodyB64 = request["body"] as? String
                if (!bodyB64.isNullOrBlank()) {
                    val bodyBytes = java.util.Base64.getDecoder().decode(bodyB64)
                    val ct = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                    if (ct != null) {
                        setBody(ByteArrayContent(bodyBytes, ContentType.parse(ct)))
                    } else {
                        setBody(bodyBytes)
                    }
                }
            }.execute { statement ->
                val responseHeaders = mutableMapOf<String, String>()
                statement.headers.forEach { name, values ->
                    if (!name.equals("Transfer-Encoding", true) &&
                        !name.equals("Connection", true)) {
                        responseHeaders[name] = values.joinToString(", ")
                    }
                }

                val contentLength = statement.headers["Content-Length"]?.toLongOrNull() ?: -1L
                val isLargeResponse = contentLength > CHUNK_SIZE || contentLength == -1L

                if (isLargeResponse && method.equals("GET", true)) {
                    // ── Chunked streaming response ──────────────────────────
                    // Send response header
                    val startMsg = gson.toJson(mapOf(
                        "type" to "res-start",
                        "id" to reqId,
                        "status" to statement.status.value,
                        "headers" to responseHeaders,
                        "size" to contentLength
                    ))
                    sendText(dc, startMsg)

                    // Stream the body in 64KB chunks
                    val channel = statement.bodyAsChannel()
                    val idBytes = reqId.padEnd(36, ' ').toByteArray(Charsets.UTF_8).sliceArray(0 until 36)
                    val readBuffer = java.nio.ByteBuffer.allocate(CHUNK_SIZE)

                    while (!channel.isClosedForRead) {
                        readBuffer.clear()
                        val bytesRead = channel.readAvailable(readBuffer)
                        if (bytesRead > 0) {
                            readBuffer.flip()
                            val packet = ByteBuffer.allocate(36 + bytesRead)
                            packet.put(idBytes)
                            packet.put(readBuffer)
                            packet.flip()
                            sendBinary(dc, packet)
                        }
                    }

                    // Signal end of stream
                    sendText(dc, gson.toJson(mapOf("type" to "res-end", "id" to reqId)))
                } else {
                    // ── Small response — send as single JSON message ────────
                    val bodyChannel = statement.bodyAsChannel()
                    val bodyBytes = bodyChannel.readRemaining().readBytes()
                    val bodyB64 = if (bodyBytes.isNotEmpty())
                        java.util.Base64.getEncoder().encodeToString(bodyBytes) else null

                    val resMsg = gson.toJson(mapOf(
                        "type" to "res",
                        "id" to reqId,
                        "status" to statement.status.value,
                        "headers" to responseHeaders,
                        "body" to bodyB64
                    ))
                    sendText(dc, resMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local server request failed: ${e.message}", e)
            val errMsg = gson.toJson(mapOf(
                "type" to "res",
                "id" to reqId,
                "status" to 502,
                "headers" to mapOf("Content-Type" to "text/plain"),
                "body" to java.util.Base64.getEncoder().encodeToString(
                    "P2P proxy error: ${e.message}".toByteArray()
                )
            ))
            sendText(dc, errMsg)
        }
    }

    /**
     * Handle a completed file upload — forward the accumulated body to the local server.
     */
    private suspend fun forwardUploadToLocalServer(dc: DataChannel, request: Map<*, *>, body: ByteArray) {
        val reqId = request["id"] as? String ?: return
        val path = request["path"] as? String ?: "/api/upload"
        val query = request["query"] as? String ?: ""
        val headers = (request["headers"] as? Map<*, *>)?.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            val value = v as? String ?: return@mapNotNull null
            key to value
        }?.toMap() ?: emptyMap()

        val querySuffix = if (query.isNotBlank()) "?$query" else ""
        val url = "http://127.0.0.1:$DEFAULT_PORT$path$querySuffix"

        try {
            localClient.prepareRequest(url) {
                this.method = HttpMethod.Post
                headers.forEach { (k, v) ->
                    if (!k.equals("Host", true) && !k.equals("Content-Length", true)) {
                        header(k, v)
                    }
                }
                val ct = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                if (ct != null) {
                    setBody(ByteArrayContent(body, ContentType.parse(ct)))
                } else {
                    setBody(body)
                }
            }.execute { statement ->
                val bodyChannel = statement.bodyAsChannel()
                val resBytes = bodyChannel.readRemaining().readBytes()
                val bodyB64 = if (resBytes.isNotEmpty())
                    java.util.Base64.getEncoder().encodeToString(resBytes) else null

                val resMsg = gson.toJson(mapOf(
                    "type" to "res",
                    "id" to reqId,
                    "status" to statement.status.value,
                    "body" to bodyB64
                ))
                sendText(dc, resMsg)
            }
        } catch (e: Exception) {
            val errMsg = gson.toJson(mapOf(
                "type" to "res",
                "id" to reqId,
                "status" to 502,
                "body" to java.util.Base64.getEncoder().encodeToString(
                    "Upload failed: ${e.message}".toByteArray()
                )
            ))
            sendText(dc, errMsg)
        }
    }

    // ── DataChannel send helpers ─────────────────────────────────────────────

    private fun sendText(dc: DataChannel, text: String) {
        val buf = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
        dc.send(DataChannel.Buffer(buf, false))
    }

    private fun sendBinary(dc: DataChannel, data: ByteBuffer) {
        dc.send(DataChannel.Buffer(data, true))
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    private fun cleanup(browserId: String) {
        dataChannels.remove(browserId)?.close()
        peerConnections.remove(browserId)?.dispose()
        Log.i(TAG, "Cleaned up P2P connection for browser $browserId")
    }

    fun destroy() {
        peerConnections.values.forEach { it.dispose() }
        dataChannels.values.forEach { it.close() }
        peerConnections.clear()
        dataChannels.clear()
        localClient.close()
        factory.dispose()
        Log.i(TAG, "WebRTCPeer destroyed")
    }
}

/** Simple SDP observer that just logs failures */
private class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(desc: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {
        Log.e("WebRTCPeer", "SDP create failure: $error")
    }
    override fun onSetFailure(error: String) {
        Log.e("WebRTCPeer", "SDP set failure: $error")
    }
}

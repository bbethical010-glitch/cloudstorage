package com.pratham.cloudstorage

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "RemoteNodeClient"

interface DownloadListener {
    fun onDownloadStarted(requestId: String, fileName: String, totalBytes: Long)
    fun onDownloadProgress(requestId: String, downloadedBytes: Long)
    fun onDownloadCompleted(requestId: String, file: File)
    fun onDownloadFailed(requestId: String, error: String)
}

/**
 * RemoteNodeClient handles the outbound P2P connection to another Android node.
 * It initiates the WebRTC handshake via a signaling relay and manages the DataChannel.
 */
class RemoteNodeClient(
    private val context: Context,
    private val relayUrl: String,
    private val shareCode: String,
    private val downloadListener: DownloadListener? = null
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _connectionStatus = MutableStateFlow(RemoteConnectionStatus.IDLE)
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles = _remoteFiles.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var signalingWs: HttpClient? = null
    private var signalingJob: Job? = null

    // Tracking pending requests and their binary streams
    private val pendingRequests = ConcurrentHashMap<String, String>() // reqId -> type ("files", "download")
    private val activeStreams = ConcurrentHashMap<String, FileOutputStream>()
    private val streamFiles = ConcurrentHashMap<String, File>()
    private val accumulatedBytes = ConcurrentHashMap<String, Long>()
    private val totalBytesMap = ConcurrentHashMap<String, Long>()

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
    )

    companion object {
        private var isInitialized = false
        private val initLock = Any()

        fun initializeWebRTC(context: Context) {
            synchronized(initLock) {
                if (!isInitialized) {
                    val options = PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                    PeerConnectionFactory.initialize(options)
                    isInitialized = true
                    Log.i(TAG, "WebRTC initialized globally")
                }
            }
        }
    }

    private val factory: PeerConnectionFactory by lazy {
        initializeWebRTC(context)
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    /**
     * Connects to the remote node via signaling relay.
     */
    fun connect() {
        if (_connectionStatus.value == RemoteConnectionStatus.CONNECTING || 
            _connectionStatus.value == RemoteConnectionStatus.CONNECTED) return

        _connectionStatus.value = RemoteConnectionStatus.CONNECTING
        
        signalingJob = scope.launch {
            try {
                val wsUrl = relayUrl.replace("https://", "wss://").replace("http://", "ws://") + 
                            "/signal/${shareCode.uppercase().trim()}"
                
                signalingWs = HttpClient(OkHttp) {
                    install(WebSockets)
                }

                signalingWs?.webSocket(wsUrl) {
                    Log.i(TAG, "Signaling WebSocket connected to $wsUrl")
                    initPeerConnection(this)
                    
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            handleSignalingMessage(frame.readText(), this)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "Signaling error", e)
                    _connectionStatus.value = RemoteConnectionStatus.ERROR
                }
            }
        }
    }

    private suspend fun initPeerConnection(session: io.ktor.websocket.WebSocketSession) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                val msg = mapOf(
                    "type" to "signal",
                    "signal" to mapOf(
                        "type" to "ice",
                        "candidate" to candidate.sdp,
                        "sdpMid" to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex
                    )
                )
                scope.launch {
                    try {
                        session.outgoing.send(Frame.Text(gson.toJson(msg)))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send ICE candidate", e)
                    }
                }
            }

            override fun onDataChannel(dc: DataChannel) {
                Log.i(TAG, "DataChannel received: ${dc.label()}")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE Connection State: $state")
                if (state == PeerConnection.IceConnectionState.CONNECTED) {
                    _connectionStatus.value = RemoteConnectionStatus.CONNECTED
                } else if (state == PeerConnection.IceConnectionState.DISCONNECTED || 
                           state == PeerConnection.IceConnectionState.FAILED) {
                    _connectionStatus.value = RemoteConnectionStatus.DISCONNECTED
                    disconnect()
                }
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState) {}
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                if (state == PeerConnection.PeerConnectionState.CONNECTED) {
                     _connectionStatus.value = RemoteConnectionStatus.CONNECTED
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        })

        val dcInit = DataChannel.Init().apply { ordered = true }
        dataChannel = peerConnection?.createDataChannel("files", dcInit)
        setupDataChannel(dataChannel)

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        val msg = mapOf(
                            "type" to "signal",
                            "signal" to mapOf("type" to "offer", "sdp" to desc.description)
                        )
                        scope.launch { 
                            try {
                                session.outgoing.send(Frame.Text(gson.toJson(msg)))
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send Offer", e)
                            }
                        }
                    }
                    override fun onSetFailure(e: String) { Log.e(TAG, "Local SDP set failure: $e") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, desc)
            }
            override fun onCreateFailure(e: String) { Log.e(TAG, "Offer creation failure: $e") }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    private fun handleSignalingMessage(json: String, session: io.ktor.websocket.WebSocketSession) {
        try {
            val msg = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return
            val signal = msg["signal"] as? Map<*, *> ?: return
            val type = signal["type"] as? String ?: return

            when (type) {
                "answer" -> {
                    val sdp = signal["sdp"] as? String ?: return
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {}
                        override fun onSetFailure(e: String) { Log.e(TAG, "Remote SDP set failure: $e") }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }
                "ice" -> {
                    val candidate = signal["candidate"] as? String ?: return
                    val sdpMid = signal["sdpMid"] as? String ?: return
                    val sdpMLineIndex = (signal["sdpMLineIndex"] as? Double)?.toInt() ?: return
                    peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle signaling message", e)
        }
    }

    private fun setupDataChannel(dc: DataChannel?) {
        dc?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                Log.i(TAG, "DataChannel State: ${dc.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) {
                    handleBinaryPacket(buffer.data)
                } else {
                    val text = Charsets.UTF_8.decode(buffer.data).toString()
                    handleTextResponse(text)
                }
            }
        })
    }

    private fun handleTextResponse(json: String) {
        try {
            val msg = gson.fromJson(json, Map::class.java) as? Map<*, *> ?: return
            val type = msg["type"] as? String ?: return
            val id = msg["id"] as? String ?: return

            when (type) {
                "res" -> {
                    val reqType = pendingRequests.remove(id)
                    if (reqType == "files") {
                        val bodyB64 = msg["body"] as? String
                        if (bodyB64 != null) {
                            val bodyJson = String(Base64.getDecoder().decode(bodyB64))
                            val typeToken = object : TypeToken<List<Map<String, Any>>>() {}.type
                            val files: List<Map<String, Any>> = gson.fromJson(bodyJson, typeToken)
                            _remoteFiles.value = files.map { mapToRemoteFile(it) }
                        }
                    }
                }
                "res-start" -> {
                    val reqType = pendingRequests[id]
                    if (reqType == "download") {
                        val headers = msg["headers"] as? Map<*, *>
                        val size = (msg["size"] as? Double)?.toLong() ?: -1L
                        val contentDisp = headers?.get("Content-Disposition") as? String
                        val fileName = contentDisp?.substringAfter("filename=")?.trim('"') ?: "downloaded_file"
                        
                        totalBytesMap[id] = size
                        accumulatedBytes[id] = 0L
                        val file = File(context.cacheDir, "remote_dl_${id}_${fileName}")
                        streamFiles[id] = file
                        activeStreams[id] = FileOutputStream(file)
                        
                        downloadListener?.onDownloadStarted(id, fileName, size)
                    }
                }
                "res-end" -> {
                    pendingRequests.remove(id)
                    activeStreams.remove(id)?.close()
                    streamFiles.remove(id)?.let { file ->
                        downloadListener?.onDownloadCompleted(id, file)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle text response", e)
        }
    }

    private fun handleBinaryPacket(data: ByteBuffer) {
        if (data.remaining() < 36) return // 36-byte header for downloads
        
        val idBytes = ByteArray(36)
        data.get(idBytes)
        val id = String(idBytes, Charsets.UTF_8).trim()
        
        val fos = activeStreams[id]
        if (fos != null) {
            val remaining = data.remaining()
            val chunk = ByteArray(remaining)
            data.get(chunk)
            fos.write(chunk)
            
            val current = (accumulatedBytes[id] ?: 0L) + remaining
            accumulatedBytes[id] = current
            downloadListener?.onDownloadProgress(id, current)
        }
    }

    private fun mapToRemoteFile(map: Map<String, Any>): RemoteFile {
        return RemoteFile(
            id = map["id"] as? String ?: "",
            name = map["name"] as? String ?: "",
            path = map["path"] as? String ?: "",
            isDirectory = map["isDirectory"] as? Boolean ?: false,
            size = (map["size"] as? Double)?.toLong() ?: 0L,
            lastModified = (map["lastModified"] as? Double)?.toLong() ?: 0L,
            mimeType = map["mimeType"] as? String
        )
    }

    /**
     * Sends a request over the DataChannel with backpressure support.
     */
    fun sendRequest(
        path: String, 
        method: String = "GET", 
        type: String = "files", 
        headers: Map<String, String> = emptyMap(),
        requestId: String = UUID.randomUUID().toString()
    ) {
        val dc = dataChannel ?: return
        if (dc.state() != DataChannel.State.OPEN) return

        // 1MB backpressure limit
        if (dc.bufferedAmount() > 1024 * 1024) {
            Log.w(TAG, "DataChannel buffer full ($${dc.bufferedAmount()} bytes), dropping request: $path")
            return
        }

        pendingRequests[requestId] = type
        
        val msg = mapOf(
            "type" to "req",
            "id" to requestId,
            "method" to method,
            "path" to path,
            "headers" to headers
        )
        
        val buf = ByteBuffer.wrap(gson.toJson(msg).toByteArray(Charsets.UTF_8))
        dc.send(DataChannel.Buffer(buf, false))
    }

    /**
     * Returns the number of active binary streams.
     */
    fun getActiveStreamCount(): Int = activeStreams.size

    /**
     * Disconnects and cleans up resources.
     */
    fun disconnect() {
        scope.cancel()
        signalingJob?.cancel()
        signalingWs?.close()
        dataChannel?.unregisterObserver()
        dataChannel?.dispose()
        peerConnection?.dispose()
        _connectionStatus.value = RemoteConnectionStatus.IDLE
        activeStreams.values.forEach { 
            try { it.close() } catch (e: Exception) {}
        }
        activeStreams.clear()
        streamFiles.clear()
        Log.i(TAG, "Disconnected and cleaned up")
    }
}

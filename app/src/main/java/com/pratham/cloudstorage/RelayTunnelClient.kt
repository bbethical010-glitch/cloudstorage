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
import io.ktor.websocket.readBytes
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.ContentType
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.TimeUnit

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
    private val activeUploadStreams = java.util.concurrent.ConcurrentHashMap<String, OutputStream>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relayWebSocketUrl = relayBaseUrl.toWebSocketUrl(shareCode.uppercase().trim())

    private val relayClient = HttpClient(OkHttp) {
        install(WebSockets) {
            maxFrameSize = 100_000_000L // 100MB frame limit for large uploads
        }
        engine {
            config {
                pingInterval(30, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
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
            onStatusChange(TunnelStatus.Connecting)
            var backoffMs = 1000L
            while (isActive) {
                try {
                    relayClient.webSocket(urlString = relayWebSocketUrl) {
                        Log.i(TAG, "Relay tunnel connected for $shareCode")
                        onStatusChange(TunnelStatus.Connected)
                        outgoing.send(Frame.Text("{\"type\":\"register\",\"nodeId\":\"$shareCode\"}"))
                        backoffMs = 1000L
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    frame.readText().toEnvelopeOrNull()?.let { envelope ->
                                        when (envelope.type) {
                                            "log" -> {
                                                Log.d(TAG, "[RELAY LOG] ${envelope.error ?: "unspecified"}")
                                            }
                                            "request" -> {
                                                scope.launch {
                                                    when (envelope.subType) {
                                                        "upload_start" -> {
                                                            handleUploadStart(envelope)
                                                        }
                                                        "upload_end" -> {
                                                            val response = handleUploadEnd(envelope)
                                                            outgoing.send(Frame.Text(relayGson.toJson(response)))
                                                        }
                                                        "upload_start_stream" -> {
                                                            val pathStr = envelope.query?.let { q -> Regex("path=([^&]*)").find(q)?.groupValues?.get(1) }
                                                                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
                                                            val nameStr = envelope.query?.let { q -> Regex("name=([^&]*)").find(q)?.groupValues?.get(1) }
                                                                ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "uploaded_file"

                                                            envelope.requestId?.let { cleanupStreaming(it) }
                                                            val file = ensureDirectoriesAndCreateFile(rootUri, pathStr, nameStr)
                                                            if (file != null && envelope.requestId != null) {
                                                                activeUploadStreams[envelope.requestId] = context.contentResolver.openOutputStream(file.uri)!!
                                                            } else {
                                                                val errResponse = RelayEnvelope(type = "response", requestId = envelope.requestId, status = 500, error = "Failed to create directory path natively on Android.")
                                                                outgoing.send(Frame.Text(relayGson.toJson(errResponse)))
                                                            }
                                                        }
                                                        "upload_end_stream" -> {
                                                            val response = handleUploadEnd(envelope)
                                                            outgoing.send(Frame.Text(relayGson.toJson(response)))
                                                        }
                                                        else -> {
                                                            forwardToLocalNodeAndStream(envelope, outgoing)
                                                        }
                                                    }
                                                }
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                                is Frame.Binary -> {
                                    try {
                                        val bytes = frame.readBytes()
                                        if (bytes.size >= 36) {
                                            val reqId = String(bytes, 0, 36, Charsets.UTF_8).trim()
                                            activeUploadStreams[reqId]?.write(bytes, 36, bytes.size - 36)
                                        }
                                    } catch (e: Exception) {
                                        Log.e("TUNNEL_DEBUG", "Failed to write binary chunk to external storage", e)
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Relay tunnel disconnected for $shareCode: ${error.message}")
                    onStatusChange(TunnelStatus.Error)
                    cleanupAllStreaming()
                }

                if (isActive) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(15000L)
                    onStatusChange(TunnelStatus.Connecting)
                }
            }
        }
    }

    fun stop() {
        onStatusChange(TunnelStatus.Offline)
        scope.cancel()
        relayClient.close()
        localNodeClient.close()
    }

    private suspend fun forwardToLocalNodeAndStream(request: RelayEnvelope, outgoing: kotlinx.coroutines.channels.SendChannel<Frame>) {
        val targetPath = request.path?.takeIf { it.isNotBlank() } ?: "/"
        val querySuffix = request.query?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
        val targetUrl = "http://127.0.0.1:$DEFAULT_PORT$targetPath$querySuffix"

        try {
            localNodeClient.prepareRequest(targetUrl) {
                this.method = HttpMethod.parse(request.method ?: HttpMethod.Get.value)
                request.headers.orEmpty().forEach { (key, value) ->
                    if (!isHopByHopHeader(key) && !key.equals(HttpHeaders.Host, ignoreCase = true)) {
                        header(key, value)
                    }
                }

                request.bodyBase64.decodeBase64()?.let { body ->
                    if (body.isNotEmpty()) {
                        val contentTypeStr = request.headers?.entries?.firstOrNull { it.key.equals(HttpHeaders.ContentType, true) }?.value
                        if (contentTypeStr != null) {
                            setBody(ByteArrayContent(body, ContentType.parse(contentTypeStr)))
                        } else {
                            setBody(body)
                        }
                    }
                }
            }.execute { statement ->
                val headersResponse = RelayEnvelope(
                    type = "response",
                    subType = "download_start_stream",
                    requestId = request.requestId,
                    status = statement.status.value,
                    headers = statement.headers.flattenEntries()
                        .filterNot { (key, _) ->
                            isHopByHopHeader(key) || key.equals(HttpHeaders.ContentLength, ignoreCase = true)
                        }
                        .associate { (key, value) -> key to value }
                )
                outgoing.send(Frame.Text(relayGson.toJson(headersResponse)))

                val channel = statement.bodyAsChannel()
                val idBytes = request.requestId.orEmpty().padEnd(36, ' ').toByteArray(Charsets.UTF_8).sliceArray(0 until 36)
                val buffer = java.nio.ByteBuffer.allocate(64 * 1024)
                
                while (!channel.isClosedForRead) {
                    buffer.clear()
                    val read = channel.readAvailable(buffer)
                    if (read > 0) {
                        buffer.flip()
                        val packet = ByteArray(36 + read)
                        System.arraycopy(idBytes, 0, packet, 0, 36)
                        buffer.get(packet, 36, read)
                        outgoing.send(Frame.Binary(true, packet))
                    }
                }

                val endResponse = RelayEnvelope(
                    type = "response",
                    subType = "download_end_stream",
                    requestId = request.requestId
                )
                outgoing.send(Frame.Text(relayGson.toJson(endResponse)))
            }
        } catch (error: Exception) {
            Log.e(TAG, "Local node proxy failed: ${error.message}", error)
            val errResponse = RelayEnvelope(
                type = "response",
                requestId = request.requestId,
                status = 502,
                headers = mapOf(HttpHeaders.ContentType to "text/plain; charset=utf-8"),
                bodyBase64 = "Relay tunnel could not reach the local node.".encodeToByteArray().encodeBase64(),
                error = error.message
            )
            outgoing.send(Frame.Text(relayGson.toJson(errResponse)))
        }
    }

    private fun ensureDirectoriesAndCreateFile(rootUri: Uri, path: String, filename: String): DocumentFile? {
        var currentDir = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        if (path.isNotBlank() && path != "/") {
            val segments = path.split("/").filter { it.isNotBlank() }
            for (segment in segments) {
                if (segment == ".." || segment == ".") continue
                var nextDir = currentDir.findFile(segment)
                if (nextDir == null) {
                    nextDir = currentDir.createDirectory(segment)
                }
                if (nextDir == null) return null
                currentDir = nextDir
            }
        }
        currentDir.findFile(filename)?.delete()
        return currentDir.createFile("application/octet-stream", filename)
    }

    private fun handleUploadStart(request: RelayEnvelope) {
        val filename = request.filename ?: "uploaded_file"
        val requestId = request.requestId
        
        Log.i(TAG, "Starting streaming upload: $filename ($requestId)")
        
        try {
            if (requestId != null) cleanupStreaming(requestId)
            
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            val file = rootDoc?.createFile("application/octet-stream", filename)
            if (file != null && requestId != null) {
                activeUploadStreams[requestId] = context.contentResolver.openOutputStream(file.uri)!!
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming upload", e)
        }
    }

    private fun handleUploadEnd(request: RelayEnvelope): RelayEnvelope {
        val requestId = request.requestId
        Log.i(TAG, "Finalizing streaming upload: $requestId")
        
        val success = requestId != null && activeUploadStreams.containsKey(requestId)
        if (requestId != null) cleanupStreaming(requestId)
        
        return if (success) {
            // Return a simple success page that goes back
            RelayEnvelope(
                type = "response",
                requestId = requestId,
                status = 200,
                headers = mapOf(HttpHeaders.ContentType to "text/html"),
                bodyBase64 = """
                    <html><body>
                    <h2>Upload Successful</h2>
                    <p>File has been streamed to your drive.</p>
                    <script>setTimeout(() => history.back(), 1500);</script>
                    </body></html>
                """.trimIndent().encodeToByteArray().encodeBase64()
            )
        } else {
            RelayEnvelope(
                type = "response",
                requestId = requestId,
                status = 500,
                headers = mapOf(HttpHeaders.ContentType to "text/plain"),
                bodyBase64 = "Upload failed during streaming.".encodeToByteArray().encodeBase64()
            )
        }
    }

    private fun cleanupStreaming(requestId: String) {
        try {
            activeUploadStreams.remove(requestId)?.close()
        } catch (_: Exception) {}
    }

    private fun cleanupAllStreaming() {
        activeUploadStreams.keys.forEach { cleanupStreaming(it) }
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
    val error: String? = null,
    val subType: String? = null,
    val filename: String? = null
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

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

private val relayGson = Gson()

class RelayTunnelClient(
    private val context: Context,
    val relayBaseUrl: String,
    val shareCode: String,
    private val rootUri: Uri
) {
    private var currentStreamingRequestId: String? = null
    private var currentUploadStream: OutputStream? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val relayWebSocketUrl = relayBaseUrl.toWebSocketUrl(shareCode)

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
            while (isActive) {
                try {
                    relayClient.webSocket(urlString = relayWebSocketUrl) {
                        Log.i(TAG, "Relay tunnel connected for $shareCode")
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    frame.readText().toEnvelopeOrNull()?.let { envelope ->
                                        when (envelope.type) {
                                            "log" -> {
                                                Log.d(TAG, "[RELAY LOG] ${envelope.error ?: "unspecified"}")
                                            }
                                            "request" -> {
                                                when (envelope.subType) {
                                                    "upload_start" -> {
                                                        handleUploadStart(envelope)
                                                    }
                                                    "upload_end" -> {
                                                        val response = handleUploadEnd(envelope)
                                                        outgoing.send(Frame.Text(relayGson.toJson(response)))
                                                    }
                                                    else -> {
                                                        val response = forwardToLocalNode(envelope)
                                                        outgoing.send(Frame.Text(relayGson.toJson(response)))
                                                    }
                                                }
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                                is Frame.Binary -> {
                                    val bytes = frame.data
                                    if (bytes.isNotEmpty()) {
                                        currentUploadStream?.let { out ->
                                            out.write(bytes)
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Relay tunnel disconnected for $shareCode: ${error.message}")
                    cleanupStreaming()
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

    private fun handleUploadStart(request: RelayEnvelope) {
        val filename = request.filename ?: "uploaded_file"
        val requestId = request.requestId
        
        Log.i(TAG, "Starting streaming upload: $filename ($requestId)")
        
        try {
            cleanupStreaming()
            
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            val file = rootDoc?.createFile("application/octet-stream", filename)
            if (file != null) {
                currentUploadStream = context.contentResolver.openOutputStream(file.uri)
                currentStreamingRequestId = requestId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming upload", e)
        }
    }

    private fun handleUploadEnd(request: RelayEnvelope): RelayEnvelope {
        val requestId = request.requestId
        Log.i(TAG, "Finalizing streaming upload: $requestId")
        
        val success = currentUploadStream != null && currentStreamingRequestId == requestId
        cleanupStreaming()
        
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

    private fun cleanupStreaming() {
        try {
            currentUploadStream?.close()
        } catch (_: Exception) {}
        currentUploadStream = null
        currentStreamingRequestId = null
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

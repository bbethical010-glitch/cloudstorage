package com.pratham.cloudstorage

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MSG_TYPE_DATA = 0
private const val MSG_TYPE_START = 1
private const val MSG_TYPE_END = 2
private const val MSG_TYPE_ACK = 3
private const val MSG_TYPE_ERROR = 4

private const val STREAMING_PROXY_TAG = "StreamingUploadProxy"

enum class UploadState {
    RECEIVING, // Receiving DATA chunks
    FINALIZING, // END_UPLOAD received, buffering/sending to Ktor
    COMPLETE,  // ACK received from Ktor and sent back to frontend
    FAILED     // Error occurred
}

/**
 * Bridges chunked transport packets into a single streaming POST against the
 * local Ktor node. The pipe is intentionally small, so writes naturally block
 * when the downstream reader falls behind instead of growing unbounded memory.
 */
class StreamingUploadProxySession(
    private val scope: CoroutineScope,
    private val localClient: HttpClient,
    private val method: String,
    private val path: String,
    private val query: String,
    private val headers: Map<String, String>,
    private val contentLength: Long,
    private val tempFile: java.io.File,
    private val onProgress: (Long) -> Unit = {},
    private val onResponse: suspend (status: Int, headers: Map<String, String>, body: ByteArray) -> Unit,
    private val onFailure: suspend (Throwable) -> Unit,
    private val onSignal: ((type: Int, payload: ByteArray) -> Unit)? = null
) {
    private val writeLock = Any()
    private var bytesReceived = 0L
    private var state = UploadState.RECEIVING
    private var nextExpectedSeq = 0L
    private var fileOutput = java.io.FileOutputStream(tempFile)

    init {
        Log.i(STREAMING_PROXY_TAG, "Session initialized. Buffering to ${tempFile.absolutePath}")
    }

    fun handlePacket(type: Int, sequence: Long, payload: ByteArray) {
        synchronized(writeLock) {
            if (state == UploadState.FAILED || state == UploadState.COMPLETE) return

            if (sequence != nextExpectedSeq) {
                val errorMsg = "Sequence mismatch: expected $nextExpectedSeq, got $sequence"
                Log.e(STREAMING_PROXY_TAG, errorMsg)
                fail(errorMsg)
                return
            }
            nextExpectedSeq++

            when (type) {
                MSG_TYPE_DATA -> {
                    if (state != UploadState.RECEIVING) {
                        fail("Invalid state for DATA: $state")
                        return
                    }
                    writeChunk(payload)
                }
                MSG_TYPE_END -> {
                    if (state != UploadState.RECEIVING) {
                        fail("Invalid state for END: $state")
                        return
                    }
                    finish()
                }
                MSG_TYPE_START -> { /* Ignore duplicate START */ }
                else -> {
                    Log.w(STREAMING_PROXY_TAG, "Unexpected packet type: $type")
                }
            }
        }
    }

    private fun writeChunk(chunk: ByteArray) {
        try {
            fileOutput.write(chunk)
            bytesReceived += chunk.size
            onProgress(bytesReceived)
        } catch (e: Exception) {
            Log.e(STREAMING_PROXY_TAG, "File write failed", e)
            fail("File write failed: ${e.message}")
        }
    }

    private fun finish() {
        Log.i(STREAMING_PROXY_TAG, "All chunks received ($bytesReceived bytes). Starting extraction...")
        state = UploadState.FINALIZING
        try {
            fileOutput.close()
        } catch (_: Exception) {}

        val querySuffix = if (query.isNotBlank()) "?$query" else ""
        val targetUrl = "http://127.0.0.1:$DEFAULT_PORT$path$querySuffix"

        scope.launch(Dispatchers.IO) {
            try {
                localClient.prepareRequest(targetUrl) {
                    this.method = HttpMethod.parse(method)
                    headers.forEach { (key, value) ->
                        if (!key.equals("Host", true) && !key.equals("Content-Length", true)) {
                            header(key, value)
                        }
                    }

                    val contentType = this@StreamingUploadProxySession.headers.entries
                        .firstOrNull { it.key.equals("Content-Type", true) }
                        ?.value
                        ?.takeIf { it.isNotBlank() }
                        ?.let { ContentType.parse(it) }

                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override val contentLength: Long = tempFile.length()
                        override val contentType: ContentType? = contentType

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            val buffer = ByteArray(128 * 1024)
                            tempFile.inputStream().use { input ->
                                while (true) {
                                    val bytesRead = withContext(Dispatchers.IO) { input.read(buffer) }
                                    if (bytesRead == -1) break
                                    channel.writeFully(buffer, 0, bytesRead)
                                    channel.flush()
                                }
                            }
                        }
                    })
                }.execute { statement ->
                    val responseHeaders = statement.headers.entries()
                        .filterNot { (key, _) ->
                            key.equals(HttpHeaders.TransferEncoding, true) ||
                                key.equals(HttpHeaders.Connection, true)
                        }
                        .associate { (key, values) -> key to values.joinToString(", ") }
                    val responseBody = statement.bodyAsChannel().readRemaining().readBytes()
                    
                    synchronized(writeLock) {
                        state = UploadState.COMPLETE
                    }

                    onResponse(statement.status.value, responseHeaders, responseBody)
                    // Signal ACK back to frontend
                    onSignal?.invoke(MSG_TYPE_ACK, responseBody)
                }
            } catch (error: Throwable) {
                Log.e(STREAMING_PROXY_TAG, "Extraction request failed", error)
                fail("Extraction failure: ${error.message}")
            } finally {
                cleanup()
            }
        }
    }

    private fun fail(message: String) {
        synchronized(writeLock) {
            if (state == UploadState.FAILED || state == UploadState.COMPLETE) return
            state = UploadState.FAILED
            try { fileOutput.close() } catch (_: Exception) {}
        }
        scope.launch {
            onFailure(RuntimeException(message))
            onSignal?.invoke(MSG_TYPE_ERROR, message.toByteArray())
        }
        cleanup()
    }

    fun cancel(error: Throwable? = null) {
        fail(error?.message ?: "Session cancelled")
    }

    private fun cleanup() {
        synchronized(writeLock) {
            try { fileOutput.close() } catch (_: Exception) {}
            if (tempFile.exists()) {
                tempFile.delete()
                Log.i(STREAMING_PROXY_TAG, "Cleaned up temp file: ${tempFile.name}")
            }
        }
    }

}

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
import java.io.PipedInputStream
import java.io.PipedOutputStream

private const val STREAMING_PROXY_TAG = "StreamingUploadProxy"

/**
 * Bridges chunked transport packets into a single streaming POST against the
 * local Ktor node. The pipe is intentionally small, so writes naturally block
 * when the downstream reader falls behind instead of growing unbounded memory.
 */
class StreamingUploadProxySession(
    scope: CoroutineScope,
    private val localClient: HttpClient,
    method: String,
    path: String,
    query: String,
    headers: Map<String, String>,
    contentLength: Long,
    private val onProgress: (Long) -> Unit = {},
    private val onResponse: suspend (status: Int, headers: Map<String, String>, body: ByteArray) -> Unit,
    private val onFailure: suspend (Throwable) -> Unit
) {
    private val pipeInput = PipedInputStream(256 * 1024)
    private val pipeOutput = PipedOutputStream(pipeInput)
    private val writeLock = Any()
    private var bytesForwarded = 0L
    private var closed = false

    init {
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

                    val contentType = headers.entries
                        .firstOrNull { it.key.equals("Content-Type", true) }
                        ?.value
                        ?.takeIf { it.isNotBlank() }
                        ?.let(ContentType::parse)

                    setBody(object : OutgoingContent.WriteChannelContent() {
                        override val contentLength: Long? = contentLength.takeIf { it >= 0L }
                        override val contentType: ContentType? = contentType

                        override suspend fun writeTo(channel: ByteWriteChannel) {
                            val buffer = ByteArray(64 * 1024)
                            pipeInput.use { input ->
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
                    onResponse(statement.status.value, responseHeaders, responseBody)
                }
            } catch (error: Throwable) {
                Log.e(STREAMING_PROXY_TAG, "Streaming proxy request failed", error)
                onFailure(error)
            } finally {
                closeQuietly()
            }
        }
    }

    fun writeChunk(chunk: ByteArray) {
        synchronized(writeLock) {
            ensureOpen()
            pipeOutput.write(chunk)
            pipeOutput.flush()
            bytesForwarded += chunk.size
        }
        onProgress(bytesForwarded)
    }

    fun finish() {
        synchronized(writeLock) {
            if (closed) return
            closed = true
            pipeOutput.close()
        }
    }

    fun cancel(error: Throwable? = null) {
        synchronized(writeLock) {
            if (closed) return
            closed = true
            try {
                pipeOutput.close()
            } catch (_: Throwable) {
            }
            try {
                pipeInput.close()
            } catch (_: Throwable) {
            }
        }
        if (error != null) {
            Log.w(STREAMING_PROXY_TAG, "Streaming session cancelled: ${error.message}")
        }
    }

    private fun ensureOpen() {
        if (closed) {
            throw IllegalStateException("Streaming upload session already closed")
        }
    }

    private fun closeQuietly() {
        synchronized(writeLock) {
            if (closed) return
            closed = true
            try {
                pipeOutput.close()
            } catch (_: Throwable) {
            }
            try {
                pipeInput.close()
            } catch (_: Throwable) {
            }
        }
    }
}

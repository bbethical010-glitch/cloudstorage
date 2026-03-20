package com.pratham.cloudstorage.relay

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.flattenEntries
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.websocket.*
import io.ktor.server.websocket.*
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

private val gson = Gson()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        relayModule()
    }.start(wait = true)
}

fun Application.relayModule() {
    install(io.ktor.server.routing.IgnoreTrailingSlash)
    install(WebSockets) {
        // Render's reverse proxy closes idle connections aggressively.
        // Sending a ping every 20s keeps the tunnel alive.
        pingPeriod = Duration.ofSeconds(20)
        timeout = Duration.ofSeconds(120) // Increased timeout for larger uploads
        maxFrameSize = 100 * 1024 * 1024L // Increased to 100MB to allow large JSON envelopes
    }

    val registry = RelayRegistry()

    routing {
        get("/") {
            val connectedCount = registry.connectedCount()
            val codes = registry.connectedShareCodes()
            call.respondText(
                buildRelayLandingPage(connectedCount, codes),
                ContentType.Text.Html
            )
        }

        get("/join") {
            val code = call.request.queryParameters["code"]?.trim()?.uppercase().orEmpty()
            if (code.isBlank()) {
                call.respondText(
                    buildRelayLandingPage(registry.connectedCount(), registry.connectedShareCodes()),
                    ContentType.Text.Html
                )
            } else {
                call.respondRedirect("/node/$code", permanent = false)
            }
        }

        get("/health") {
            call.respondText("relay_online")
        }

        get("/agents") {
            call.respondText(
                gson.toJson(
                    mapOf(
                        "count" to registry.connectedCount(),
                        "shareCodes" to registry.connectedShareCodes()
                    )
                ),
                ContentType.Application.Json
            )
        }

        webSocket("/agent/connect") {
            val shareCode = call.request.queryParameters["shareCode"]
                ?.trim()
                ?.uppercase()
                .orEmpty()

            if (shareCode.isBlank()) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing shareCode"))
                return@webSocket
            }

            registry.register(shareCode, this)
            sendEnvelope(RelayEnvelope(type = "connected", shareCode = shareCode))

            try {
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val envelope = frame.readText().toEnvelopeOrNull() ?: continue
                            if (envelope.type == "response") {
                                if (envelope.subType == "download_end_stream" && !envelope.requestId.isNullOrBlank()) {
                                    registry.getAgent(shareCode)?.let {
                                        it.activeDownloadStreams[envelope.requestId]?.flush()
                                        it.activeDownloadStreams[envelope.requestId]?.close(null)
                                        it.activeDownloadCompletions[envelope.requestId]?.complete(Unit)
                                    }
                                } else if (!envelope.requestId.isNullOrBlank()) {
                                    registry.completeResponse(envelope)
                                }
                            }
                        }
                        is Frame.Binary -> {
                            val bytes = frame.readBytes()
                            if (bytes.size >= 36) {
                                val reqId = String(bytes, 0, 36, Charsets.UTF_8).trim()
                                registry.getAgent(shareCode)?.activeDownloadStreams?.get(reqId)?.writeFully(bytes, 36, bytes.size - 36)
                            }
                        }
                        else -> {}
                    }
                }
            } finally {
                registry.unregister(shareCode, this)
            }
        }

        route("/node/{shareCode}") {
            get {
                // If it doesn't end with a slash, redirect to the trailing slash version
                // so the browser resolves relative asset paths (like ./assets/) against the sharecode, not 'node'
                val shareCode = call.parameters["shareCode"]?.trim()?.uppercase().orEmpty()
                val requestUri = call.request.uri
                if (!requestUri.endsWith("/")) {
                    val queryString = call.request.queryString().let { if (it.isNotBlank()) "?$it" else "" }
                    call.respondRedirect("/node/$shareCode/$queryString", permanent = true)
                    return@get
                }
                call.proxyNodeRequest(registry, emptyList())
            }
            post { call.proxyNodeRequest(registry, emptyList()) }
            put { call.proxyNodeRequest(registry, emptyList()) }
            delete { call.proxyNodeRequest(registry, emptyList()) }
            head { call.proxyNodeRequest(registry, emptyList()) }
            options { call.proxyNodeRequest(registry, emptyList()) }
        }

        route("/node/{shareCode}/{...}") {
            get {
                val tail = call.parameters.getAll("...").orEmpty()
                call.proxyNodeRequest(registry, tail)
            }
            post {
                val tail = call.parameters.getAll("...").orEmpty()
                call.proxyNodeRequest(registry, tail)
            }
            put {
                val tail = call.parameters.getAll("...").orEmpty()
                call.proxyNodeRequest(registry, tail)
            }
            delete {
                val tail = call.parameters.getAll("...").orEmpty()
                call.proxyNodeRequest(registry, tail)
            }
            head {
                val tail = call.parameters.getAll("...").orEmpty()
                call.proxyNodeRequest(registry, tail)
            }
            options {
                val tail = call.parameters.getAll("...").orEmpty()
                call.proxyNodeRequest(registry, tail)
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.proxyNodeRequest(
    registry: RelayRegistry,
    tail: List<String>
) {
    val shareCode = parameters["shareCode"]?.trim()?.uppercase().orEmpty()
    if (request.queryParameters.contains("debug_trace")) {
        respondText("TRACE: shareCode=$shareCode, multipart=${request.isMultipart()}")
        return
    }
    
    if (shareCode.isBlank()) {
        respondText("Missing share code", status = HttpStatusCode.BadRequest)
        return
    }

    try {
        val agent = registry.getAgent(shareCode)
        if (agent == null) {
            respondText(
                "No active phone node connected for share code $shareCode. Is the Easy Storage app running?",
                status = HttpStatusCode.ServiceUnavailable
            )
            return
        }

        val requestId = UUID.randomUUID().toString()

        val isUpload = request.path().contains("/api/upload") || request.path().contains("/api/folder_")
        val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
        val maxBodyBytes = 50L * 1024 * 1024 // 50 MB for non-streaming
        
        // Remove Hardcoded Limits: Ignore 50MB limit only if it's an upload pipe
        if (!isUpload && contentLength > maxBodyBytes) {
            respondText(
                "Payload too large (max 50 MB for non-streaming).",
                status = HttpStatusCode.PayloadTooLarge
            )
            return
        }

        val originalShareCode = parameters["shareCode"] ?: shareCode
        val relayPath = request.path()
            .removePrefix("/node/$originalShareCode")
            .removePrefix("/")
            .let { "/$it" }

        val relayHeaders = request.headers.flattenEntries()
            .filterNot { (key, _) -> key.equals(HttpHeaders.Host, ignoreCase = true) || isHopByHopHeader(key) }
            .associate { (key, value) -> key to value }

        if (isUpload) {
            // Initiate Pure Stream Forwarding
            val relayRequest = RelayEnvelope(
                type = "request",
                subType = "upload_start_stream",
                requestId = requestId,
                method = request.httpMethod.value,
                path = relayPath,
                query = request.queryString(),
                headers = relayHeaders
                // Body-parsing completely disabled—no base64 payload envelope
            )
            
            val deferred = registry.prepareResponse(requestId)
            try {
                agent.send(relayRequest)

                // Extract the incoming stream seamlessly
                val channel = request.receiveChannel()
                val idBytes = requestId.padEnd(36, ' ').toByteArray(Charsets.UTF_8).sliceArray(0 until 36)
                val bb = java.nio.ByteBuffer.allocate(64 * 1024)

                // Manage flow control and suspension points naturally 
                while (!channel.isClosedForRead) {
                    bb.clear()
                    val read = channel.readAvailable(bb)
                    if (read > 0) {
                        bb.flip()
                        val packet = ByteArray(36 + read)
                        System.arraycopy(idBytes, 0, packet, 0, 36)
                        bb.get(packet, 36, read)
                        agent.sendFrame(io.ktor.websocket.Frame.Binary(true, packet))
                    }
                }
                
                val endEnvelope = RelayEnvelope(
                    type = "request",
                    subType = "upload_end_stream",
                    requestId = requestId
                )
                agent.send(endEnvelope)

                val relayResponse = kotlinx.coroutines.withTimeoutOrNull(180_000) { deferred.await() }
                if (relayResponse != null) {
                    respondRelayResponse(relayResponse)
                } else {
                    respondText("Phone node did not respond in time.", status = HttpStatusCode.GatewayTimeout)
                }
            } catch (e: Exception) {
                // Gracefully Trap Errors without crashing the whole application
                respondText("Proxy gateway stream interrupted: ${e.message}", status = HttpStatusCode.BadGateway)
            } finally {
                registry.removeResponse(requestId)
            }
        } else {
            // Standard non-multipart request handling
            val requestBody = receive<ByteArray>()
            val relayRequest = RelayEnvelope(
                type = "request",
                requestId = requestId,
                method = request.httpMethod.value,
                path = relayPath,
                query = request.queryString(),
                headers = relayHeaders,
                bodyBase64 = requestBody.encodeBase64()
            )

            val relayResponse = registry.forwardEnvelope(agent, relayRequest)
            if (relayResponse != null) {
                if (relayResponse.subType == "download_start_stream") {
                    val status = relayResponse.status?.let(HttpStatusCode::fromValue) ?: HttpStatusCode.OK
                    val contentType = relayResponse.headers?.get(HttpHeaders.ContentType)?.let { ContentType.parse(it) }
                    val contentLength = relayResponse.headers?.get(HttpHeaders.ContentLength)?.toLongOrNull()

                    relayResponse.headers.orEmpty()
                        .filterNot { (k, _) -> 
                            isHopByHopHeader(k) || 
                            k.equals(HttpHeaders.ContentLength, true) || 
                            k.equals(HttpHeaders.ContentType, true) ||
                            k.equals(HttpHeaders.TransferEncoding, true)
                        }
                        .forEach { (k, v) -> response.headers.append(k, v, safeOnly = false) }

                    val completion = CompletableDeferred<Unit>()
                    agent.activeDownloadCompletions[requestId] = completion
                    
                    try {
                        respondBytesWriter(contentType = contentType, status = status, contentLength = contentLength) {
                            agent.activeDownloadStreams[requestId] = this
                            completion.await()
                        }
                    } finally {
                        agent.activeDownloadStreams.remove(requestId)
                        agent.activeDownloadCompletions.remove(requestId)
                    }
                } else {
                    respondRelayResponse(relayResponse)
                }
            } else {
                respondText("Phone node did not respond in time.", status = HttpStatusCode.GatewayTimeout)
            }
        }

    } catch (e: TimeoutCancellationException) {
        respondText(
            "Phone node did not respond in time. Check your connection and try again.",
            status = HttpStatusCode.GatewayTimeout
        )
    } catch (e: Throwable) {
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        respondText(
            "Relay gateway error:\n$sw",
            status = HttpStatusCode.BadGateway
        )
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondRelayResponse(relayResponse: RelayEnvelope) {
    val status = relayResponse.status?.let(HttpStatusCode::fromValue) ?: HttpStatusCode.BadGateway
    relayResponse.headers.orEmpty()
        .filterNot { (key, _) -> isHopByHopHeader(key) || key.equals(HttpHeaders.ContentLength, ignoreCase = true) }
        .forEach { (key, value) ->
            response.headers.append(key, value, safeOnly = false)
        }

    val bodyBytes = relayResponse.bodyBase64.decodeBase64()
    val contentType = relayResponse.headers?.entries
        ?.firstOrNull { (key, _) -> key.equals(HttpHeaders.ContentType, ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }
        ?.let(ContentType::parse)

    respondBytes(bodyBytes, contentType = contentType, status = status)
}

private class RelayRegistry {
    private val agents = ConcurrentHashMap<String, AgentConnection>()
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<RelayEnvelope>>()

    suspend fun register(shareCode: String, session: DefaultWebSocketSession) {
        val previous = agents.put(shareCode, AgentConnection(shareCode, session))
        previous?.session?.close(CloseReason(CloseReason.Codes.NORMAL, "Replaced by newer connection"))
    }

    fun unregister(shareCode: String, session: DefaultWebSocketSession) {
        agents.computeIfPresent(shareCode) { _, existing ->
            if (existing.session == session) null else existing
        }
    }

    fun connectedCount(): Int = agents.size

    fun connectedShareCodes(): List<String> = agents.keys().toList().sorted()

    fun getAgent(shareCode: String): AgentConnection? = agents[shareCode]

    fun prepareResponse(requestId: String): CompletableDeferred<RelayEnvelope> {
        val deferred = CompletableDeferred<RelayEnvelope>()
        pendingResponses[requestId] = deferred
        return deferred
    }

    fun removeResponse(requestId: String) {
        pendingResponses.remove(requestId)
    }

    suspend fun forwardEnvelope(agent: AgentConnection, request: RelayEnvelope): RelayEnvelope? {
        val requestId = request.requestId ?: return null
        val deferred = prepareResponse(requestId)

        return try {
            agent.send(request)
            withTimeout(45_000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            removeResponse(requestId)
        }
    }

    fun completeResponse(response: RelayEnvelope) {
        val requestId = response.requestId ?: return
        pendingResponses.remove(requestId)?.complete(response)
    }
}

private data class AgentConnection(
    val shareCode: String,
    val session: DefaultWebSocketSession,
    val sendMutex: Mutex = Mutex()
) {
    val activeDownloadStreams = ConcurrentHashMap<String, io.ktor.utils.io.ByteWriteChannel>()
    val activeDownloadCompletions = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    suspend fun send(envelope: RelayEnvelope) {
        sendMutex.withLock {
            session.sendEnvelope(envelope)
        }
    }

    suspend fun sendFrame(frame: Frame) {
        sendMutex.withLock {
            session.outgoing.send(frame)
        }
    }

    suspend fun sendLog(requestId: String, message: String) {
        send(RelayEnvelope(type = "log", requestId = requestId, error = message))
    }
}

private data class RelayEnvelope(
    val type: String,
    val requestId: String? = null,
    val shareCode: String? = null,
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

private suspend fun DefaultWebSocketSession.sendEnvelope(envelope: RelayEnvelope) {
    outgoing.send(Frame.Text(gson.toJson(envelope)))
}

private fun String.toEnvelopeOrNull(): RelayEnvelope? {
    return try {
        gson.fromJson(this, RelayEnvelope::class.java)
    } catch (_: JsonSyntaxException) {
        null
    }
}

private fun ByteArray?.encodeBase64(): String? {
    if (this == null || isEmpty()) return null
    return Base64.getEncoder().encodeToString(this)
}

private fun String?.decodeBase64(): ByteArray {
    if (this.isNullOrBlank()) return ByteArray(0)
    return Base64.getDecoder().decode(this)
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

private fun buildRelayLandingPage(connectedCount: Int, shareCodes: List<String>): String {
    val nodeListHtml = if (shareCodes.isEmpty()) {
        "<div class=\"empty\">No nodes currently connected. Open the Easy Storage app on your Android device and start the node.</div>"
    } else {
        shareCodes.joinToString("") { code ->
            """<a class="node-link" href="/node/$code">
               <span class="code">$code</span>
               <span class="arrow">→ Open console</span>
             </a>"""
        }
    }
    return """<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Easy Storage Relay</title>
  <style>
    :root { color-scheme: dark; --bg: #061018; --panel: #0c1622; --line: #18384f; --text: #e2edf7; --muted: #87a0b5; --accent: #3dd2ff; }
    * { box-sizing: border-box; }
    body { margin: 0; font-family: ui-monospace, monospace; background: linear-gradient(180deg,#040a10,var(--bg)); color: var(--text); padding: 24px; }
    .shell { max-width: 700px; margin: 0 auto; display: grid; gap: 18px; }
    .card { border: 1px solid var(--line); background: rgba(12,22,34,.95); border-radius: 20px; padding: 20px; }
    h1 { font-size: clamp(22px,5vw,36px); margin: 0 0 6px; }
    .muted { color: var(--muted); }
    .label { color: var(--accent); font-size: 11px; font-weight: 700; letter-spacing: .1em; margin-bottom: 12px; }
    .badge { display: inline-block; background: rgba(61,210,255,.15); color: var(--accent); border-radius: 999px; padding: 4px 14px; font-size: 13px; margin-bottom: 10px; }
    .node-link { display: flex; justify-content: space-between; align-items: center; border: 1px solid var(--line); border-radius: 12px; padding: 12px 16px; margin-top: 10px; text-decoration: none; color: white; background: rgba(7,16,24,.8); transition: background .2s; }
    .node-link:hover { background: rgba(61,210,255,.07); }
    .code { font-weight: 700; }
    .arrow { color: var(--accent); }
    .empty { color: var(--muted); margin-top: 8px; }
    form { display: flex; gap: 10px; margin-top: 14px; flex-wrap: wrap; }
    input { flex: 1; min-width: 160px; background: var(--panel); border: 1px solid var(--line); border-radius: 10px; padding: 10px 14px; color: var(--text); font-family: inherit; font-size: 14px; }
    button { background: linear-gradient(135deg,#0f7896,#18536f); color: white; border: none; border-radius: 10px; padding: 10px 20px; font-weight: 700; cursor: pointer; font-family: inherit; }
  </style>
</head>
<body>
  <div class="shell">
    <div class="card">
      <div class="label">EASY STORAGE RELAY</div>
      <h1>Drive relay gateway</h1>
      <p class="muted">This relay forwards requests to Android phone nodes. The files stay on the drive attached to the phone — this server only proxies traffic.</p>
      <div class="badge">$connectedCount node(s) online</div>
    </div>
    <div class="card">
      <div class="label">CONNECTED NODES</div>
      $nodeListHtml
    </div>
    <div class="card">
      <div class="label">OPEN A NODE BY SHARE CODE</div>
      <p class="muted">Paste the share code from the Easy Storage app to open that node's file console.</p>
      <form action="/join" method="get">
        <input name="code" placeholder="e.g. E9455A2DC9" autocomplete="off" autocapitalize="characters">
        <button type="submit">Go →</button>
      </form>
    </div>
  </div>
</body>
</html>"""
}

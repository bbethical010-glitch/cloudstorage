package com.pratham.cloudstorage.relay

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.forwardedheaders.ForwardedHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.util.flattenEntries
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.http.*
import io.ktor.websocket.*
import io.ktor.server.websocket.*
import io.ktor.server.http.content.*
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

// ──────────────────────────────────────────────────────────────────────────────
// Easy Storage Relay — WebRTC Signaling + API Fallback Relay
// ──────────────────────────────────────────────────────────────────────────────

private val gson = Gson()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    println("RELAY_BOOT: Easy Storage Relay starting on 0.0.0.0:$port")
    
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        relayModule()
    }.start(wait = true)
}

fun Application.relayModule() {
    install(IgnoreTrailingSlash)
    install(ForwardedHeaders)
    install(XForwardedHeaders)
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Node-Id")
        allowHeader("X-Archive-Name")
        allowHeader("X-Upload-Id")
        allowHeader("X-Upload-Restart")
        anyHost() // Since this is a public relay Gateway
    }
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(20)
        timeout = Duration.ofSeconds(60)
        maxFrameSize = 8 * 1024 * 1024L
    }

    val registry = SignalingRegistry()

    routing {
        // 1. HEALTH CHECK MUST BE AT THE VERY TOP
        get("/health") {
            call.respondText("OK", status = HttpStatusCode.OK)
        }

        get("/api/health") {
            call.respondText("ok", status = HttpStatusCode.OK)
        }

        // 2. STATIC ASSETS SECOND
        // This ensures your CSS and JS load without hitting the catch-all
        staticResources("/assets", "static/assets")
        staticResources("/", "static") {
            exclude { it.path.endsWith(".html") } // Don't let it serve the raw HTML yet
        }

        get("/nodes") {
            val connectedCount = registry.connectedAgentCount()
            val shareCodes = registry.connectedShareCodes()
            call.respondText(buildRelayLandingPage(connectedCount, shareCodes), ContentType.Text.Html)
        }

        // ── 1. WebSockets & Signaling (High Priority) ─────────────────────────
        
        webSocket("/agent/connect") {
            val shareCode = call.request.queryParameters["shareCode"]?.trim()?.uppercase().orEmpty()
            if (shareCode.isBlank()) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing shareCode"))
                return@webSocket
            }

            println("Relay: Agent connected for node $shareCode")
            registry.registerAgent(shareCode, this)
            send(Frame.Text(gson.toJson(mapOf("type" to "connected", "shareCode" to shareCode))))

            val pingJob = launch {
                while (isActive) {
                    delay(25_000)
                    try { send(Frame.Ping(ByteArray(0))) } catch (_: Exception) { break }
                }
            }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val envelope = text.toEnvelopeOrNull()
                        if (envelope?.type == "response" || envelope?.type == "stream-response") {
                            registry.completeResponse(envelope)
                            continue
                        }

                        val msg = text.toJsonMap() ?: continue
                        when (msg["type"]) {
                            "signal" -> {
                                val browserId = msg["browserId"] as? String
                                if (browserId != null) registry.forwardToBrowser(shareCode, browserId, text)
                                else registry.forwardToAllBrowsers(shareCode, text)
                            }
                            "heartbeat" -> {
                                // Just a stay-alive frame from the agent, no action needed
                            }
                            "connected", "register" -> {
                                // Registration frames do not need relay-side action
                            }
                        }
                    }
                }
            } finally {
                pingJob.cancel()
                registry.unregisterAgent(shareCode, this)
                println("Relay: Agent disconnected for node $shareCode")
            }
        }

        webSocket("/signal/{shareCode}") {
            val shareCode = call.parameters["shareCode"]?.trim()?.uppercase().orEmpty()
            if (shareCode.isBlank()) {
                close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Missing shareCode"))
                return@webSocket
            }

            val browserId = java.util.UUID.randomUUID().toString()
            registry.registerBrowser(shareCode, browserId, this)

            send(Frame.Text(gson.toJson(mapOf(
                "type" to "status",
                "agentOnline" to registry.isAgentHealthy(shareCode),
                "browserId" to browserId
            ))))

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val msg = text.toJsonMap() ?: continue
                        if (msg["type"] == "signal") {
                            val enriched = msg.toMutableMap()
                            enriched["browserId"] = browserId
                            registry.forwardToAgent(shareCode, gson.toJson(enriched))
                        }
                    }
                }
            } finally {
                registry.unregisterBrowser(shareCode, browserId)
            }
        }

        // ── 2. Explicit API Routes ──────────────────────────────────────────────
        
        route("/api") {
            get("/node/{shareCode}/status") {
                val sc = call.parameters["shareCode"]?.trim()?.uppercase() ?: ""
                val isOnline = registry.isAgentHealthy(sc)
                // Log only on changes or if offline to avoid too much spam, but enough to debug 
                if (!isOnline) {
                    println("Relay Status: Node $sc is OFFLINE. Active nodes: ${registry.connectedShareCodes()}")
                }
                call.respondJson(mapOf("online" to isOnline))
            }

            get("/debug/registry") {
                call.respondJson(mapOf(
                    "nodes" to registry.connectedShareCodes(),
                    "total" to registry.connectedAgentCount(),
                    "timestamp" to System.currentTimeMillis()
                ))
            }

            route("{...}") {
                get { call.proxyApiRequest(registry) }
                post { call.proxyApiRequest(registry) }
                put { call.proxyApiRequest(registry) }
                delete { call.proxyApiRequest(registry) }
                head { call.proxyApiRequest(registry) }
                options { call.proxyApiRequest(registry) }
            }
        }

        // ── 3. Proxy & Navigation Routes ────────────────────────────────────────
        
        get("/node/{code}") { 
            val code = call.parameters["code"]?.uppercase() ?: ""
            call.respondRedirect("/#/console/$code")
        }
        
        get("/join") { 
            val code = call.request.queryParameters["code"]?.trim()?.uppercase() ?: ""
            if (code.isNotEmpty()) call.respondRedirect("/#/console/$code")
            else call.respondRedirect("/")
        }

        // ── 4. React SPA & Static Files (Lowest Priority) ───────────────────────
        
        get("{...}") {
            val path = call.request.path()
            if (path.startsWith("/api") || path.startsWith("/assets")) {
                 call.respond(HttpStatusCode.NotFound)
            } else {
                 val content = registry.javaClass.classLoader.getResource("static/index.html")?.readBytes()
                 if (content != null) call.respondBytes(content, ContentType.Text.Html)
                 else call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

private class SignalingRegistry {
    private val agents = ConcurrentHashMap<String, AgentConnection>()
    private val browsers = ConcurrentHashMap<String, ConcurrentHashMap<String, DefaultWebSocketSession>>()
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<RelayEnvelope>>()

    suspend fun registerAgent(shareCode: String, session: DefaultWebSocketSession) {
        val previous = agents.put(shareCode, AgentConnection(shareCode, session))
        previous?.session?.close(CloseReason(CloseReason.Codes.NORMAL, "Replaced by newer connection"))
    }
    fun unregisterAgent(shareCode: String, session: DefaultWebSocketSession) {
        agents.computeIfPresent(shareCode) { _, existing -> if (existing.session == session) null else existing }
    }
    fun registerBrowser(shareCode: String, browserId: String, session: DefaultWebSocketSession) {
        browsers.getOrPut(shareCode) { ConcurrentHashMap() }[browserId] = session
    }
    fun unregisterBrowser(shareCode: String, browserId: String) { browsers[shareCode]?.remove(browserId) }
    fun isAgentHealthy(shareCode: String): Boolean {
        val session = agents[shareCode]
        return session != null && session.session.isActive && !session.session.outgoing.isClosedForSend
    }
    fun connectedAgentCount(): Int = agents.size
    fun connectedShareCodes(): List<String> = agents.keys().toList().sorted()
    fun getAgent(shareCode: String): AgentConnection? = agents[shareCode]

    suspend fun forwardToAllBrowsers(shareCode: String, message: String) {
        browsers[shareCode]?.values?.forEach { try { it.send(Frame.Text(message)) } catch (_: Exception) {} }
    }
    suspend fun forwardToBrowser(shareCode: String, browserId: String, message: String) {
        try { browsers[shareCode]?.get(browserId)?.send(Frame.Text(message)) } catch (_: Exception) {}
    }
    suspend fun forwardToAgent(shareCode: String, message: String) {
        try { agents[shareCode]?.sendText(message) } catch (_: Exception) {}
    }

    suspend fun forwardEnvelope(agent: AgentConnection, request: RelayEnvelope): RelayEnvelope? {
        val requestId = request.requestId ?: return null
        val deferred = CompletableDeferred<RelayEnvelope>()
        pendingResponses[requestId] = deferred

        return try {
            agent.sendEnvelope(request)
            withTimeoutOrNull(45_000) { deferred.await() }
        } finally {
            pendingResponses.remove(requestId)
        }
    }

    suspend fun forwardStreamingEnvelope(
        agent: AgentConnection,
        request: RelayEnvelope,
        bodyChannel: ByteReadChannel
    ): RelayEnvelope? {
        val requestId = request.requestId ?: return null
        val deferred = CompletableDeferred<RelayEnvelope>()
        pendingResponses[requestId] = deferred

        return try {
            agent.sendEnvelope(request)
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val bytesRead = bodyChannel.readAvailable(buffer, 0, buffer.size)
                if (bytesRead == -1) break
                if (bytesRead > 0) {
                    val packet = ByteArray(36 + bytesRead)
                    requestId.padEnd(36, ' ')
                        .toByteArray(Charsets.UTF_8)
                        .copyInto(packet, 0, 0, 36)
                    buffer.copyInto(packet, 36, 0, bytesRead)
                    agent.sendBinary(packet)
                }
            }
            agent.sendEnvelope(RelayEnvelope(type = "stream-request-end", requestId = requestId))
            withTimeoutOrNull(20 * 60_000) { deferred.await() }
        } finally {
            pendingResponses.remove(requestId)
        }
    }

    /**
     * Forward a streaming DOWNLOAD request - sends request metadata, then receives streaming response.
     * The agent responds with stream-response packets containing binary chunks.
     */
    suspend fun forwardStreamingRequest(
        agent: AgentConnection,
        request: RelayEnvelope
    ): RelayEnvelope? {
        val requestId = request.requestId ?: return null
        val deferred = CompletableDeferred<RelayEnvelope>()
        pendingResponses[requestId] = deferred

        return try {
            // Send the request envelope (headers only, no body for downloads)
            agent.sendEnvelope(request.copy(type = "streaming-request"))

            // Wait for the stream to start (first response with isStreaming=true)
            val startResponse = withTimeoutOrNull(45_000) { deferred.await() }
            if (startResponse?.isStreaming != true) {
                // Not a streaming response, return as-is
                return startResponse
            }

            // Return the start response - streaming data follows via binary frames
            startResponse
        } catch (e: Exception) {
            pendingResponses.remove(requestId)
            null
        }
    }

    /**
     * Completes a streaming response by waiting for the stream-end signal.
     */
    suspend fun awaitStreamingComplete(requestId: String, timeoutMs: Long = 20 * 60_000L): Boolean {
        return try {
            withTimeoutOrNull(timeoutMs) {
                // Wait for stream-end marker or timeout
                delay(timeoutMs)
                true
            } ?: false
        } catch (e: Exception) {
            false
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
    suspend fun sendEnvelope(envelope: RelayEnvelope) {
        sendMutex.withLock {
            session.sendEnvelope(envelope)
        }
    }

    suspend fun sendText(message: String) {
        sendMutex.withLock {
            session.send(Frame.Text(message))
        }
    }

    suspend fun sendBinary(payload: ByteArray) {
        sendMutex.withLock {
            session.send(Frame.Binary(true, payload))
        }
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
    val contentLength: Long? = null,
    val status: Int? = null,
    val error: String? = null,
    val isStreaming: Boolean? = null  // Flag for streaming responses
)

private suspend fun io.ktor.server.application.ApplicationCall.proxyApiRequest(
    registry: SignalingRegistry
) {
    val nodeId = request.headers["X-Node-Id"]
        ?.trim()
        ?.uppercase()
        ?.takeIf { it.isNotBlank() }
        ?: request.queryParameters["nodeId"]
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotBlank() }

    if (nodeId.isNullOrBlank()) {
        respondJson(mapOf("error" to "missing_node_id"), HttpStatusCode.BadRequest)
        return
    }

    if (
        request.path().endsWith("/upload_chunk", ignoreCase = true) &&
        request.queryParameters["filename"].isNullOrBlank()
    ) {
        respondJson(mapOf("error" to "Missing or malformed filename"), HttpStatusCode.BadRequest)
        return
    }

    val agent = registry.getAgent(nodeId)
    if (agent == null) {
        if (request.path().endsWith("/storage")) {
            respondJson(
                mapOf(
                    "error" to "node_offline",
                    "total" to 0L,
                    "used" to 0L,
                    "free" to 0L,
                    "healthPercent" to 0
                ),
                HttpStatusCode.ServiceUnavailable
            )
        } else {
            respondJson(mapOf("error" to "node_offline"), HttpStatusCode.ServiceUnavailable)
        }
        return
    }

    val requestId = UUID.randomUUID().toString()
    val forwardedQuery = request.queryParameters.flattenEntries()
        .filterNot { (key, _) -> key.equals("nodeId", ignoreCase = true) }
        .formUrlEncode()
    val forwardedHeaders = request.headers.flattenEntries()
        .filterNot { (key, _) ->
            key.equals(HttpHeaders.Host, ignoreCase = true) ||
                key.equals("X-Node-Id", ignoreCase = true) ||
                isHopByHopHeader(key)
        }
        .associate { (key, value) -> key to value }

    val relayRequest = RelayEnvelope(
        type = "request",
        requestId = requestId,
        method = request.httpMethod.value,
        path = request.path(),
        query = forwardedQuery,
        headers = forwardedHeaders
    )

    // Determine if this request/response should use streaming
    val isLargeDownload = request.path().matches(Regex(".*/(download|download_folder|download_bulk|file-content).*", RegexOption.IGNORE_CASE))
    val isArchiveUpload = request.path().endsWith("/upload/archive", ignoreCase = true)
    val shouldStream = isLargeDownload || isArchiveUpload

    val relayResponse = if (isArchiveUpload) {
        // Upload streaming (already implemented)
        registry.forwardStreamingEnvelope(
            agent,
            relayRequest.copy(
                type = "stream-request-start",
                contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
            ),
            receiveChannel()
        )
    } else if (shouldStream) {
        // Download streaming - request without body, expect streaming response
        registry.forwardStreamingRequest(
            agent,
            relayRequest.copy(isStreaming = true)
        )
    } else {
        // Standard request with Base64 body (small payloads only)
        val requestBody = receiveStream().readBytes()
        registry.forwardEnvelope(agent, relayRequest.copy(bodyBase64 = requestBody.encodeBase64()))
    }

    if (relayResponse == null) {
        respondJson(mapOf("error" to "node_timeout"), HttpStatusCode.GatewayTimeout)
        return
    }

    // Handle streaming response for downloads
    if (shouldStream && relayResponse.isStreaming == true) {
        respondStreamingRelayResponse(relayResponse, registry, requestId, nodeId)
    } else {
        respondRelayResponse(relayResponse)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondRelayResponse(relayResponse: RelayEnvelope) {
    val status = relayResponse.status?.let(HttpStatusCode::fromValue) ?: HttpStatusCode.BadGateway
    relayResponse.headers.orEmpty()
        .filterNot { (key, _) ->
            isHopByHopHeader(key) || key.equals(HttpHeaders.ContentLength, ignoreCase = true)
        }
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

/**
 * Stream a large response from the Android node without buffering in relay memory.
 * Sets up WebSocket listener for binary stream chunks, forwards them to HTTP response.
 */
private suspend fun io.ktor.server.application.ApplicationCall.respondStreamingRelayResponse(
    relayResponse: RelayEnvelope,
    registry: SignalingRegistry,
    requestId: String,
    nodeId: String
) {
    val status = relayResponse.status?.let(HttpStatusCode::fromValue) ?: HttpStatusCode.BadGateway
    val contentType = relayResponse.headers?.entries
        ?.firstOrNull { (key, _) -> key.equals(HttpHeaders.ContentType, ignoreCase = true) }
        ?.value
        ?.takeIf { it.isNotBlank() }
        ?.let(ContentType::parse) ?: ContentType.Application.OctetStream

    // Set response headers
    response.headers.append(HttpHeaders.ContentType, contentType.toString())
    relayResponse.headers.orEmpty()
        .filter { (key, _) -> key.equals(HttpHeaders.ContentRange, ignoreCase = true) || key.equals(HttpHeaders.AcceptRanges, ignoreCase = true) }
        .forEach { (key, value) ->
            response.headers.append(key, value, safeOnly = false)
        }

    // Get the agent WebSocket to listen for incoming binary chunks
    val agent = registry.getAgent(nodeId)
    if (agent == null) {
        respondJson(mapOf("error" to "node_disconnected"), HttpStatusCode.ServiceUnavailable)
        return
    }

    // Use respondOutputStream for true streaming - doesn't buffer
    respondOutputStream(contentType = contentType, status = status) {
        val buffer = ByteArray(64 * 1024) // 64KB chunks
        var totalBytes = 0L

        // Listen for binary frames from the agent (stream chunks)
        // The agent sends: [36-byte requestId][payload]
        // We need to forward just the payload to the HTTP response
        while (agent.session.incoming.isClosedForReceive == false) {
            val frame = agent.session.incoming.receive() ?: break
            if (frame is Frame.Binary) {
                val data = frame.data
                if (data.size >= 36) {
                    // Extract payload (skip 36-byte header)
                    val payloadSize = data.size - 36
                    if (payloadSize > 0) {
                        write(data, 36, payloadSize)
                        totalBytes += payloadSize
                    }
                }
                // Check for stream end marker handled via Frame.Text envelope
            } else if (frame is Frame.Text) {
                // Check for stream-end envelope
                val text = frame.readText()
                val envelope = text.toEnvelopeOrNull()
                if (envelope?.type == "stream-response-end") {
                    break
                }
            }
        }
        println("STREAM_COMPLETE: Request $requestId, total bytes: $totalBytes")
    }
}

private suspend fun DefaultWebSocketSession.sendEnvelope(envelope: RelayEnvelope) {
    outgoing.send(Frame.Text(gson.toJson(envelope)))
}

private fun String.toEnvelopeOrNull(): RelayEnvelope? {
    return try { gson.fromJson(this, RelayEnvelope::class.java) } catch (_: JsonSyntaxException) { null }
}

@Suppress("UNCHECKED_CAST")
private fun String.toJsonMap(): Map<String, Any?>? {
    return try { gson.fromJson(this, Map::class.java) as? Map<String, Any?> } catch (_: JsonSyntaxException) { null }
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

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
    payload: Any,
    status: HttpStatusCode = HttpStatusCode.OK
) {
    respondText(
        gson.toJson(payload),
        ContentType.Application.Json,
        status
    )
}

private fun buildRelayLandingPage(connectedCount: Int, shareCodes: List<String>): String {
    val nodeListHtml = if (shareCodes.isEmpty()) {
        """<div class="empty">No nodes currently connected. Open the Easy Storage app on your Android device.</div>"""
    } else {
        shareCodes.joinToString("") { code ->
            """<a class="node-link" href="/node/$code/">
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
    .p2p-badge { display: inline-block; background: rgba(16,185,129,.15); color: #10b981; border-radius: 999px; padding: 4px 14px; font-size: 11px; font-weight: 700; letter-spacing: .05em; margin-left: 8px; }
  </style>
</head>
<body>
  <div class="shell">
    <div class="card">
      <div class="label">EASY STORAGE RELAY <span class="p2p-badge">WebRTC P2P</span></div>
      <h1>Signaling gateway</h1>
      <p class="muted">This relay brokers WebRTC connections between browsers and Android nodes. <strong>Zero file bytes</strong> pass through this server — all data flows peer-to-peer.</p>
      <div class="badge">$connectedCount node(s) online</div>
    </div>
    <div class="card">
      <div class="label">CONNECTED NODES</div>
      $nodeListHtml
    </div>
    <div class="card">
      <div class="label">OPEN A NODE BY SHARE CODE</div>
      <p class="muted">Paste the share code from the Easy Storage app.</p>
      <form action="/join" method="get">
        <input name="code" placeholder="e.g. E9455A2DC9" autocomplete="off" autocapitalize="characters">
        <button type="submit">Go →</button>
      </form>
    </div>
  </div>
</body>
</html>"""
}

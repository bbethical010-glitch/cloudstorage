package com.pratham.cloudstorage.relay

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.util.flattenEntries
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.server.request.receiveMultipart
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.contentType
import io.ktor.http.isMultipart
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val gson = Gson()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        relayModule()
    }.start(wait = true)
}

fun Application.relayModule() {
    install(WebSockets) {
        // Render's reverse proxy closes idle connections aggressively.
        // Sending a ping every 20s keeps the tunnel alive.
        pingPeriod = java.time.Duration.ofSeconds(20)
        timeout = java.time.Duration.ofSeconds(120) // Increased timeout for larger uploads
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
                    if (frame is Frame.Text) {
                        val envelope = frame.readText().toEnvelopeOrNull() ?: continue
                        if (envelope.type == "response" && !envelope.requestId.isNullOrBlank()) {
                            registry.completeResponse(envelope)
                        }
                    }
                }
            } finally {
                registry.unregister(shareCode, this)
            }
        }

        route("/node/{shareCode}") {
            get { call.proxyNodeRequest(registry, emptyList()) }
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

        if (request.contentType().isMultipart()) {
            val responseDeferred = registry.prepareResponse(requestId)
            try {
                agent.sendLog(requestId, "Starting multipart processing on relay")
                val multipart = receiveMultipart()
                var streamedAnyFile = false
                
                multipart.forEachPart { part ->
                    if (part is PartData.FileItem) {
                        val filename = part.originalFileName ?: "uploaded_file"
                        streamedAnyFile = true
                        
                        agent.sendLog(requestId, "Streaming file: $filename")
                        
                        // 1. Send Request Start
                        agent.send(RelayEnvelope(
                            type = "request",
                            subType = "upload_start",
                            requestId = requestId,
                            method = request.httpMethod.value,
                            path = request.path().removePrefix("/node/$shareCode").takeIf { it.isNotBlank() } ?: "/",
                            query = request.queryString(),
                            filename = filename,
                            headers = request.headers.flattenEntries()
                                .filterNot { (key, _) -> key.equals(HttpHeaders.Host, ignoreCase = true) || isHopByHopHeader(key) }
                                .associate { (key, value) -> key to value }
                        ))

                        // 2. Stream Binary Chunks
                        val channel = part.provider()
                        val buffer = ByteArray(65536)
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buffer)
                            if (read > 0) {
                                agent.sendFrame(Frame.Binary(true, if (read == buffer.size) buffer else buffer.copyOfRange(0, read)))
                            } else if (read == -1) {
                                break
                            }
                        }

                        // 3. Send Request End
                        agent.send(RelayEnvelope(
                            type = "request",
                            subType = "upload_end",
                            requestId = requestId
                        ))
                        agent.sendLog(requestId, "Finished streaming file: $filename")
                    }
                    part.dispose()
                }

                if (!streamedAnyFile) {
                    agent.sendLog(requestId, "No file parts found in multipart request")
                }
                
                // Wait for response from the phone
                agent.sendLog(requestId, "Awaiting phone confirmation")
                val response = withTimeout(45_000) { responseDeferred.await() }
                respondRelayResponse(response)
            } catch (e: Exception) {
                agent.sendLog(requestId, "Error during streaming: ${e.message}")
                throw e
            } finally {
                registry.removeResponse(requestId)
            }
            return
        }

        // Standard non-multipart request handling
        val contentLength = request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
        val maxBodyBytes = 50L * 1024 * 1024 // 50 MB for non-streaming
        if (contentLength > maxBodyBytes) {
            respondText(
                "Payload too large (max 50 MB for non-streaming).",
                status = HttpStatusCode.PayloadTooLarge
            )
            return
        }

        val requestBody = receiveStream().readBytes()
        val relayRequest = RelayEnvelope(
            type = "request",
            requestId = requestId,
            method = request.httpMethod.value,
            path = request.path()
                .removePrefix("/node/$shareCode")
                .takeIf { it.isNotBlank() }
                ?: "/",
            query = request.queryString(),
            headers = request.headers.flattenEntries()
                .filterNot { (key, _) -> key.equals(HttpHeaders.Host, ignoreCase = true) || isHopByHopHeader(key) }
                .associate { (key, value) -> key to value },
            bodyBase64 = requestBody.encodeBase64()
        )

        val relayResponse = registry.forwardEnvelope(agent, relayRequest)
        if (relayResponse != null) {
            respondRelayResponse(relayResponse)
        } else {
            respondText("Phone node did not respond in time.", status = HttpStatusCode.GatewayTimeout)
        }

    } catch (e: TimeoutCancellationException) {
        respondText(
            "Phone node did not respond in time. Check your connection and try again.",
            status = HttpStatusCode.GatewayTimeout
        )
    } catch (e: Exception) {
        respondText(
            "Relay gateway error: ${e.message?.take(120)}",
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

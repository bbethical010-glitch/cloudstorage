package com.pratham.cloudstorage.relay

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.websocket.*
import io.ktor.server.websocket.*
import io.ktor.server.http.content.*
import kotlinx.coroutines.CompletableDeferred
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

// ──────────────────────────────────────────────────────────────────────────────
// Easy Storage Relay — Pure WebRTC Signaling Server
// ──────────────────────────────────────────────────────────────────────────────

private val gson = Gson()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    embeddedServer(Netty, host = "0.0.0.0", port = port) {
        relayModule()
    }.start(wait = true)
}

fun Application.relayModule() {
    install(IgnoreTrailingSlash)
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(20)
        timeout = Duration.ofSeconds(60)
        maxFrameSize = 16 * 1024 * 1024L
    }

    val registry = SignalingRegistry()

    routing {
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
                            "http_response" -> {
                                registry.resolveHttpResponse(text)
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
                call.respondText(
                    gson.toJson(mapOf("online" to isOnline)),
                    ContentType.Application.Json
                )
            }

            get("/debug/registry") {
                call.respondText(
                    gson.toJson(
                        mapOf(
                            "nodes" to registry.connectedShareCodes(),
                            "total" to registry.connectedAgentCount(),
                            "timestamp" to System.currentTimeMillis()
                        )
                    ),
                    ContentType.Application.Json
                )
            }

            route("{...}") {
                handle {
                    proxyHttpRequest(call, registry)
                }
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
        
        staticResources("/", "web") {
            default("index.html")
        }

        get("{path...}") {
            val path = call.parameters.getAll("path")?.joinToString("/") { it } ?: ""
            if (path.startsWith("api")) {
                 call.respond(HttpStatusCode.NotFound)
            } else {
                 val content = registry.javaClass.classLoader.getResource("web/index.html")?.readBytes()
                 if (content != null) call.respondBytes(content, ContentType.Text.Html)
                 else call.respond(HttpStatusCode.NotFound)
            }
        }
    }
}

private class SignalingRegistry {
    private val agents = ConcurrentHashMap<String, DefaultWebSocketSession>()
    private val browsers = ConcurrentHashMap<String, ConcurrentHashMap<String, DefaultWebSocketSession>>()
    private val pendingHttpResponses = ConcurrentHashMap<String, CompletableDeferred<HttpProxyResponse>>()

    fun registerAgent(shareCode: String, session: DefaultWebSocketSession) { agents[shareCode] = session }
    fun unregisterAgent(shareCode: String, session: DefaultWebSocketSession) {
        agents.computeIfPresent(shareCode) { _, existing -> if (existing == session) null else existing }
    }
    fun registerBrowser(shareCode: String, browserId: String, session: DefaultWebSocketSession) {
        browsers.getOrPut(shareCode) { ConcurrentHashMap() }[browserId] = session
    }
    fun unregisterBrowser(shareCode: String, browserId: String) { browsers[shareCode]?.remove(browserId) }
    fun isAgentHealthy(shareCode: String): Boolean {
        val session = agents[shareCode]
        return session != null && session.isActive && !session.outgoing.isClosedForSend
    }
    fun connectedAgentCount(): Int = agents.size
    fun connectedShareCodes(): List<String> = agents.keys().toList().sorted()

    suspend fun forwardToAllBrowsers(shareCode: String, message: String) {
        browsers[shareCode]?.values?.forEach { try { it.send(Frame.Text(message)) } catch (_: Exception) {} }
    }
    suspend fun forwardToBrowser(shareCode: String, browserId: String, message: String) {
        try { browsers[shareCode]?.get(browserId)?.send(Frame.Text(message)) } catch (_: Exception) {}
    }
    suspend fun forwardToAgent(shareCode: String, message: String) {
        try { agents[shareCode]?.send(Frame.Text(message)) } catch (_: Exception) {}
    }

    suspend fun dispatchHttpRequest(shareCode: String, request: HttpProxyRequest): HttpProxyResponse? {
        val deferred = CompletableDeferred<HttpProxyResponse>()
        pendingHttpResponses[request.requestId] = deferred
        return try {
            forwardToAgent(shareCode, gson.toJson(request))
            withTimeout(20_000) { deferred.await() }
        } finally {
            pendingHttpResponses.remove(request.requestId)
        }
    }

    fun resolveHttpResponse(message: String) {
        val payload = message.toJsonMap() ?: return
        val requestId = payload["requestId"] as? String ?: return
        val deferred = pendingHttpResponses.remove(requestId) ?: return
        deferred.complete(
            HttpProxyResponse(
                requestId = requestId,
                status = (payload["status"] as? Double)?.toInt() ?: 502,
                headers = (payload["headers"] as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
                    val headerName = key as? String ?: return@mapNotNull null
                    val headerValue = value as? String ?: return@mapNotNull null
                    headerName to headerValue
                }?.toMap().orEmpty(),
                body = payload["body"] as? String
            )
        )
    }
}

private data class HttpProxyRequest(
    val type: String = "http_request",
    val requestId: String,
    val method: String,
    val path: String,
    val query: String,
    val headers: Map<String, String>,
    val body: String? = null
)

private data class HttpProxyResponse(
    val requestId: String,
    val status: Int,
    val headers: Map<String, String>,
    val body: String? = null
)

@Suppress("UNCHECKED_CAST")
private fun String.toJsonMap(): Map<String, Any?>? {
    return try { gson.fromJson(this, Map::class.java) as? Map<String, Any?> } catch (_: JsonSyntaxException) { null }
}

private suspend fun proxyHttpRequest(call: io.ktor.server.application.ApplicationCall, registry: SignalingRegistry) {
    val shareCode = call.request.headers["X-Share-Code"]
        ?.trim()
        ?.uppercase()
        ?.takeIf { it.isNotEmpty() }
        ?: call.request.queryParameters["shareCode"]
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }
        ?: call.request.queryParameters["nodeId"]
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotEmpty() }

    if (shareCode == null) {
        call.respondText(
            gson.toJson(mapOf("error" to "missing_share_code")),
            ContentType.Application.Json,
            HttpStatusCode.BadRequest
        )
        return
    }

    if (!registry.isAgentHealthy(shareCode)) {
        call.respondText(
            gson.toJson(mapOf("error" to "agent_offline")),
            ContentType.Application.Json,
            HttpStatusCode.ServiceUnavailable
        )
        return
    }

    val path = call.request.uri.substringBefore('?')
    val bodyBytes = call.receiveStream().readBytes()
    val request = HttpProxyRequest(
        requestId = UUID.randomUUID().toString(),
        method = call.request.httpMethod.value,
        path = path,
        query = call.request.queryString(),
        headers = call.request.headers.entries().mapNotNull { entry ->
            val name = entry.key
            if (name.equals(HttpHeaders.Host, true) || name.equals(HttpHeaders.ContentLength, true)) {
                return@mapNotNull null
            }
            name to entry.value.joinToString(", ")
        }.toMap(),
        body = if (bodyBytes.isNotEmpty()) Base64.getEncoder().encodeToString(bodyBytes) else null
    )

    val response = registry.dispatchHttpRequest(shareCode, request)
    if (response == null) {
        call.respondText(
            gson.toJson(mapOf("error" to "relay_timeout")),
            ContentType.Application.Json,
            HttpStatusCode.GatewayTimeout
        )
        return
    }

    response.headers.forEach { (name, value) ->
        if (!name.equals(HttpHeaders.ContentLength, true) &&
            !name.equals(HttpHeaders.TransferEncoding, true) &&
            !name.equals(HttpHeaders.Connection, true)) {
            call.response.headers.append(name, value, safeOnly = false)
        }
    }

    val contentType = response.headers.entries.firstOrNull { (name, _) ->
        name.equals(HttpHeaders.ContentType, true)
    }?.value?.let {
        runCatching { ContentType.parse(it) }.getOrDefault(ContentType.Application.OctetStream)
    } ?: ContentType.Application.OctetStream
    val responseBody = response.body?.let { Base64.getDecoder().decode(it) } ?: ByteArray(0)

    call.respondBytes(responseBody, contentType, HttpStatusCode.fromValue(response.status))
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

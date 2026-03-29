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
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

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
        maxFrameSize = 512 * 1024L
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

            get("{...}") {
                call.respondText(
                    gson.toJson(mapOf("error" to "API endpoint not found")),
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound
                )
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
}

@Suppress("UNCHECKED_CAST")
private fun String.toJsonMap(): Map<String, Any?>? {
    return try { gson.fromJson(this, Map::class.java) as? Map<String, Any?> } catch (_: JsonSyntaxException) { null }
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

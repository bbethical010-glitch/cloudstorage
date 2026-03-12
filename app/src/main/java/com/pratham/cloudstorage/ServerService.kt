package com.pratham.cloudstorage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ServerService : Service() {

    private var server: ApplicationEngine? = null
    private var relayTunnelClient: RelayTunnelClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val ACTION_START_SERVER = "ACTION_START_SERVER"
        const val ACTION_STOP_SERVER = "ACTION_STOP_SERVER"
        const val EXTRA_URI = "EXTRA_URI"
        const val EXTRA_SHARE_CODE = "EXTRA_SHARE_CODE"
        const val EXTRA_RELAY_BASE_URL = "EXTRA_RELAY_BASE_URL"
        private const val CHANNEL_ID = "ServerChannelId"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                val shareCode = intent.getStringExtra(EXTRA_SHARE_CODE).orEmpty()
                val relayBaseUrl = intent.getStringExtra(EXTRA_RELAY_BASE_URL).orEmpty()
                if (uri != null) {
                    startForegroundNode(uri, shareCode, relayBaseUrl)
                }
            }

            ACTION_STOP_SERVER -> stopServer()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNode(rootUri: Uri, shareCode: String, relayBaseUrl: String) {
        createNotificationChannel()

        val publicUrl = buildRelayBrowserUrl(relayBaseUrl, shareCode)
        val localUrl = buildLocalAccessUrl(DEFAULT_PORT)
        val notificationText = publicUrl ?: localUrl ?: "Node online"

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("External storage node online")
            .setContentText(notificationText)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        startKtorServer(rootUri, shareCode, relayBaseUrl)
        startRelayTunnel(relayBaseUrl, shareCode, rootUri)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Node Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startKtorServer(rootUri: Uri, shareCode: String, relayBaseUrl: String) {
        if (server != null) return

        scope.launch {
            server = embeddedServer(Netty, port = DEFAULT_PORT) {
                routing {
                    get("/") {
                        val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                        if (root == null || !root.canRead()) {
                            call.respond(HttpStatusCode.NotFound, "Directory not accessible")
                            return@get
                        }

                        call.respondText(
                            renderHomePage(
                                root = root,
                                shareCode = shareCode,
                                relayBaseUrl = relayBaseUrl,
                                accessUrls = buildLocalAccessUrls(DEFAULT_PORT)
                            ),
                            ContentType.Text.Html
                        )
                    }

                    get("/api/status") {
                        val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                        if (root != null && root.canRead()) {
                            call.respondText("{\"status\":\"online\"}", ContentType.Application.Json)
                        } else {
                            call.respond(HttpStatusCode.ServiceUnavailable, "{\"status\":\"offline\"}")
                        }
                    }

                    get("/api/files") {
                        val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                        if (root != null && root.canRead()) {
                            val filesList = root.listFiles()
                                .joinToString(prefix = "[", postfix = "]") { file ->
                                    "{\"name\":\"${file.name}\",\"isDirectory\":${file.isDirectory}}"
                                }
                            call.respondText(filesList, ContentType.Application.Json)
                        } else {
                            call.respond(HttpStatusCode.NotFound, "Directory not accessible")
                        }
                    }

                    get("/api/download") {
                        val fileName = call.request.queryParameters["file"]
                        if (fileName == null) {
                            call.respond(HttpStatusCode.BadRequest, "Missing file parameter")
                            return@get
                        }

                        val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                        val targetFile = root?.listFiles()?.find { it.name == fileName }

                        if (targetFile != null && targetFile.canRead()) {
                            val mimeType = contentResolver.getType(targetFile.uri) ?: "application/octet-stream"
                            call.respondOutputStream(
                                contentType = ContentType.parse(mimeType),
                                status = HttpStatusCode.OK
                            ) {
                                contentResolver.openInputStream(targetFile.uri)?.use { input ->
                                    input.copyTo(this)
                                }
                            }
                        } else {
                            call.respond(HttpStatusCode.NotFound, "File not found or unreadable")
                        }
                    }

                    post("/api/upload") {
                        call.handleUpload(rootUri, redirectToHome = false)
                    }

                    post("/upload") {
                        call.handleUpload(rootUri, redirectToHome = true)
                    }
                }
            }

            server?.start(wait = true)
        }
    }

    private fun stopServer() {
        relayTunnelClient?.stop()
        relayTunnelClient = null
        server?.stop(1000, 2000)
        server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startRelayTunnel(relayBaseUrl: String, shareCode: String, rootUri: Uri) {
        if (relayBaseUrl.isBlank() || shareCode.isBlank()) return

        // Don't restart the tunnel if already connected with the same config.
        val current = relayTunnelClient
        if (current != null && current.relayBaseUrl == relayBaseUrl && current.shareCode == shareCode) {
            return
        }

        current?.stop()
        relayTunnelClient = RelayTunnelClient(
            context = this,
            relayBaseUrl = relayBaseUrl,
            shareCode = shareCode,
            rootUri = rootUri
        ).also { it.start() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    private suspend fun io.ktor.server.application.ApplicationCall.handleUpload(
        rootUri: Uri,
        redirectToHome: Boolean
    ) {
        val multipart = receiveMultipart()
        val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)

        if (root == null || !root.canWrite()) {
            respond(HttpStatusCode.InternalServerError, "Storage not writable")
            return
        }

        multipart.forEachPart { part: PartData ->
            if (part is PartData.FileItem) {
                val fileName = part.originalFileName ?: "uploaded_file"
                root.findFile(fileName)?.delete()

                val newFile = root.createFile(
                    part.contentType?.toString() ?: "application/octet-stream",
                    fileName
                )

                if (newFile != null) {
                    // Stream upload to storage. provider() returns a ByteReadPacket;
                    // we read it and write straight to the output stream.
                    contentResolver.openOutputStream(newFile.uri)?.use { output ->
                        val input = part.provider()
                        try {
                            output.write(input.readBytes())
                        } finally {
                            input.close()
                        }
                    }
                }
            }
            part.dispose()
        }

        if (redirectToHome) {
            // history.back() returns to the node console URL in the browser.
            // respondRedirect("/") would go to the relay root (404), so we use JS instead.
            respondText(
                """<!DOCTYPE html><html><head><meta charset="utf-8">
                <style>body{font-family:monospace;background:#061018;color:#e2edf7;display:flex;
                align-items:center;justify-content:center;height:100vh;margin:0}
                .msg{text-align:center}.tick{font-size:48px;color:#3dd2ff}</style></head>
                <body><div class="msg"><div class="tick">✓</div>
                <p>Upload successful</p><p style="color:#87a0b5;font-size:12px">Returning…</p></div>
                <script>setTimeout(function(){history.back()},1200);</script></body></html>""",
                io.ktor.http.ContentType.Text.Html
            )
        } else {
            respondText("Upload successful")
        }
    }
}

private fun renderHomePage(
    root: DocumentFile,
    shareCode: String,
    relayBaseUrl: String,
    accessUrls: List<String>
): String {
    val publicUrl = buildRelayBrowserUrl(relayBaseUrl, shareCode)
    val filesMarkup = root.listFiles().joinToString(separator = "") { file ->
        val name = escapeHtml(file.name ?: "Unnamed file")
        val type = if (file.isDirectory) "DIR" else "FILE"
        val action = if (file.isDirectory) {
            "<span class=\"pill\">Folder traversal pending</span>"
        } else {
            val encodedName = encodeUrlSegment(file.name ?: "")
            "<a class=\"download\" href=\"/api/download?file=$encodedName\">DOWNLOAD</a>"
        }
        """
        <div class="file-row">
          <div class="meta">
            <div class="file-type">$type</div>
            <div class="file-name">$name</div>
          </div>
          $action
        </div>
        """.trimIndent()
    }.ifEmpty {
        "<div class=\"empty\">No files detected in the mounted storage.</div>"
    }

    val localMarkup = accessUrls.joinToString(separator = "") { url ->
        "<div class=\"line\">${escapeHtml(url)}</div>"
    }.ifEmpty {
        "<div class=\"line\">No private LAN route detected</div>"
    }

    val publicMarkup = publicUrl?.let {
        "<div class=\"line\">${escapeHtml(it)}</div>"
    } ?: "<div class=\"line\">Relay endpoint not configured</div>"

    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Easy Storage Cloud Node</title>
          <style>
            :root {
              color-scheme: dark;
              --bg: #061018;
              --panel: #0c1622;
              --line: #18384f;
              --text: #e2edf7;
              --muted: #87a0b5;
              --accent: #3dd2ff;
              --accent2: #4bf1c8;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
              background:
                radial-gradient(circle at top right, rgba(61, 210, 255, .12), transparent 28%),
                linear-gradient(180deg, #040a10, var(--bg));
              color: var(--text);
              padding: 24px;
            }
            .shell {
              max-width: 960px;
              margin: 0 auto;
              display: grid;
              gap: 18px;
            }
            .card {
              border: 1px solid var(--line);
              background: rgba(12, 22, 34, .95);
              border-radius: 24px;
              padding: 20px;
              box-shadow: 0 20px 50px rgba(0,0,0,.35);
            }
            .title {
              font-size: clamp(30px, 6vw, 48px);
              line-height: 1.03;
              margin: 0 0 8px;
            }
            .muted {
              color: var(--muted);
              line-height: 1.6;
            }
            .label {
              color: var(--accent2);
              letter-spacing: .08em;
              font-size: 12px;
              font-weight: 700;
              margin-bottom: 12px;
            }
            .grid {
              display: grid;
              gap: 18px;
              grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
            }
            .line {
              border: 1px solid var(--line);
              border-radius: 14px;
              padding: 12px;
              margin-top: 10px;
              color: white;
              word-break: break-all;
            }
            .file-row {
              display: flex;
              justify-content: space-between;
              gap: 12px;
              align-items: center;
              border: 1px solid var(--line);
              border-radius: 16px;
              padding: 14px;
              margin-top: 10px;
              background: rgba(7, 16, 24, .92);
            }
            .meta {
              display: grid;
              gap: 4px;
            }
            .file-type {
              color: #81e0ff;
              font-size: 12px;
            }
            .file-name {
              font-size: 16px;
              font-weight: 700;
            }
            .download, button {
              display: inline-flex;
              align-items: center;
              justify-content: center;
              min-height: 42px;
              padding: 0 16px;
              border-radius: 12px;
              text-decoration: none;
              border: 1px solid var(--line);
              color: white;
              background: linear-gradient(135deg, #0f7896, #18536f);
              font-weight: 700;
              cursor: pointer;
            }
            input[type=file] {
              width: 100%;
              margin: 12px 0;
              color: var(--text);
            }
            .pill {
              display: inline-block;
              padding: 8px 12px;
              border-radius: 999px;
              background: rgba(61, 210, 255, .12);
              color: #9be8ff;
            }
          </style>
        </head>
        <body>
          <div class="shell">
            <div class="card">
              <div class="label">EDGE NODE CONSOLE</div>
              <h1 class="title">Mounted drive is serving directly</h1>
              <div class="muted">The drive attached to the phone remains the storage server. This console is the node surface that a LAN client or public relay would reach.</div>
            </div>

            <div class="grid">
              <div class="card">
                <div class="label">NODE METADATA</div>
                <div class="line">share_code = ${escapeHtml(shareCode.ifBlank { "not_set" })}</div>
                <div class="line">drive_name = ${escapeHtml(root.name ?: "selected_storage")}</div>
              </div>
              <div class="card" style="border-left: 4px solid var(--accent)">
                <div class="label" style="color: var(--accent)">PUBLIC RELAY ACCESS</div>
                <div class="muted" style="font-size: 11px; margin-bottom: 8px">Use for sharing links over the internet. Supports large streaming uploads.</div>
                $publicMarkup
              </div>
            </div>

            <div class="card" style="border-left: 4px solid var(--accent2)">
              <div class="label" style="color: var(--accent2)">DIRECT PRIVATE LAN (FAST)</div>
              <div class="muted" style="font-size: 11px; margin-bottom: 8px">Use when on the same Wi-Fi. Fast and proximity-based.</div>
              $localMarkup
            </div>

            <div class="card">
              <div class="label">UPLOAD INTO DRIVE</div>
              <form id="upload-form" method="post" enctype="multipart/form-data">
                <input type="file" id="file-input" name="file" multiple>
                <button type="submit">UPLOAD TO NODE</button>
              </form>
            </div>

            <div class="card">
              <div class="label">FILES</div>
              $filesMarkup
            </div>
          </div>

          <script>
            (function() {
              const path = window.location.pathname;
              if (path.includes('/node/')) {
                // Determine base path: /node/{shareCode}
                const segments = path.split('/');
                const nodeIdx = segments.indexOf('node');
                if (nodeIdx !== -1 && segments.length > nodeIdx + 1) {
                  const base = segments.slice(0, nodeIdx + 2).join('/');
                  
                  // Update Upload Form
                  const form = document.getElementById('upload-form');
                  if (form) {
                    const currentAction = form.getAttribute('action');
                    if (currentAction && !currentAction.startsWith(base)) {
                      form.action = base + (currentAction.startsWith('/') ? '' : '/') + currentAction;
                    }
                  }

                  // Update Download Links
                  const downloads = document.querySelectorAll('a.download');
                  downloads.forEach(link => {
                    const href = link.getAttribute('href');
                    if (href && !href.startsWith(base)) {
                      link.href = base + (href.startsWith('/') ? '' : '/') + href;
                    }
                  });
                }
              }
            })();
          </script>
        </body>
        </html>
    """.trimIndent()
}

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

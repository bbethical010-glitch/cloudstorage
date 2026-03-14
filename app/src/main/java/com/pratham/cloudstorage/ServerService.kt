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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.core.readBytes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.InputStream
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.utils.io.core.readAvailable
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
        const val EXTRA_CONSOLE_PASSWORD = "EXTRA_CONSOLE_PASSWORD"
        private const val CHANNEL_ID = "ServerChannelId"
        
        val tunnelStatusFlow = kotlinx.coroutines.flow.MutableStateFlow(TunnelStatus.Offline)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                val shareCode = intent.getStringExtra(EXTRA_SHARE_CODE).orEmpty()
                val relayBaseUrl = intent.getStringExtra(EXTRA_RELAY_BASE_URL).orEmpty()
                val consolePassword = intent.getStringExtra(EXTRA_CONSOLE_PASSWORD)
                if (uri != null) {
                    startForegroundNode(uri, shareCode, relayBaseUrl, consolePassword)
                }
            }

            ACTION_STOP_SERVER -> stopServer()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNode(rootUri: Uri, shareCode: String, relayBaseUrl: String, consolePassword: String?) {
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
        startKtorServer(rootUri, shareCode, relayBaseUrl, consolePassword)
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

    private fun startKtorServer(rootUri: Uri, shareCode: String, relayBaseUrl: String, consolePassword: String?) {
        if (server != null) return

        scope.launch {
            server = embeddedServer(Netty, port = DEFAULT_PORT) {
                routing {
                    
                    fun io.ktor.server.application.ApplicationCall.hasValidAuth(): Boolean {
                        if (consolePassword.isNullOrBlank()) return true
                        val authHeader = request.headers["Authorization"] ?: return false
                        val token = authHeader.removePrefix("Bearer ").trim()
                        return token == consolePassword
                    }

                    route("/api") {
                        get("/status") {
                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                            if (root != null && root.canRead()) {
                                call.respondText("{\"status\":\"online\"}", ContentType.Application.Json)
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable, "{\"status\":\"offline\"}")
                            }
                        }

                        get("/storage") {
                            if (!call.hasValidAuth()) return@get call.respond(HttpStatusCode.Unauthorized)
                            try {
                                val docId = android.provider.DocumentsContract.getTreeDocumentId(rootUri)
                                val rootId = docId?.substringBefore(":") ?: "primary"
                                val rootsUri = android.provider.DocumentsContract.buildRootUri(rootUri.authority!!, rootId)
                                
                                var availableBytes = 0L
                                var capacityBytes = 0L
                                contentResolver.query(
                                    rootsUri,
                                    arrayOf(
                                        android.provider.DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
                                        android.provider.DocumentsContract.Root.COLUMN_CAPACITY_BYTES
                                    ),
                                    null, null, null
                                )?.use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        availableBytes = cursor.getLong(0)
                                        capacityBytes = cursor.getLong(1)
                                    }
                                }
                                
                                val usedBytes = if (capacityBytes > 0) capacityBytes - availableBytes else 0L
                                call.respondText(
                                    """{"total":$capacityBytes,"free":$availableBytes,"used":$usedBytes}""",
                                    ContentType.Application.Json
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                                call.respondText("""{"total":0,"free":0,"used":0}""", ContentType.Application.Json)
                            }
                        }

                        get("/files") {
                            if (!call.hasValidAuth()) return@get call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                            if (root == null || !root.canRead()) return@get call.respond(HttpStatusCode.NotFound, "Directory not accessible")
                            
                            val path = call.request.queryParameters["path"]
                            val targetDir = resolveSafePath(root, path)
                            if (targetDir == null || !targetDir.isDirectory) return@get call.respond(HttpStatusCode.NotFound, "Invalid path")

                            // Limit and Offset
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 1000
                            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0

                            val files = targetDir.listFiles().filter { it.name?.startsWith(".Trash") != true }.drop(offset).take(limit).map { file ->
                                """{"id":"${file.uri}","name":"${file.name}","isDirectory":${file.isDirectory},"size":${file.length()},"lastModified":${file.lastModified()}}"""
                            }
                            call.respondText(files.joinToString(prefix = "[", postfix = "]"), ContentType.Application.Json)
                        }

                        get("/trash") {
                            if (!call.hasValidAuth()) return@get call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri) ?: return@get call.respond(HttpStatusCode.NotFound)
                            val trashDir = root.findFile(".Trash")
                            if (trashDir == null) return@get call.respondText("[]", ContentType.Application.Json)
                            val files = trashDir.listFiles().map { file ->
                                """{"id":"${file.uri}","name":"${file.name}","isDirectory":${file.isDirectory},"size":${file.length()},"lastModified":${file.lastModified()}}"""
                            }
                            call.respondText(files.joinToString(prefix = "[", postfix = "]"), ContentType.Application.Json)
                        }

                        post("/folder") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized)
                            val params = call.receiveParameters()
                            val path = params["path"]
                            val name = params["name"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing name")
                            
                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val targetDir = resolveSafePath(root, path) ?: return@post call.respond(HttpStatusCode.NotFound)
                            
                            val newDir = targetDir.createDirectory(name)
                            if (newDir != null) call.respond(HttpStatusCode.OK, "Created")
                            else call.respond(HttpStatusCode.InternalServerError, "Failed")
                        }

                        post("/rename") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized)
                            val params = call.receiveParameters()
                            val path = params["path"]
                            val oldName = params["oldName"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            val newName = params["newName"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            
                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val targetDir = resolveSafePath(root, path) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val targetFile = targetDir.findFile(oldName) ?: return@post call.respond(HttpStatusCode.NotFound)
                            
                            if (targetFile.renameTo(newName)) call.respond(HttpStatusCode.OK)
                            else call.respond(HttpStatusCode.InternalServerError)
                        }

                        post("/delete") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized)
                            val params = call.receiveParameters()
                            val path = params["path"]
                            val name = params["name"] ?: return@post call.respond(HttpStatusCode.BadRequest)
                            
                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val targetDir = resolveSafePath(root, path) ?: return@post call.respond(HttpStatusCode.NotFound)
                            val targetFile = targetDir.findFile(name) ?: return@post call.respond(HttpStatusCode.NotFound)
                            
                            var trashDir = root.findFile(".Trash")
                            if (trashDir == null) trashDir = root.createDirectory(".Trash")
                            if (trashDir == null) return@post call.respond(HttpStatusCode.InternalServerError)
                            
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                try {
                                    android.provider.DocumentsContract.moveDocument(contentResolver, targetFile.uri, targetDir.uri, trashDir.uri)
                                } catch (e: Exception) {
                                    targetFile.renameTo(".trash_$name")
                                }
                            } else {
                                targetFile.renameTo(".trash_$name")
                            }
                            call.respond(HttpStatusCode.OK)
                        }

                        post("/bulk_action") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized)
                            try {
                                val jsonStr: String = call.receiveText()
                                val json = org.json.JSONObject(jsonStr)
                                val action = json.optString("action")
                                val items = json.optJSONArray("items") ?: org.json.JSONArray()
                                val destPath = json.optString("destinationPath", "")

                                val root = DocumentFile.fromTreeUri(this@ServerService, rootUri) ?: return@post call.respond(HttpStatusCode.NotFound)
                                
                                var trashDir: DocumentFile? = null
                                if (action == "delete") {
                                    trashDir = root.findFile(".Trash") ?: root.createDirectory(".Trash")
                                }
                                val destDir = if (action == "move") resolveSafePath(root, destPath) else null

                                val finalTrashDir = trashDir
                                for (i in 0 until items.length()) {
                                    val item = items.getJSONObject(i)
                                    val path = item.optString("path", "")
                                    val name = item.optString("name", "")
                                    val targetDir = resolveSafePath(root, path) ?: continue
                                    val targetFile = targetDir.findFile(name) ?: continue

                                    if (action == "delete" && finalTrashDir != null) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                            try {
                                                android.provider.DocumentsContract.moveDocument(contentResolver, targetFile.uri, targetDir.uri, finalTrashDir.uri)
                                            } catch (e: Exception) {
                                                targetFile.renameTo(".trash_$name")
                                            }
                                        } else {
                                            targetFile.renameTo(".trash_$name")
                                        }
                                    } else if (action == "move" && destDir != null) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                            try {
                                                android.provider.DocumentsContract.moveDocument(contentResolver, targetFile.uri, targetDir.uri, destDir.uri)
                                            } catch (e: Exception) {}
                                        }
                                    }
                                }
                                call.respond(HttpStatusCode.OK)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                call.respond(HttpStatusCode.InternalServerError)
                            }
                        }

                        get("/download") {
                            val path = call.request.queryParameters["path"]
                            val fileName = call.request.queryParameters["file"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing file")

                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                            val targetDir = resolveSafePath(root, path)
                            val targetFile = targetDir?.findFile(fileName)

                            if (targetFile != null && targetFile.canRead() && !targetFile.isDirectory) {
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

                        get("/download_folder") {
                            val path = call.request.queryParameters["path"]
                            val folderName = call.request.queryParameters["folder"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            
                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                            val targetDir = resolveSafePath(root, path)
                            val targetFolder = targetDir?.findFile(folderName)
                            
                            if (targetFolder != null && targetFolder.isDirectory) {
                                call.respondOutputStream(contentType = ContentType.parse("application/zip"), status = HttpStatusCode.OK) {
                                    val zipOut = ZipOutputStream(this)
                                    fun addFolderToZip(folder: DocumentFile, basePath: String) {
                                        folder.listFiles().forEach { file ->
                                            val entryName = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
                                            if (file.isDirectory) {
                                                zipOut.putNextEntry(ZipEntry("$entryName/"))
                                                zipOut.closeEntry()
                                                addFolderToZip(file, entryName ?: "")
                                            } else {
                                                zipOut.putNextEntry(ZipEntry(entryName))
                                                contentResolver.openInputStream(file.uri)?.use { it.copyTo(zipOut) }
                                                zipOut.closeEntry()
                                            }
                                        }
                                    }
                                    addFolderToZip(targetFolder, "")
                                    zipOut.finish()
                                }
                            } else {
                                call.respond(HttpStatusCode.NotFound)
                            }
                        }

                        get("/download_bulk") {
                            val itemsJsonStr = call.request.queryParameters["items"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                            val items = org.json.JSONArray(itemsJsonStr)
                            
                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri) ?: return@get call.respond(HttpStatusCode.NotFound)
                            
                            call.respondOutputStream(contentType = ContentType.parse("application/zip"), status = HttpStatusCode.OK) {
                                val zipOut = ZipOutputStream(this)
                                
                                fun addFolderToZip(folder: DocumentFile, basePath: String) {
                                    folder.listFiles().forEach { file ->
                                        val entryName = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
                                        if (file.isDirectory) {
                                            zipOut.putNextEntry(ZipEntry("$entryName/"))
                                            zipOut.closeEntry()
                                            addFolderToZip(file, entryName ?: "")
                                        } else {
                                            zipOut.putNextEntry(ZipEntry(entryName))
                                            contentResolver.openInputStream(file.uri)?.use { it.copyTo(zipOut) }
                                            zipOut.closeEntry()
                                        }
                                    }
                                }

                                for (i in 0 until items.length()) {
                                    val item = items.getJSONObject(i)
                                    val path = item.optString("path", "")
                                    val name = item.optString("name", "")
                                    val targetDir = resolveSafePath(root, path) ?: continue
                                    val targetFile = targetDir.findFile(name) ?: continue
                                    
                                    if (targetFile.isDirectory) {
                                        zipOut.putNextEntry(ZipEntry("${targetFile.name}/"))
                                        zipOut.closeEntry()
                                        addFolderToZip(targetFile, targetFile.name ?: "folder")
                                    } else {
                                        zipOut.putNextEntry(ZipEntry(targetFile.name))
                                        contentResolver.openInputStream(targetFile.uri)?.use { it.copyTo(zipOut) }
                                        zipOut.closeEntry()
                                    }
                                }
                                zipOut.finish()
                            }
                        }

                        post("/upload") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized)
                            val path = call.request.queryParameters["path"]
                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                            val targetDir = resolveSafePath(root, path) ?: return@post call.respond(HttpStatusCode.NotFound)
                            call.handleStreamingUpload(targetDir)
                        }
                    }

                    get("/{...}") {
                        val requestPath = call.request.path().removePrefix("/")
                        
                        // 1. Enforce trailing slash for the base relay route to fix relative asset resolution
                        val nodeBaseRegex = Regex("^node/[a-zA-Z0-9]+$")
                        if (nodeBaseRegex.matches(requestPath)) {
                            call.respondRedirect(call.request.path() + "/")
                            return@get
                        }

                        // 2. Map the request path to the actual static asset path
                        val assetPath = when {
                            requestPath.isEmpty() -> "www/index.html"
                            requestPath.matches(Regex("^node/[a-zA-Z0-9]+/?$")) -> "www/index.html"
                            requestPath.startsWith("node/") -> {
                                // Extract the path after the share code: /node/12345/assets/main.css -> assets/main.css
                                val afterShareCode = requestPath.substringAfter("node/").substringAfter("/")
                                if (afterShareCode.isEmpty()) "www/index.html" else "www/$afterShareCode"
                            }
                            else -> "www/$requestPath"
                        }
                        
                        fun getContentType(path: String): ContentType {
                            return when {
                                path.endsWith(".html") -> ContentType.Text.Html
                                path.endsWith(".css") -> ContentType.Text.CSS
                                path.endsWith(".js") -> ContentType.Text.JavaScript
                                path.endsWith(".svg") -> ContentType.Image.SVG
                                path.endsWith(".png") -> ContentType.Image.PNG
                                else -> ContentType.Application.OctetStream
                            }
                        }

                        try {
                            val inputStream = this@ServerService.assets.open(assetPath)
                            call.respondBytes(inputStream.readBytes(), getContentType(assetPath))
                        } catch (e: Exception) {
                            // Fallback to SPA index.html for unknown routes
                            try {
                                val indexStream = this@ServerService.assets.open("www/index.html")
                                call.respondBytes(indexStream.readBytes(), ContentType.Text.Html)
                            } catch (e2: Exception) {
                                call.respond(HttpStatusCode.NotFound)
                            }
                        }
                    }
                }
            }

            server?.start(wait = true)
        }
    }

    private fun stopServer() {
        relayTunnelClient?.stop()
        relayTunnelClient = null
        tunnelStatusFlow.value = TunnelStatus.Offline
        server?.stop(1000, 2000)
        server = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startRelayTunnel(relayBaseUrl: String, shareCode: String, rootUri: Uri) {
        if (relayBaseUrl.isBlank() || shareCode.isBlank()) {
            tunnelStatusFlow.value = TunnelStatus.Offline
            return
        }

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
            rootUri = rootUri,
            onStatusChange = { status ->
                tunnelStatusFlow.value = status
            }
        ).also { it.start() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    private suspend fun io.ktor.server.application.ApplicationCall.handleStreamingUpload(targetDir: DocumentFile) {
        val multipart = receiveMultipart()

        if (!targetDir.canWrite()) {
            respond(HttpStatusCode.InternalServerError, "Storage not writable")
            return
        }

        multipart.forEachPart { part: PartData ->
            if (part is PartData.FileItem) {
                val fileName = part.originalFileName ?: "uploaded_file"
                targetDir.findFile(fileName)?.delete()

                val newFile = targetDir.createFile(
                    part.contentType?.toString() ?: "application/octet-stream",
                    fileName
                )

                if (newFile != null) {
                    contentResolver.openOutputStream(newFile.uri)?.use { output ->

                        val input = part.provider()
                        try {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val buffer = ByteArray(64 * 1024)
                                while (!input.endOfInput) {
                                    val count = input.readAvailable(buffer)
                                    if (count > 0) {
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            input.close()
                        }
                    }
                }
            }
            part.dispose()
        }
        respond(HttpStatusCode.OK)
    }

    private fun resolveSafePath(root: DocumentFile?, path: String?): DocumentFile? {
        if (root == null) return null
        if (path.isNullOrBlank() || path == "/") return root
        var current = root
        val segments = path.split("/").filter { it.isNotBlank() }
        for (segment in segments) {
            if (segment == ".." || segment == ".") return null
            current = current?.findFile(segment) ?: return null
        }
        return current
    }
}



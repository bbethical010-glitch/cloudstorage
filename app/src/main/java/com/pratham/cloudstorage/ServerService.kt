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
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.header
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.http.HttpMethod
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
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.serialization.gson.*

class ServerService : Service() {
    private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

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
        private const val UPLOAD_CHANNEL_ID = "UploadStatusChannel"
        
        val tunnelStatusFlow = kotlinx.coroutines.flow.MutableStateFlow(TunnelStatus.Offline)
    }
    private val activeSanitizationMap = java.util.concurrent.ConcurrentHashMap<String, String>()
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
                install(CORS) {
                    allowMethod(HttpMethod.Options)
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Put)
                    allowMethod(HttpMethod.Delete)
                    allowMethod(HttpMethod.Patch)

                    allowHeader(io.ktor.http.HttpHeaders.Authorization)
                    allowHeader(io.ktor.http.HttpHeaders.ContentType)
                    allowHeader(io.ktor.http.HttpHeaders.Range)
                    allowHeader(io.ktor.http.HttpHeaders.AcceptRanges)
                    allowHeader(io.ktor.http.HttpHeaders.Accept)
                    allowHeader(io.ktor.http.HttpHeaders.AccessControlAllowOrigin)
                    allowHeader("X-Node-Id")
                    allowHeader("X-Requested-With")
                    allowHeader("pwd")

                    exposeHeader(io.ktor.http.HttpHeaders.ContentLength)
                    exposeHeader(io.ktor.http.HttpHeaders.ContentRange)
                    exposeHeader(io.ktor.http.HttpHeaders.AcceptRanges)
                    
                    anyHost() // Allow Web Console origin
                }

                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        android.util.Log.e("ServerService", "Unhandled exception in Ktor", cause)
                        // Use a literal respond here if extension method is problematic in this scope
                        val map = mutableMapOf<String, Any?>(
                            "success" to false,
                            "error" to (cause.message ?: "Internal Server Error"),
                            "code" to "INTERNAL_SERVER_ERROR"
                        )
                        call.respond(map)
                    }
                }

                install(ContentNegotiation) {
                    gson {
                        setPrettyPrinting()
                        setLenient()
                    }
                }

                routing {
                    
                    fun io.ktor.server.application.ApplicationCall.hasValidAuth(): Boolean {
                        val prefs = getSharedPreferences("NodeAuthSettings", android.content.Context.MODE_PRIVATE)
                        val accountExists = prefs.contains("password_hash")
                        val activeToken = prefs.getString("active_token", null)
                        
                        val headerToken = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

                        if (accountExists) {
                            if (activeToken.isNullOrBlank()) return false
                            return headerToken == activeToken
                        } else {
                            if (consolePassword.isNullOrBlank()) return true
                            return headerToken == consolePassword
                        }
                    }

                    route("/api") {
                        route("/auth") {
                            get("/status") {
                                val prefs = getSharedPreferences("NodeAuthSettings", android.content.Context.MODE_PRIVATE)
                                val hasAccount = prefs.contains("password_hash")
                                call.respondText("{\"hasAccount\": $hasAccount}", ContentType.Application.Json)
                            }
                            
                            post("/signup") {
                                val prefs = getSharedPreferences("NodeAuthSettings", android.content.Context.MODE_PRIVATE)
                                if (prefs.contains("password_hash")) {
                                    return@post call.respond(HttpStatusCode.Forbidden, "{\"error\": \"Account exists\"}")
                                }
                                
                                val jsonStr = call.receiveText()
                                if (jsonStr.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
                                
                                val obj = org.json.JSONObject(jsonStr)
                                val password = obj.optString("password", "")
                                
                                if (password.isBlank()) {
                                    return@post call.respond(HttpStatusCode.BadRequest, "{\"error\": \"Missing password\"}")
                                }
                                
                                val md = java.security.MessageDigest.getInstance("SHA-256")
                                val hash = java.util.Base64.getEncoder().encodeToString(md.digest(password.toByteArray()))
                                
                                val token = java.util.UUID.randomUUID().toString()
                                prefs.edit()
                                    .putString("password_hash", hash)
                                    .putString("active_token", token)
                                    .apply()
                                    
                                call.respondText("{\"token\":\"$token\"}", ContentType.Application.Json)
                            }

                            post("/login") {
                                val prefs = getSharedPreferences("NodeAuthSettings", android.content.Context.MODE_PRIVATE)
                                val existingHash = prefs.getString("password_hash", null)
                                
                                if (existingHash == null) {
                                    return@post call.respond(HttpStatusCode.NotFound, "{\"error\": \"No account\"}")
                                }
                                
                                val jsonStr = call.receiveText()
                                if (jsonStr.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)
                                
                                val obj = org.json.JSONObject(jsonStr)
                                val password = obj.optString("password", "")
                                
                                val md = java.security.MessageDigest.getInstance("SHA-256")
                                val hash = java.util.Base64.getEncoder().encodeToString(md.digest(password.toByteArray()))
                                
                                if (hash == existingHash) {
                                    val token = java.util.UUID.randomUUID().toString()
                                    prefs.edit().putString("active_token", token).apply()
                                    call.respondText("{\"token\":\"$token\"}", ContentType.Application.Json)
                                } else {
                                    call.respond(HttpStatusCode.Unauthorized, "{\"error\": \"Invalid credentials\"}")
                                }
                            }
                            
                            post("/logout") {
                                val prefs = getSharedPreferences("NodeAuthSettings", android.content.Context.MODE_PRIVATE)
                                prefs.edit().remove("active_token").apply()
                                call.respond(HttpStatusCode.OK)
                            }
                        }

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
                                val rootDoc = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                                var availableBytes = 0L
                                var capacityBytes = 0L
                                
                                val docId = android.provider.DocumentsContract.getTreeDocumentId(rootUri)
                                val uuidStr = docId?.substringBefore(":") ?: "primary"
                                
                                try {
                                    val statsManager = getSystemService(Context.STORAGE_STATS_SERVICE) as android.app.usage.StorageStatsManager
                                    val uuid = if (uuidStr.equals("primary", ignoreCase = true)) {
                                        android.os.storage.StorageManager.UUID_DEFAULT
                                    } else {
                                        java.util.UUID.fromString(uuidStr)
                                    }
                                    
                                    capacityBytes = statsManager.getTotalBytes(uuid)
                                    availableBytes = statsManager.getFreeBytes(uuid)
                                    android.util.Log.d("STORAGE_DEBUG", "StorageStatsManager success for UUID: $uuidStr ($capacityBytes total)")
                                } catch (e: Exception) {
                                    android.util.Log.e("STORAGE_DEBUG", "StorageStatsManager failed: ${e.message}, falling back to StorageVolume API")
                                    try {
                                        val storageManager = getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
                                        
                                        var targetDir: java.io.File? = null
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                            for (volume in storageManager.storageVolumes) {
                                                val volUuid = volume.uuid
                                                if (volUuid != null && volUuid.equals(uuidStr, ignoreCase = true)) {
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                        targetDir = volume.directory
                                                        break
                                                    }
                                                } else if (volUuid == null && uuidStr.equals("primary", ignoreCase = true)) {
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                        targetDir = volume.directory
                                                        break
                                                    }
                                                }
                                            }
                                        }

                                        if (targetDir != null) {
                                            val stat = android.os.StatFs(targetDir.absolutePath)
                                            availableBytes = stat.availableBlocksLong * stat.blockSizeLong
                                            capacityBytes = stat.blockCountLong * stat.blockSizeLong
                                            android.util.Log.d("STORAGE_DEBUG", "StorageVolume StatFs fallback success for path: ${targetDir.absolutePath}")
                                        } else {
                                             android.util.Log.e("STORAGE_DEBUG", "No matching StorageVolume found for UUID: $uuidStr")
                                        }
                                    } catch (fallbackEx: Exception) {
                                        android.util.Log.e("STORAGE_DEBUG", "Total failure resolving capacity: ${fallbackEx.message}")
                                    }
                                }

                                val usedBytes = if (capacityBytes > 0) capacityBytes - availableBytes else 0L
                                val healthPercent = if (capacityBytes > 0) {
                                    ((usedBytes.toDouble() / capacityBytes.toDouble()) * 100).toInt()
                                } else {
                                    0
                                }
                                call.respond(
                                    HttpStatusCode.OK,
                                    mapOf(
                                        "total" to capacityBytes,
                                        "free" to availableBytes,
                                        "used" to usedBytes,
                                        "totalBytes" to capacityBytes,
                                        "freeBytes" to availableBytes,
                                        "usedBytes" to usedBytes,
                                        "healthPercent" to healthPercent,
                                        "totalFormatted" to formatBytes(capacityBytes),
                                        "freeFormatted" to formatBytes(availableBytes),
                                        "usedFormatted" to formatBytes(usedBytes),
                                        "mountPoint" to (rootDoc?.name ?: "Unknown"),
                                        "isReady" to (rootDoc != null && rootDoc.exists())
                                    )
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("STORAGE_DEBUG", "Absolute api/storage endpoint failure", e)
                                call.respond(
                                    HttpStatusCode.OK,
                                    mapOf(
                                        "total" to 0L,
                                        "free" to 0L,
                                        "used" to 0L,
                                        "totalBytes" to 0L,
                                        "freeBytes" to 0L,
                                        "usedBytes" to 0L,
                                        "healthPercent" to 0,
                                        "totalFormatted" to "0 B",
                                        "freeFormatted" to "0 B",
                                        "usedFormatted" to "0 B",
                                        "mountPoint" to "Unknown",
                                        "isReady" to false
                                    )
                                )
                            }
                        }

                        post("/upload_event") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized)
                            val jsonStr = try { call.receiveText() } catch (e: Exception) { "" }
                            if (jsonStr.isNotBlank()) {
                                try {
                                    val obj = org.json.JSONObject(jsonStr)
                                    val filename = obj.optString("filename", "Unknown File")
                                    val progress = obj.optInt("progress", 0)
                                    triggerUploadNotification(filename, progress)
                                } catch (e: Exception) {
                                    android.util.Log.e("UploadEvent", "Failed to parse upload event JSON", e)
                                }
                            }
                            call.respond(HttpStatusCode.OK)
                        }

                        get("/transfer_status") {
                            val active = TransferManager.getActiveTransfers()
                            val globalState = TransferManager.transferState.value
                            val list = active.map { item ->
                                mapOf(
                                    "transferId" to item.id,
                                    "fileName" to item.filename,
                                    "totalBytes" to item.totalBytes,
                                    "bytesWritten" to item.bytesTransferred,
                                    "progressPercent" to (if (item.totalBytes > 0) (item.bytesTransferred * 100 / item.totalBytes).toInt() else 0),
                                    "speedBytesPerSecond" to (globalState?.speedBps ?: 0L),
                                    "isActive" to true,
                                    "isComplete" to false,
                                    "isFailed" to false,
                                    "startedAt" to item.lastUpdateMs
                                )
                            }
                            call.respond(list)
                        }

                        get("/files") {
                            if (!call.hasValidAuth()) return@get call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 1000
                            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                            val path = call.request.queryParameters["path"]

                            if (hasConsecutiveIdenticalSegments(path)) {
                                android.util.Log.w("ServerService", "Malformed path detected in /api/files: $path")
                                call.respondText("{\"error\":\"malformed_path_detected\",\"received\":\"$path\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                                return@get
                            }

                            val targetDocId = resolveSafeDocIdFast(rootUri, path)
                            if (targetDocId == null) {
                                call.respond(HttpStatusCode.NotFound, "Invalid path")
                                return@get
                            }

                            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, targetDocId)

                            val fileList = mutableListOf<Map<String, Any>>()
                            var currentIndex = 0

                            try {
                                contentResolver.query(
                                    childrenUri,
                                    arrayOf(
                                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                                        android.provider.DocumentsContract.Document.COLUMN_SIZE,
                                        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
                                    ),
                                    null, null, null
                                )?.use { cursor ->
                                    val idIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                    val nameIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                    val mimeIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                                    val sizeIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_SIZE)
                                    val modIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                                    while (cursor.moveToNext()) {
                                        val name = if (nameIdx != -1) cursor.getString(nameIdx) else continue
                                        if (name?.startsWith(".Trash") == true) continue

                                        if (currentIndex < offset) {
                                            currentIndex++
                                            continue
                                        }
                                        if (fileList.size >= limit) break

                                        val id = if (idIdx != -1) cursor.getString(idIdx) else ""
                                        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(rootUri, id)
                                        val mime = if (mimeIdx != -1) cursor.getString(mimeIdx) else ""
                                        val isDirectory = mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                                        val size = if (sizeIdx != -1) cursor.getLong(sizeIdx) else 0L
                                        val mod = if (modIdx != -1) cursor.getLong(modIdx) else 0L

                                        val itemPath = if (path.isNullOrBlank()) name else "$path/$name"
                                        fileList.add(
                                            mapOf(
                                                "id" to uri.toString(),
                                                "name" to name,
                                                "path" to itemPath,
                                                "isDirectory" to isDirectory,
                                                "size" to size,
                                                "lastModified" to mod
                                            )
                                        )
                                        currentIndex++
                                    }
                                }
                                call.respond(fileList)
                            } catch (e: Exception) {
                                android.util.Log.e("ServerService", "Error reading files", e)
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "read_failed", "details" to e.message))
                            }
                        }

                        get("/trash") {
                            if (!call.hasValidAuth()) return@get call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                            val trashDocId = resolveSafeDocIdFast(rootUri, ".Trash")
                            if (trashDocId == null) {
                                call.respondText("[]", ContentType.Application.Json)
                                return@get
                            }

                            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, trashDocId)
                            val fileList = mutableListOf<Map<String, Any>>()

                            try {
                                contentResolver.query(
                                    childrenUri,
                                    arrayOf(
                                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                                        android.provider.DocumentsContract.Document.COLUMN_SIZE,
                                        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
                                    ),
                                    null, null, null
                                )?.use { cursor ->
                                    val idIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                    val nameIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                    val mimeIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                                    val sizeIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_SIZE)
                                    val modIdx = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                                    while (cursor.moveToNext()) {
                                        val name = if (nameIdx != -1) cursor.getString(nameIdx) else continue
                                        val id = if (idIdx != -1) cursor.getString(idIdx) else ""
                                        val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(rootUri, id)
                                        val mime = if (mimeIdx != -1) cursor.getString(mimeIdx) else ""
                                        val isDirectory = mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                                        val size = if (sizeIdx != -1) cursor.getLong(sizeIdx) else 0L
                                        val mod = if (modIdx != -1) cursor.getLong(modIdx) else 0L

                                        fileList.add(
                                            mapOf(
                                                "id" to uri.toString(),
                                                "name" to name,
                                                "path" to name, // Assuming path is just name in trash or handled elsewhere
                                                "isDirectory" to isDirectory,
                                                "size" to size,
                                                "lastModified" to mod
                                            )
                                        )
                                    }
                                }
                                call.respond(fileList)
                            } catch (e: Exception) {
                                android.util.Log.e("ServerService", "Error reading trash", e)
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "read_failed", "details" to e.message))
                            }
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
                                val fileLength = targetFile.length()

                                val rangeHeader = call.request.headers[io.ktor.http.HttpHeaders.Range]
                                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                                    val rangeTrimmed = rangeHeader.removePrefix("bytes=")
                                    val parts = rangeTrimmed.split("-")
                                    val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                                    val endStr = parts.getOrNull(1)
                                    val end = if (!endStr.isNullOrEmpty()) endStr.toLong() else (fileLength - 1)
                                    val contentLength = end - start + 1

                                    call.response.header(io.ktor.http.HttpHeaders.ContentRange, "bytes $start-$end/$fileLength")
                                    call.response.header(io.ktor.http.HttpHeaders.AcceptRanges, "bytes")
                                    call.response.header(io.ktor.http.HttpHeaders.ContentLength, contentLength.toString())

                                    call.respondOutputStream(
                                        contentType = ContentType.parse(mimeType),
                                        status = HttpStatusCode.PartialContent
                                    ) {
                                        contentResolver.openInputStream(targetFile.uri)?.use { input ->
                                            if (start > 0) input.skip(start)
                                            val buffer = ByteArray(8192)
                                            var bytesRead: Int = 0
                                            var bytesToRead = contentLength
                                            while (bytesToRead > 0 && input.read(buffer, 0, minOf(buffer.size.toLong(), bytesToRead).toInt()).also { bytesRead = it } != -1) {
                                                this.write(buffer, 0, bytesRead)
                                                bytesToRead -= bytesRead
                                            }
                                        }
                                    }
                                } else {
                                    call.response.header(io.ktor.http.HttpHeaders.AcceptRanges, "bytes")
                                    call.response.header(io.ktor.http.HttpHeaders.ContentLength, fileLength.toString())
                                    call.respondOutputStream(
                                        contentType = ContentType.parse(mimeType),
                                        status = HttpStatusCode.OK
                                    ) {
                                        contentResolver.openInputStream(targetFile.uri)?.use { input ->
                                            input.copyTo(this)
                                        }
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

                        post("/folder_manifest") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized)
                            try {
                                val basePath = call.request.queryParameters["path"] ?: ""
                                val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                                if (root == null) {
                                    call.respondText("{\"error\":\"storage_unavailable\"}", ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                                    return@post
                                }
                                val baseDir = ensureSafePathFast(rootUri, basePath)
                                if (baseDir == null) {
                                    call.respondText("{\"error\":\"storage_unavailable\"}", ContentType.Application.Json, HttpStatusCode.NotFound)
                                    return@post
                                }

                                val jsonStr: String = call.receiveText()
                                if (jsonStr.isBlank() || !jsonStr.startsWith("[")) {
                                    android.util.Log.e("ServerService", "Empty or malformed JSON received for folder manifest: $jsonStr")
                                    call.respondText("{\"error\":\"empty_or_malformed_manifest\"}", ContentType.Application.Json, HttpStatusCode.BadRequest)
                                    return@post
                                }

                                val jsonArray = org.json.JSONArray(jsonStr)
                                var directoriesCreated = 0

                                val dirCache = mutableMapOf<String, DocumentFile>()
                                dirCache[""] = baseDir

                                for (i in 0 until jsonArray.length()) {
                                    val item = jsonArray.getJSONObject(i)
                                    val relativePath = item.optString("relativePath", "")
                                    if (relativePath.isBlank() || !relativePath.contains("/")) continue
                                    
                                    val cleanPath = sanitizeRelativePath(relativePath)
                                    val dirPath = cleanPath.substringBeforeLast("/")
                                    val dirSegments = dirPath.split("/").filter { it.isNotBlank() }

                                    var currentDir: DocumentFile = baseDir!!
                                    var currentPath = ""

                                    for (segment in dirSegments) {
                                        val safeSegment = sanitizeFilename(segment)
                                        currentPath = if (currentPath.isEmpty()) safeSegment else "$currentPath/$safeSegment"
                                        if (dirCache.containsKey(currentPath)) {
                                            currentDir = dirCache[currentPath]!!
                                        } else {
                                            var nextDir = findFileReliable(currentDir, safeSegment)
                                            if (nextDir != null && !nextDir.isDirectory) {
                                                call.respondText("{\"error\":\"path_conflict_at_segment: $safeSegment\"}", ContentType.Application.Json, HttpStatusCode.Conflict)
                                                return@post
                                            }
                                            if (nextDir == null) {
                                                nextDir = currentDir.createDirectory(safeSegment)
                                                if (nextDir == null) {
                                                    throw Exception("Failed to create directory at segment: $safeSegment")
                                                }
                                                directoriesCreated++
                                            }
                                            currentDir = nextDir!!
                                            dirCache[currentPath] = nextDir!!
                                        }
                                    }
                                }
                                call.respondJson(true, data = mapOf("directoriesCreated" to directoriesCreated))
                            } catch (e: IllegalArgumentException) {
                                android.util.Log.e("ServerService", "Invalid path traversal in manifest", e)
                                call.respondJson(false, "Invalid path traversal", "INVALID_PATH")
                            } catch (e: Exception) {
                                android.util.Log.e("ServerService", "Folder manifest processing failed", e)
                                call.respondJson(false, e.message ?: "Unknown error", "MANIFEST_FAILED")
                            }
                        }

                        post("/folder_complete") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized)
                            try {
                                val basePath = call.request.queryParameters["path"] ?: ""
                                val folderName = call.request.queryParameters["folder"] ?: ""
                                val root = DocumentFile.fromTreeUri(this@ServerService, rootUri) ?: return@post call.respond(HttpStatusCode.NotFound)
                                
                                val fullPath = if (basePath.isBlank()) folderName else if (folderName.isBlank()) basePath else "$basePath/$folderName"
                                val targetDir = resolveSafePath(root, fullPath) ?: return@post call.respond(HttpStatusCode.NotFound)

                                var totalFileCount = 0
                                var totalBytesWrite = 0L
                                val paths = mutableListOf<String>()

                                fun scanFolder(dir: DocumentFile, currentPath: String) {
                                    val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                                        rootUri,
                                        android.provider.DocumentsContract.getDocumentId(dir.uri)
                                    )
                                    val columns = arrayOf(
                                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                                        android.provider.DocumentsContract.Document.COLUMN_SIZE
                                    )
                                    contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                                        val idIdx = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                        val nameIdx = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                        val mimeIdx = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                                        val sizeIdx = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_SIZE)

                                        while(cursor.moveToNext()) {
                                            val name = cursor.getString(nameIdx)
                                            val id = cursor.getString(idIdx)
                                            val mime = cursor.getString(mimeIdx)
                                            val size = cursor.getLong(sizeIdx)
                                            val relPath = if (currentPath.isEmpty()) name else "$currentPath/$name"

                                            if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                                                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(rootUri, id)
                                                val subDir = DocumentFile.fromTreeUri(this@ServerService, uri)
                                                if (subDir != null) scanFolder(subDir, relPath)
                                            } else {
                                                totalFileCount++
                                                totalBytesWrite += size
                                                paths.add(relPath)
                                            }
                                        }
                                    }
                                }

                                scanFolder(targetDir, "")

                                call.respondJson(true, data = mapOf(
                                    "totalFileCount" to totalFileCount,
                                    "totalBytes" to totalBytesWrite,
                                    "paths" to paths
                                ))
                            } catch (e: Exception) {
                                android.util.Log.e("ServerService", "Folder complete scan failed", e)
                                call.respondJson(false, e.message ?: "Unknown error", "SCAN_FAILED")
                            }
                        }

                        post("/upload_chunk") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized)
                            var relativePath: String? = null
                            var chunkIndex: Int = 0
                            var transferId = ""
                            try {
                                val path = call.request.queryParameters["path"] ?: ""
                                val fileName = call.request.queryParameters["filename"] ?: "uploaded_file"
                                relativePath = call.request.queryParameters["relativePath"]
                                chunkIndex = call.request.queryParameters["chunkIndex"]?.toIntOrNull() ?: 0
                                val totalChunks = call.request.queryParameters["totalChunks"]?.toIntOrNull() ?: 1
                                val totalSize = call.request.queryParameters["totalSize"]?.toLongOrNull() ?: 0L
                                val chunkSize = 5 * 1024 * 1024L // Match frontend constant

                                transferId = TransferRegistry.generateTransferId(fileName, totalSize)

                                if (chunkIndex == 0) {
                                    TransferRegistry.onTransferStarted(
                                        transferId = transferId,
                                        fileName = fileName,
                                        totalBytes = totalSize
                                    )
                                }

                                val combinedPath = if (!relativePath.isNullOrBlank()) {
                                    if (path.isNotBlank()) "$path/$relativePath" else relativePath
                                } else {
                                    path
                                }

                                if (hasConsecutiveIdenticalSegments(combinedPath)) {
                                    return@post call.respondJson(false, "Malformed path detected", "BAD_PATH")
                                }

                                val root = DocumentFile.fromTreeUri(applicationContext, rootUri) 
                                    ?: return@post call.respondJson(false, "Storage not mounted", "STORAGE_OFFLINE")
                                
                                var targetDir: DocumentFile = root
                                val finalFileName: String

                                if (!relativePath.isNullOrBlank()) {
                                    try {
                                        val cleanPath = sanitizeRelativePath(relativePath)
                                        val originalFileName = cleanPath.substringAfterLast("/")
                                        finalFileName = sanitizeFilename(originalFileName)
                                        val dirPath = cleanPath.substringBeforeLast("/", "")
                                        val fullDirPath = if (path.isNotBlank()) {
                                            if (dirPath.isNotBlank()) "$path/$dirPath" else path
                                        } else {
                                            dirPath
                                        }
                                        val segments = fullDirPath.split("/").filter { it.isNotBlank() }
                                        for (segment in segments) {
                                            targetDir = resolveOrCreateDirectory(targetDir, segment)
                                        }
                                    } catch (e: Exception) {
                                        return@post call.respondJson(false, "Directory resolution failed: ${e.message}", "DIR_ERROR")
                                    }
                                } else {
                                    val segments = path.split("/").filter { it.isNotBlank() }
                                    for (segment in segments) {
                                        targetDir = resolveOrCreateDirectory(targetDir, segment)
                                    }
                                    finalFileName = sanitizeFilename(fileName)
                                }

                                if (!targetDir.canWrite()) {
                                    return@post call.respondJson(false, "Storage not writable", "STORAGE_READONLY")
                                }

                                val existingDoc = findFileReliable(targetDir, finalFileName)
                                if (existingDoc != null && existingDoc.isDirectory) {
                                    return@post call.respondJson(false, "Path conflict: '$finalFileName' is a directory", "PATH_CONFLICT")
                                }

                                val mimeType = android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(finalFileName.substringAfterLast('.', ""))
                                    ?: "application/octet-stream"

                                val fileUri = if (chunkIndex == 0) {
                                    existingDoc?.uri ?: run {
                                        val createdDoc = targetDir.createFile(mimeType, finalFileName)
                                            ?: android.provider.DocumentsContract.createDocument(
                                                contentResolver,
                                                targetDir.uri,
                                                mimeType,
                                                finalFileName
                                            )?.let { createdUri ->
                                                DocumentFile.fromSingleUri(applicationContext, createdUri)
                                            }

                                        createdDoc?.uri ?: throw Exception("Failed to create file")
                                    }
                                } else {
                                    existingDoc?.uri ?: throw Exception("File not found for subsequent chunk")
                                }

                                // 3. Read the chunk bytes into memory (chunkSize is ~5MB)
                                val byteArray = call.receive<ByteArray>()
                                
                                // Idempotent thread-safe write using locking and FileChannel seeking
                                val lockKey = fileUri.toString()
                                synchronized(fileLocks.getOrPut(lockKey) { Any() }) {
                                    contentResolver.openFileDescriptor(fileUri, "rw")?.use { pfd ->
                                        java.io.FileOutputStream(pfd.fileDescriptor).channel.use { channel ->
                                            if (chunkIndex == 0) {
                                                channel.truncate(0)
                                            }
                                            channel.position(chunkIndex.toLong() * chunkSize)
                                            channel.write(java.nio.ByteBuffer.wrap(byteArray))
                                            channel.force(true)
                                        }
                                    } ?: throw Exception("Failed to open file descriptor for writing")
                                }

                                // Calculate cumulative bytes written across all chunks
                                val chunkSizeBytes = 5L * 1024L * 1024L
                                val estimatedCumulativeBytes = minOf(
                                    (chunkIndex.toLong() + 1L) * chunkSizeBytes,
                                    if (totalSize > 0) totalSize else (chunkIndex.toLong() + 1L) * chunkSizeBytes
                                )
                                TransferRegistry.onChunkWritten(
                                    transferId = transferId,
                                    bytesWritten = estimatedCumulativeBytes
                                )

                                if (chunkIndex == totalChunks - 1) {
                                    TransferRegistry.onTransferComplete(transferId)
                                }

                                call.respondJson(true)
                            } catch (e: Exception) {
                                if (transferId.isNotBlank()) {
                                    TransferRegistry.onTransferFailed(
                                        transferId = transferId,
                                        error = e.message ?: "Upload failed on chunk $chunkIndex"
                                    )
                                }
                                android.util.Log.e("UploadChunk", "Error in upload_chunk for $relativePath", e)
                                call.respondJson(false, e.message ?: "Unknown upload error", "UPLOAD_FAILED")
                            }
                        }

                        post("/upload_complete") {
                            // Logic to verify file integrity if needed
                            call.respondJson(true)
                        }

                        get("/transfer_status") {
                            val active = TransferRegistry.activeTransfers.value
                            val response = active.map { transfer ->
                                mapOf(
                                    "transferId" to transfer.transferId,
                                    "fileName" to transfer.fileName,
                                    "totalBytes" to transfer.totalBytes,
                                    "bytesWritten" to transfer.bytesWritten,
                                    "progressPercent" to transfer.progressPercent,
                                    "speedBytesPerSecond" to transfer.speedBytesPerSecond,
                                    "isComplete" to transfer.isComplete,
                                    "isFailed" to transfer.isFailed,
                                    "isActive" to transfer.isActive,
                                    "startedAt" to transfer.startedAt,
                                    "errorMessage" to transfer.errorMessage
                                )
                            }
                            call.respond(response)
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
                            requestPath.isEmpty() -> "web/index.html"
                            requestPath.matches(Regex("^node/[a-zA-Z0-9]+/?$")) -> "web/index.html"
                            requestPath.startsWith("node/") -> {
                                // Extract the path after the share code: /node/12345/assets/main.css -> assets/main.css
                                val afterShareCode = requestPath.substringAfter("node/").substringAfter("/")
                                if (afterShareCode.isEmpty()) "web/index.html" else "web/$afterShareCode"
                            }
                            else -> "web/$requestPath"
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
                                val indexStream = this@ServerService.assets.open("web/index.html")
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

    private fun hasConsecutiveIdenticalSegments(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val segments = path.split("/").filter { it.isNotBlank() }
        for (i in 0 until segments.size - 1) {
            if (segments[i] == segments[i + 1]) return true
        }
        return false
    }

    private fun openOutputStreamWithRetry(
        contentResolver: android.content.ContentResolver, 
        uri: android.net.Uri, 
        mode: String,
        retries: Int = 3
    ): java.io.OutputStream {
        repeat(retries) { attempt ->
            val stream = contentResolver.openOutputStream(uri, mode)
            if (stream != null) return stream
            android.util.Log.w("UploadChunk", "openOutputStream returned null, attempt ${attempt + 1}")
            Thread.sleep(100L * (attempt + 1))
        }
        throw java.io.IOException("openOutputStream returned null after $retries attempts for uri=$uri")
    }

    private fun findFileReliable(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.let { return it }
        return parent.listFiles().firstOrNull { 
            it.name?.equals(name, ignoreCase = false) == true 
        }
    }

    private fun resolveOrCreateDirectory(parent: DocumentFile, segment: String): DocumentFile {
        val safeSegment = sanitizeFilename(segment)
        val existing = findFileReliable(parent, safeSegment)
        if (existing != null && existing.isDirectory) return existing
        if (existing != null && !existing.isDirectory) {
            throw IllegalStateException("Path conflict: '$safeSegment' exists as a file, not a directory")
        }
        val created = parent.createDirectory(safeSegment)
        if (created != null) {
            android.util.Log.w("UploadChunk", "Auto-healed missing directory segment: $safeSegment")
            return created
        }
        throw IllegalStateException("createDirectory returned null for segment '$safeSegment' under ${parent.uri}")
    }

    private fun sanitizeFilename(name: String): String {
        return name
            .replace(' ', '_')
            .replace(Regex("[()\\[\\]]"), "")
            .replace(Regex("\\.{2,}"), ".")
            .replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .trimEnd('.')
            .ifEmpty { "unnamed_file" }
    }

    private fun sanitizeRelativePath(relativePath: String): String {
        val clean = relativePath.removePrefix("/").removePrefix("./")
        if (clean.contains("..") || clean.contains("./") || clean.contains("//")) {
            throw IllegalArgumentException("Invalid path traversal detected")
        }
        return clean
    }

    private fun resolveSafeDocIdFast(rootUri: Uri, path: String?): String? {
        var currentDocId = android.provider.DocumentsContract.getTreeDocumentId(rootUri) ?: return null
        if (path.isNullOrBlank() || path == "/") return currentDocId
        val segments = path.split("/").filter { it.isNotBlank() }
        
        for (segment in segments) {
            if (segment == ".." || segment == ".") return null
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, currentDocId)
            val foundId = contentResolver.query(
                childrenUri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                var id: String? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == segment) {
                        id = cursor.getString(idIdx)
                        break
                    }
                }
                id
            }
            if (foundId == null) return null
            currentDocId = foundId
        }
        return currentDocId
    }

    private fun resolveSafePath(root: DocumentFile?, path: String?): DocumentFile? {
        if (root == null) return null
        if (path.isNullOrBlank() || path == "/") return root
        
        val docId = resolveSafeDocIdFast(root.uri, path) ?: return null
        val targetUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(root.uri, docId)
        return DocumentFile.fromTreeUri(this@ServerService, targetUri)
    }

    private fun ensureSafePathFast(rootUri: Uri, path: String?): DocumentFile? {
        var currentDocId = android.provider.DocumentsContract.getTreeDocumentId(rootUri) ?: return null
        if (path.isNullOrBlank() || path == "/") {
            val targetUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(rootUri, currentDocId)
            return DocumentFile.fromTreeUri(this@ServerService, targetUri)
        }
        val segments = path.split("/").filter { it.isNotBlank() }
        
        for (segment in segments) {
            if (segment == ".." || segment == ".") return null
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, currentDocId)
            var foundId = contentResolver.query(
                childrenUri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                var id: String? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx) == segment) {
                        id = cursor.getString(idIdx)
                        break
                    }
                }
                id
            }
            if (foundId == null) {
                // Must create it
                val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(rootUri, currentDocId)
                val newDirUri = android.provider.DocumentsContract.createDocument(contentResolver, parentUri, android.provider.DocumentsContract.Document.MIME_TYPE_DIR, segment)
                if (newDirUri == null) return null
                foundId = android.provider.DocumentsContract.getDocumentId(newDirUri)
            }
            if (foundId == null) return null
            currentDocId = foundId
        }
        val targetUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(rootUri, currentDocId)
        return DocumentFile.fromTreeUri(this@ServerService, targetUri)
    }

    private fun triggerUploadNotification(filename: String, progress: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPLOAD_CHANNEL_ID,
                "File Uploads",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val builder = NotificationCompat.Builder(this, UPLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (progress == 100) "Upload Complete" else "Uploading File")
            .setContentText(filename)
            .setProgress(100, progress, progress == 0)
            .setOnlyAlertOnce(true)
            .setAutoCancel(progress == 100)
        
        manager.notify(filename.hashCode(), builder.build())
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024L -> String.format("%.1f KB", bytes / 1_024.0)
            else -> "$bytes B"
        }
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
        success: Boolean, 
        error: String? = null, 
        code: String? = null, 
        data: Any? = null
    ) {
        val map = mutableMapOf<String, Any?>("success" to success)
        if (error != null) map["error"] = error
        if (code != null) map["code"] = code
        if (data != null) map["data"] = data
        this.respond(map)
    }
}

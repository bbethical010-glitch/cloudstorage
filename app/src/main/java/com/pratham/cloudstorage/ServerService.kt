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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.core.readBytes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.InputStream
import java.io.File
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.utils.io.core.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.serialization.gson.*

/**
 * Typed result from the folder manifest handler.
 * Prevents unchecked casts and ensures exhaustive when() handling.
 */
private sealed class ManifestResult {
    data class Success(
        val created: Int,
        val failed: Int,
        val skipped: Int,
        val results: List<Map<String, String>>
    ) : ManifestResult()

    data class Error(
        val message: String,
        val code: String
    ) : ManifestResult()
}

class ServerService : Service() {
    private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val manifestMutex = Mutex()

    private var server: ApplicationEngine? = null
    private var relayTunnelClient: RelayTunnelClient? = null
    private var uploadNotificationManager: UploadNotificationManager? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        uploadNotificationManager = UploadNotificationManager(this)
    }

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
        return START_STICKY
    }

    private fun startForegroundNode(rootUri: Uri, shareCode: String, relayBaseUrl: String, consolePassword: String?) {
        createNotificationChannel()

        // Acquire WakeLock to keep CPU alive while node is serving
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EasyStorageCloud::NodeWakeLock").apply {
            // 24 hours max - auto-releases if service crashes without cleanup
            acquire(24 * 60 * 60 * 1000L)
        }

        val publicUrl = NodeUrlBuilder.buildRelayBrowserUrl(shareCode)
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

        UploadNotificationManager.updateNodeStatus(NodeStatus.STARTING)
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
                    allowHeader("X-Node-Id")
                    allowHeader("X-Requested-With")
                    allowHeader("pwd")

                    exposeHeader(io.ktor.http.HttpHeaders.ContentLength)
                    exposeHeader(io.ktor.http.HttpHeaders.ContentRange)
                    exposeHeader(io.ktor.http.HttpHeaders.AcceptRanges)
                    allowHost("app.local.cloud", schemes = listOf("https"))
                    anyHost() // Fallback for other origins
                }

                // Enforce CORS universally
                intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                    call.response.header("Access-Control-Allow-Origin", "*")
                }

                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        android.util.Log.e("Ktor", "Unhandled exception: ${cause::class.simpleName}", cause)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            mapOf(
                                "success" to false,
                                "error" to (cause::class.simpleName ?: "UnknownError"),
                                "message" to (cause.message ?: "An unexpected error occurred"),
                                "path" to call.request.uri
                            )
                        )
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
                        val queryToken = request.queryParameters["pwd"]
                            ?: request.queryParameters["token"]
                            ?: request.queryParameters["node_session_token"]
                        val providedToken = headerToken?.takeIf { it.isNotBlank() }
                            ?: queryToken?.takeIf { it.isNotBlank() }

                        if (accountExists) {
                            if (activeToken.isNullOrBlank()) return false
                            return providedToken == activeToken
                        } else {
                            if (consolePassword.isNullOrBlank()) return true
                            return providedToken == consolePassword
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
                                val username = obj.optString("username", "").trim().lowercase()
                                val password = obj.optString("password", "")

                                if (username.isBlank() || password.isBlank()) {
                                    return@post call.respond(HttpStatusCode.BadRequest, "{\"error\": \"Missing username or password\"}")
                                }

                                val md = java.security.MessageDigest.getInstance("SHA-256")
                                val hash = java.util.Base64.getEncoder().encodeToString(md.digest(password.toByteArray()))

                                val token = java.util.UUID.randomUUID().toString()
                                prefs.edit()
                                    .putString("username", username)
                                    .putString("password_hash", hash)
                                    .putString("active_token", token)
                                    .apply()

                                call.respondText("{\"token\":\"$token\"}", ContentType.Application.Json)
                            }

                            post("/login") {
                                val prefs = getSharedPreferences("NodeAuthSettings", android.content.Context.MODE_PRIVATE)
                                val existingHash = prefs.getString("password_hash", null)
                                val existingUsername = prefs.getString("username", null)

                                if (existingHash == null || existingUsername == null) {
                                    return@post call.respond(HttpStatusCode.NotFound, "{\"error\": \"No account\"}")
                                }

                                val jsonStr = call.receiveText()
                                if (jsonStr.isBlank()) return@post call.respond(HttpStatusCode.BadRequest)

                                val obj = org.json.JSONObject(jsonStr)
                                val username = obj.optString("username", "").trim().lowercase()
                                val password = obj.optString("password", "")

                                val md = java.security.MessageDigest.getInstance("SHA-256")
                                val hash = java.util.Base64.getEncoder().encodeToString(md.digest(password.toByteArray()))

                                if (hash == existingHash && username == existingUsername.lowercase()) {
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
                                                "mimeType" to mime,
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

                        get("/file-content") {
                            if (!call.hasValidAuth()) return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                            try {
                                val filePath = call.request.queryParameters["path"]
                                    ?: call.request.queryParameters["relativePath"]
                                    ?: run {
                                        call.respond(
                                            HttpStatusCode.BadRequest,
                                            mapOf("error" to "missing_path_parameter")
                                        )
                                        return@get
                                    }

                                if (filePath.contains("..")) {
                                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_path"))
                                    return@get
                                }

                                android.util.Log.d("FileContent", "Serving file: $filePath")

                                val rootDoc = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                                    ?: run {
                                        call.respond(
                                            HttpStatusCode.ServiceUnavailable,
                                            mapOf("error" to "storage_unavailable")
                                        )
                                        return@get
                                    }

                                val targetFile = resolveFilePath(rootDoc, filePath)
                                    ?: run {
                                        android.util.Log.e("FileContent", "File not found: $filePath")
                                        call.respond(
                                            HttpStatusCode.NotFound,
                                            mapOf("error" to "file_not_found", "path" to filePath)
                                        )
                                        return@get
                                    }

                                if (!targetFile.isFile) {
                                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path_is_directory"))
                                    return@get
                                }

                                val extension = targetFile.name?.substringAfterLast('.', "") ?: ""
                                val mimeType = targetFile.type
                                    ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                                    ?: "application/octet-stream"

                                val fileSize = targetFile.length()
                                val rangeHeader = call.request.headers[HttpHeaders.Range]

                                if (rangeHeader != null) {
                                    serveRangeRequest(call, targetFile, mimeType, fileSize, rangeHeader)
                                } else {
                                    call.response.header(HttpHeaders.ContentType, mimeType)
                                    call.response.header(HttpHeaders.ContentLength, fileSize.toString())
                                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                                    call.response.header(
                                        HttpHeaders.ContentDisposition,
                                        "inline; filename=\"${targetFile.name ?: "file"}\""
                                    )

                                    call.respondOutputStream(
                                        contentType = ContentType.parse(mimeType),
                                        status = HttpStatusCode.OK
                                    ) {
                                        contentResolver.openInputStream(targetFile.uri)?.use { input ->
                                            val buffer = ByteArray(65536)
                                            var bytesRead: Int
                                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                                write(buffer, 0, bytesRead)
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("FileContent", "Error serving file", e)
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to "${e::class.simpleName}: ${e.message}")
                                )
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
                                                "mimeType" to mime,
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

                        suspend fun deletePath(deleteCall: io.ktor.server.application.ApplicationCall, fullPath: String) {
                            if (fullPath.isBlank()) {
                                deleteCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_path"))
                                return
                            }
                            if (fullPath.contains("..")) {
                                deleteCall.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_path"))
                                return
                            }

                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                                ?: run {
                                    deleteCall.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "storage_unavailable"))
                                    return
                                }

                            val target = resolveFilePath(root, fullPath)
                                ?: run {
                                    deleteCall.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found", "path" to fullPath))
                                    return
                                }

                            val isDirectory = target.isDirectory
                            val name = target.name ?: fullPath
                            val success = target.delete()

                            if (success) {
                                deleteCall.respond(
                                    HttpStatusCode.OK,
                                    mapOf(
                                        "success" to true,
                                        "deleted" to name,
                                        "wasDirectory" to isDirectory
                                    )
                                )
                            } else {
                                deleteCall.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf(
                                        "error" to "delete_failed",
                                        "message" to "SAF delete returned false for: $fullPath"
                                    )
                                )
                            }
                        }

                        post("/delete") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                            try {
                                val queryPath = call.request.queryParameters["path"]
                                val params = if (queryPath.isNullOrBlank()) call.receiveParameters() else null
                                val parentPath = params?.get("path")
                                val name = params?.get("name")
                                val fullPath = when {
                                    !queryPath.isNullOrBlank() && !call.request.queryParameters["name"].isNullOrBlank() ->
                                        listOf(queryPath, call.request.queryParameters["name"]).joinToString("/").replace(Regex("/+"), "/")
                                    !queryPath.isNullOrBlank() -> queryPath
                                    !parentPath.isNullOrBlank() && !name.isNullOrBlank() -> "$parentPath/$name"
                                    !name.isNullOrBlank() -> name
                                    else -> ""
                                }
                                deletePath(call, fullPath)
                            } catch (e: Exception) {
                                android.util.Log.e("Delete", "Delete error", e)
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "${e::class.simpleName}: ${e.message}"))
                            }
                        }

                        delete("/delete") {
                            if (!call.hasValidAuth()) return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                            try {
                                val path = call.request.queryParameters["path"]
                                    ?: run {
                                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_path"))
                                        return@delete
                                    }
                                deletePath(call, path)
                            } catch (e: Exception) {
                                android.util.Log.e("Delete", "Delete error", e)
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "${e::class.simpleName}: ${e.message}"))
                            }
                        }

                        // ── Recursive Folder Delete ─────────────────────────────────
                        post("/folder_delete") {
                            if (!call.hasValidAuth()) return@post call.respond(HttpStatusCode.Unauthorized)
                            try {
                                val params = call.receiveParameters()
                                val path = params["path"] ?: ""
                                val name = params["name"] ?: return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf("error" to "Missing folder name")
                                )

                                // Path traversal sanitization
                                if (name.contains("..") || name.contains("/") || name.contains("\\")) {
                                    return@post call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("error" to "Invalid folder name")
                                    )
                                }

                                val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Root not found"))
                                val targetDir = resolveSafePath(root, path)
                                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Parent directory not found"))
                                val targetFolder = targetDir.findFile(name)
                                    ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Folder not found"))

                                if (!targetFolder.isDirectory) {
                                    return@post call.respond(
                                        HttpStatusCode.BadRequest,
                                        mapOf("error" to "Target is not a directory. Use /api/delete for files.")
                                    )
                                }

                                // Recursive delete using DocumentFile API
                                fun deleteRecursively(doc: DocumentFile): Int {
                                    var count = 0
                                    if (doc.isDirectory) {
                                        doc.listFiles().forEach { child ->
                                            count += deleteRecursively(child)
                                        }
                                    }
                                    if (doc.delete()) count++
                                    return count
                                }

                                val deletedCount = deleteRecursively(targetFolder)
                                call.respond(
                                    HttpStatusCode.OK,
                                    mapOf("deleted" to deletedCount, "folder" to name)
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("ServerService", "Folder delete failed", e)
                                call.respond(
                                    HttpStatusCode.InternalServerError,
                                    mapOf("error" to (e.message ?: "Folder delete failed"))
                                )
                            }
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

                        get("/download-folder") {
                            if (!call.hasValidAuth()) return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                            try {
                                val path = call.request.queryParameters["path"]
                                    ?: run {
                                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing_path"))
                                        return@get
                                    }

                                if (path.contains("..")) {
                                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_path"))
                                    return@get
                                }

                                val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                                    ?: run {
                                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "storage_unavailable"))
                                        return@get
                                    }

                                val folderDoc = resolveFilePath(root, path)
                                    ?: run {
                                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "folder_not_found", "path" to path))
                                        return@get
                                    }

                                if (!folderDoc.isDirectory) {
                                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path_is_not_directory"))
                                    return@get
                                }

                                val folderName = (folderDoc.name ?: "download").replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${folderName}.zip\"")
                                call.response.header(HttpHeaders.ContentType, "application/zip")

                                call.respondOutputStream(
                                    contentType = ContentType.parse("application/zip"),
                                    status = HttpStatusCode.OK
                                ) {
                                    ZipOutputStream(this).use { zip ->
                                        addFolderToZip(zip, folderDoc, "", contentResolver)
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("DownloadFolder", "Error creating ZIP", e)
                                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "${e::class.simpleName}: ${e.message}"))
                            }
                        }

                        get("/download_folder") {
                            val path = call.request.queryParameters["path"]
                            val folderName = call.request.queryParameters["folder"] ?: return@get call.respond(HttpStatusCode.BadRequest)

                            // Path traversal sanitization
                            if (folderName.contains("..") || folderName.contains("/") || folderName.contains("\\")) {
                                return@get call.respond(HttpStatusCode.BadRequest, "Invalid folder name")
                            }

                            val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                            val targetDir = resolveSafePath(root, path)
                            val targetFolder = targetDir?.findFile(folderName)

                            if (targetFolder != null && targetFolder.isDirectory) {
                                // Set Content-Disposition for proper filename in browser downloads
                                val safeFileName = folderName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                call.response.header("Content-Disposition", "attachment; filename=\"${safeFileName}.zip\"")

                                call.respondOutputStream(contentType = ContentType.parse("application/zip"), status = HttpStatusCode.OK) {
                                    val zipOut = ZipOutputStream(this)
                                    val buffer = ByteArray(8192) // Chunk buffer for streaming

                                    fun addFolderToZip(folder: DocumentFile, basePath: String) {
                                        folder.listFiles().forEach { file ->
                                            val entryName = if (basePath.isEmpty()) file.name else "$basePath/${file.name}"
                                            if (file.isDirectory) {
                                                zipOut.putNextEntry(ZipEntry("$entryName/"))
                                                zipOut.closeEntry()
                                                addFolderToZip(file, entryName ?: "")
                                            } else {
                                                zipOut.putNextEntry(ZipEntry(entryName))
                                                // Stream chunk-by-chunk to avoid OOM
                                                contentResolver.openInputStream(file.uri)?.use { inputStream ->
                                                    var bytesRead: Int
                                                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                                        zipOut.write(buffer, 0, bytesRead)
                                                    }
                                                }
                                                zipOut.closeEntry()
                                            }
                                        }
                                    }
                                    addFolderToZip(targetFolder, "")
                                    zipOut.finish()
                                }
                            } else {
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Folder not found"))
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
                            // ═══════════════════════════════════════════════════════════
                            // NUCLEAR OUTER CATCH — Nothing escapes this route. Ever.
                            // If even the error-response logic throws (e.g. response
                            // already committed), we log and eat it.
                            // ═══════════════════════════════════════════════════════════
                            try {
                                if (!call.hasValidAuth()) {
                                    call.respond(HttpStatusCode.Unauthorized)
                                    return@post
                                }

                                // ── Parse request inputs safely ──────────────────────
                                val basePath: String
                                val jsonStr: String
                                try {
                                    basePath = call.request.queryParameters["path"] ?: ""
                                    jsonStr = call.receiveText()
                                } catch (e: Exception) {
                                    android.util.Log.e("FolderManifest", "Failed to read request body", e)
                                    call.respondJson(false, "Failed to read request body: ${e.message}", "BAD_REQUEST")
                                    return@post
                                }

                                // ── Validate JSON format ─────────────────────────────
                                if (jsonStr.isBlank() || (!jsonStr.trimStart().startsWith("[") && !jsonStr.trimStart().startsWith("{"))) {
                                    call.respondJson(false, "Empty or malformed manifest body", "BAD_MANIFEST")
                                    return@post
                                }

                                val jsonArray: org.json.JSONArray
                                try {
                                    jsonArray = org.json.JSONArray(jsonStr)
                                } catch (e: Exception) {
                                    android.util.Log.e("FolderManifest", "JSON parse failed", e)
                                    call.respondJson(false, "Malformed JSON: ${e.message}", "JSON_PARSE_ERROR")
                                    return@post
                                }

                                if (jsonArray.length() == 0) {
                                    call.respondJson(true, data = mapOf(
                                        "directoriesCreated" to 0,
                                        "directoriesFailed" to 0,
                                        "results" to emptyList<Any>()
                                    ))
                                    return@post
                                }

                                // ── Resolve base directory safely ────────────────────
                                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    val root = DocumentFile.fromTreeUri(this@ServerService, rootUri)
                                    if (root == null) {
                                        return@withContext ManifestResult.Error("Storage unavailable", "STORAGE_OFFLINE")
                                    }

                                    val baseDir = try {
                                        if (basePath.isBlank() || basePath == "/") root
                                        else resolveSafePath(root, basePath)
                                    } catch (e: Exception) {
                                        android.util.Log.e("FolderManifest", "Base path resolution failed: '$basePath'", e)
                                        null
                                    }

                                    if (baseDir == null) {
                                        return@withContext ManifestResult.Error("Upload target directory not found", "TARGET_NOT_FOUND")
                                    }
                                    if (!baseDir.isDirectory) {
                                        return@withContext ManifestResult.Error("Upload target is not a directory", "NOT_A_DIRECTORY")
                                    }

                                    // ── Process entries under Mutex ──────────────────
                                    manifestMutex.withLock {
                                        var created = 0
                                        var failed = 0
                                        var skipped = 0
                                        val dirCache = mutableMapOf<String, DocumentFile>()
                                        val entryResults = mutableListOf<Map<String, String>>()

                                        for (i in 0 until jsonArray.length()) {
                                            // ── Extract path from JSON safely ───────
                                            val originalPath: String
                                            try {
                                                val item = jsonArray.getJSONObject(i)
                                                originalPath = item.optString("relativePath", "")
                                            } catch (e: Exception) {
                                                android.util.Log.e("FolderManifest", "Entry [$i]: failed to parse JSON object", e)
                                                failed++
                                                entryResults.add(mapOf(
                                                    "originalPath" to "<malformed JSON at index $i>",
                                                    "sanitizedPath" to "",
                                                    "status" to "FAILED",
                                                    "error" to "Malformed JSON entry: ${e.message}"
                                                ))
                                                continue
                                            }

                                            // Skip blank entries
                                            if (originalPath.isBlank()) {
                                                skipped++
                                                continue
                                            }

                                            // ── Sanitize the path aggressively ──────
                                            val sanitizedPath: String
                                            try {
                                                sanitizedPath = sanitizeManifestPath(originalPath)
                                            } catch (e: Exception) {
                                                android.util.Log.e("FolderManifest", "Entry [$i]: sanitization failed for '$originalPath'", e)
                                                failed++
                                                entryResults.add(mapOf(
                                                    "originalPath" to originalPath,
                                                    "sanitizedPath" to "",
                                                    "status" to "FAILED",
                                                    "error" to "Path sanitization failed: ${e.message}"
                                                ))
                                                continue
                                            }

                                            // Root-level files have no parent dir to create
                                            if (!sanitizedPath.contains("/")) {
                                                skipped++
                                                continue
                                            }

                                            val parentDirPath = sanitizedPath.substringBeforeLast("/")
                                            if (parentDirPath.isBlank() || parentDirPath == "/") {
                                                skipped++
                                                continue
                                            }

                                            val dirSegments = parentDirPath.split("/").filter { it.isNotBlank() }
                                            if (dirSegments.isEmpty()) {
                                                skipped++
                                                continue
                                            }

                                            // ── Create directory tree segment by segment ──
                                            try {
                                                var currentDir: DocumentFile = baseDir
                                                var currentKey = ""

                                                for (segment in dirSegments) {
                                                    val safeSegment = sanitizeFilename(segment)
                                                    if (safeSegment.isBlank() || safeSegment == "unnamed_file") continue

                                                    currentKey = if (currentKey.isEmpty()) safeSegment
                                                                 else "$currentKey/${safeSegment}"

                                                    val cached = dirCache[currentKey]
                                                    if (cached != null) {
                                                        currentDir = cached
                                                    } else {
                                                        val nextDir = resolveOrCreateDirectory(currentDir, safeSegment)
                                                        currentDir = nextDir
                                                        dirCache[currentKey] = nextDir
                                                    }
                                                }
                                                created++
                                                // Only include detailed results for failures to keep response small
                                            } catch (e: Exception) {
                                                android.util.Log.e("FolderManifest",
                                                    "Entry [$i]: directory creation failed | original='$originalPath' | sanitized='$sanitizedPath'", e)
                                                failed++
                                                entryResults.add(mapOf(
                                                    "originalPath" to originalPath,
                                                    "sanitizedPath" to sanitizedPath,
                                                    "status" to "FAILED",
                                                    "error" to (e.message ?: "Unknown directory creation error")
                                                ))
                                            }
                                        }

                                        ManifestResult.Success(
                                            created = created,
                                            failed = failed,
                                            skipped = skipped,
                                            results = entryResults.take(20) // Cap detailed results
                                        )
                                    }
                                }

                                // ── Send response based on result ────────────────────
                                when (result) {
                                    is ManifestResult.Error -> {
                                        call.respondJson(false, result.message, result.code)
                                    }
                                    is ManifestResult.Success -> {
                                        if (result.failed > 0) {
                                            android.util.Log.w("FolderManifest",
                                                "Batch: ${result.created} created, ${result.failed} failed, ${result.skipped} skipped")
                                        }
                                        call.respondJson(true, data = mapOf(
                                            "directoriesCreated" to result.created,
                                            "directoriesFailed" to result.failed,
                                            "directoriesSkipped" to result.skipped,
                                            "results" to result.results
                                        ))
                                    }
                                }
                            } catch (e: Exception) {
                                // ═════════════════════════════════════════════════════
                                // LAST RESORT — This should NEVER fire, but if it does
                                // we absolutely refuse to let it become a 502.
                                // ═════════════════════════════════════════════════════
                                android.util.Log.e("FolderManifest", "CRITICAL: Unhandled exception in manifest route", e)
                                try {
                                    call.respondJson(false, "Internal server error: ${e.message}", "INTERNAL_ERROR")
                                } catch (responseError: Exception) {
                                    // Even responding failed (response already committed, etc.)
                                    android.util.Log.e("FolderManifest", "CRITICAL: Failed to send error response", responseError)
                                }
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
                                val path = call.request.queryParameters["path"]?.takeIf { it.isNotBlank() } ?: ""
                                val fileName = call.request.queryParameters["filename"]?.takeIf { it.isNotBlank() }
                                    ?: call.request.queryParameters["fileName"]?.takeIf { it.isNotBlank() }
                                    ?: run {
                                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing or malformed filename"))
                                        return@post
                                    }
                                relativePath = call.request.queryParameters["relativePath"]?.takeIf { it.isNotBlank() }
                                chunkIndex = call.request.queryParameters["chunkIndex"]?.toIntOrNull() ?: 0
                                val totalChunks = call.request.queryParameters["totalChunks"]?.toIntOrNull() ?: 1
                                val totalSize = call.request.queryParameters["totalSize"]?.toLongOrNull() ?: 0L
                                val chunkSize = 5 * 1024 * 1024L // Match frontend constant
                                transferId = "${fileName}_${totalSize}"



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
                                        val cleanPath = validateAndNormalizeRelativePath(relativePath)
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
                                    } catch (e: SecurityException) {
                                        return@post call.respondJson(false, e.message ?: "Invalid path traversal detected", "INVALID_PATH")
                                    } catch (e: Exception) {
                                        return@post call.respondJson(false, "Directory resolution failed: ${e.message}", "DIR_ERROR")
                                    }
                                } else {
                                    finalFileName = sanitizeFilename(fileName)
                                    val segments = path.split("/").filter { it.isNotBlank() }
                                    for (segment in segments) {
                                        targetDir = resolveOrCreateDirectory(targetDir, segment)
                                    }
                                }

                                if (chunkIndex == 0) {
                                    uploadNotificationManager?.onUploadStarted(transferId, fileName, totalSize)
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





                                if (chunkIndex == totalChunks - 1) {
                                    uploadNotificationManager?.onUploadComplete(transferId)
                                } else {
                                    val currentBytes = (chunkIndex.toLong() + 1) * chunkSize
                                    uploadNotificationManager?.onProgressUpdate(transferId, minOf(currentBytes, totalSize))
                                }

                                call.respondJson(true)
                            } catch (e: Exception) {

                                if (transferId.isNotBlank()) {
                                    uploadNotificationManager?.onUploadFailed(transferId, e.message ?: "Chunk $chunkIndex failed")
                                }
                                android.util.Log.e("UploadChunk", "Error in upload_chunk for $relativePath", e)
                                call.respondJson(false, e.message ?: "Unknown upload error", "UPLOAD_FAILED")
                            }
                        }

                        post("/upload_complete") {
                            // Logic to verify file integrity if needed
                            call.respondJson(true)
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

            server?.start(wait = false)
            // Server is now listening — emit ACTIVE
            UploadNotificationManager.updateNodeStatus(NodeStatus.ACTIVE)
        }
    }

    private fun stopServer() {
        UploadNotificationManager.updateNodeStatus(NodeStatus.STOPPED)
        relayTunnelClient?.stop()
        relayTunnelClient = null
        tunnelStatusFlow.value = TunnelStatus.Offline
        server?.stop(1000, 2000)
        server = null
        // Release WakeLock if held
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            android.util.Log.e("ServerService", "WakeLock release failed", e)
        }
        wakeLock = null
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
        uploadNotificationManager?.cancelAll()
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

    /**
     * Thread-safe directory resolution/creation for SAF.
     *
     * Race condition fix: Under concurrent uploads, multiple threads may try to
     * create the same directory simultaneously. `createDirectory()` returns null
     * for the losing thread because the directory already exists. This is NOT a
     * failure — it's expected. We use synchronized + post-creation re-query to
     * handle this gracefully.
     */
    private fun resolveOrCreateDirectory(parent: DocumentFile, segment: String): DocumentFile {
        val safeSegment = sanitizeFilename(segment)

        // Fast path — directory already exists (no lock needed)
        findFileReliable(parent, safeSegment)?.let { existing ->
            if (existing.isDirectory) return existing
            if (!existing.isDirectory) {
                throw IllegalStateException("Path conflict: '$safeSegment' exists as a file, not a directory")
            }
        }

        // Slow path — need to create. Synchronize on the canonicalized path
        // to prevent two threads from racing on the same directory segment.
        val lockKey = "${parent.uri}/$safeSegment"
        synchronized(lockKey.intern()) {
            // Double-check after acquiring lock — another thread may have created it
            findFileReliable(parent, safeSegment)?.let { existing ->
                if (existing.isDirectory) return existing
                if (!existing.isDirectory) {
                    throw IllegalStateException("Path conflict: '$safeSegment' exists as a file, not a directory")
                }
            }

            // Attempt creation — ignore null return value, it does NOT mean failure
            val created = parent.createDirectory(safeSegment)
            if (created != null) {
                android.util.Log.d("DirCreate", "Created directory segment: $safeSegment")
                return created
            }

            // createDirectory returned null — likely a race condition where another
            // thread created it between our check and our create call.
            // Re-query SAF as the ultimate source of truth.
            android.util.Log.w("DirCreate", "createDirectory returned null for '$safeSegment', re-querying SAF")
            findFileReliable(parent, safeSegment)?.let { recheck ->
                if (recheck.isDirectory) return recheck
            }

            // Final fallback: brief delay + one more attempt (SAF caching lag)
            Thread.sleep(100)
            findFileReliable(parent, safeSegment)?.let { finalCheck ->
                if (finalCheck.isDirectory) return finalCheck
            }

            // Only throw if the directory truly does not exist after all attempts
            throw IllegalStateException(
                "Failed to create or locate directory '$safeSegment' under ${parent.uri} after synchronized retry"
            )
        }
    }

    private fun sanitizeFilename(name: String): String {
        return name
            .replace(' ', '_')
            .replace(Regex("[()\\[\\]]"), "")
            .replace(Regex("[^a-zA-Z0-9._\\-]"), "_")
            .trimEnd('.')
            .ifEmpty { "unnamed_file" }
    }

    /**
     * Sanitize a full relative path (with / separators) against Android-illegal
     * filesystem characters BEFORE path validation or segmentation.
     *
     * Characters legal on macOS/Windows but illegal on Android storage:
     *   * " < > ? | :
     *
     * Forward slashes are preserved as directory separators.
     */
    private fun sanitizeRelativePath(path: String): String {
        return path
            .replace('\\', '/')           // Normalize Windows backslashes first
            .split('/')                    // Split into segments
            .joinToString("/") { segment ->
                segment
                    .replace(Regex("[*\"<>?|:]"), "_")  // Strip Android-illegal chars
                    .trim()                                // Trim whitespace per segment
            }
    }

    /**
     * Comprehensive manifest path sanitizer. This is the FIRST line of defense —
     * runs before any File I/O or SAF operations.
     *
     * 1. Normalizes separators (backslash → forward slash)
     * 2. Strips Android-illegal characters per segment: * " < > ? | : \\
     * 3. Trims whitespace and trailing dots per segment
     * 4. Collapses repeated slashes (a///b → a/b)
     * 5. Removes leading slashes (absolute → relative)
     * 6. Blocks path traversal (.. segments)
     * 7. Rejects empty results
     */
    private fun sanitizeManifestPath(rawPath: String): String {
        val normalized = rawPath
            .replace('\\', '/')                              // Windows → Unix separators
            .replace(Regex("/+"), "/")                       // Collapse repeated slashes
            .trimStart('/')                                   // Remove leading slash
            .trim()                                           // Trim outer whitespace

        if (normalized.isBlank()) {
            throw IllegalArgumentException("Path is blank after normalization")
        }

        val segments = normalized.split("/").map { segment ->
            // Block traversal
            if (segment == ".." || segment == ".") {
                throw SecurityException("Path traversal detected: '$segment' in '$rawPath'")
            }

            segment
                .replace(Regex("[*\"<>?|:\\\\]"), "_")       // Strip illegal chars
                .trim()                                       // Trim whitespace
                .trimEnd('.')                                  // Remove trailing dots
                .ifEmpty { "_" }                              // Never leave a blank segment
        }.filter { it.isNotBlank() && it != "_" }             // Drop degenerate segments

        if (segments.isEmpty()) {
            throw IllegalArgumentException("Path is empty after sanitization: '$rawPath'")
        }

        return segments.joinToString("/")
    }

    private fun validateAndNormalizeRelativePath(relativePath: String): String {
        val normalized = relativePath
            .replace('\\', '/')
            .trim()
            .trimStart('/')

        if (normalized.isBlank()) {
            throw SecurityException("Invalid path traversal detected")
        }

        val baseDir = File(applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir, "EasyStorage")
        val canonicalBaseDir = baseDir.canonicalFile
        val targetFile = File(canonicalBaseDir, normalized)
        val canonicalTargetFile = targetFile.canonicalFile

        val safeBasePath = canonicalBaseDir.path
        val allowedPrefix = if (safeBasePath.endsWith(File.separator)) safeBasePath else "$safeBasePath${File.separator}"
        val targetPath = canonicalTargetFile.path

        // canonicalPath resolves traversal segments like "../../etc/passwd" while preserving
        // legitimate filenames such as "script..py", so only true directory escapes are blocked.
        if (targetPath != safeBasePath && !targetPath.startsWith(allowedPrefix)) {
            throw SecurityException("Invalid path traversal detected")
        }

        return canonicalTargetFile
            .relativeTo(canonicalBaseDir)
            .invariantSeparatorsPath
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

    private fun resolveFilePath(root: DocumentFile, path: String): DocumentFile? {
        if (path.isBlank() || path == "/" || path == ".") return root

        val segments = path
            .replace('\\', '/')
            .trim('/')
            .split("/")
            .filter { it.isNotBlank() }

        var current: DocumentFile = root
        for (segment in segments) {
            if (segment == "." || segment == "..") return null
            current = current.listFiles().firstOrNull { it.name == segment } ?: return null
        }

        return current
    }

    private suspend fun serveRangeRequest(
        call: io.ktor.server.application.ApplicationCall,
        file: DocumentFile,
        mimeType: String,
        fileSize: Long,
        rangeHeader: String
    ) {
        val range = rangeHeader.removePrefix("bytes=").split("-", limit = 2)
        val start = range.getOrNull(0)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val requestedEnd = range.getOrNull(1)?.toLongOrNull()
        val end = (requestedEnd ?: (fileSize - 1)).coerceAtMost(fileSize - 1)

        if (!rangeHeader.startsWith("bytes=") || fileSize <= 0L || start > end) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_range"))
            return
        }

        val contentLength = end - start + 1

        call.response.header(HttpHeaders.ContentType, mimeType)
        call.response.header(HttpHeaders.ContentLength, contentLength.toString())
        call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$fileSize")
        call.response.header(HttpHeaders.AcceptRanges, "bytes")

        call.respondOutputStream(
            contentType = ContentType.parse(mimeType),
            status = HttpStatusCode.PartialContent
        ) {
            contentResolver.openInputStream(file.uri)?.use { input ->
                var skipped = 0L
                while (skipped < start) {
                    val delta = input.skip(start - skipped)
                    if (delta <= 0L) break
                    skipped += delta
                }

                var remaining = contentLength
                val buffer = ByteArray(65536)
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val bytesRead = input.read(buffer, 0, toRead)
                    if (bytesRead == -1) break
                    write(buffer, 0, bytesRead)
                    remaining -= bytesRead
                }
            }
        }
    }

    private fun addFolderToZip(
        zip: ZipOutputStream,
        folder: DocumentFile,
        prefix: String,
        contentResolver: android.content.ContentResolver
    ) {
        folder.listFiles().forEach { child ->
            val childName = child.name ?: "unknown"
            val entryName = if (prefix.isBlank()) childName else "$prefix/$childName"

            if (child.isDirectory) {
                zip.putNextEntry(ZipEntry("$entryName/"))
                zip.closeEntry()
                addFolderToZip(zip, child, entryName, contentResolver)
            } else {
                zip.putNextEntry(ZipEntry(entryName))
                contentResolver.openInputStream(child.uri)?.use { input ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        zip.write(buffer, 0, bytesRead)
                    }
                }
                zip.closeEntry()
            }
        }
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

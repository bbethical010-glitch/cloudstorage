package com.pratham.cloudstorage

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.pratham.cloudstorage.ui.theme.CloudStorageTheme
import com.pratham.cloudstorage.ui.theme.DarkPanel
import com.pratham.cloudstorage.ui.theme.PrimaryBlue
import com.pratham.cloudstorage.ui.theme.TextSecondary
import com.pratham.cloudstorage.ui.theme.TextPrimary
import com.pratham.cloudstorage.ui.theme.SuccessGreen
import com.pratham.cloudstorage.ui.theme.HighlightPurple

import android.webkit.JavascriptInterface
import org.json.JSONObject
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {

    private lateinit var preferences: SharedPreferences

    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private var selectedUri by mutableStateOf<Uri?>(null)
    private var shareCode by mutableStateOf("")
    private var relayBaseUrl by mutableStateOf("")
    private var isNodeRunning by mutableStateOf(false)
    private var tunnelStatus by mutableStateOf(TunnelStatus.Offline.name)

    private var healthCpu = "0%"
    private var healthMem = "0 MB"
    private var healthPing = "0 ms"
    private var healthIo = "Idle"
    private var lastRxBytes = 0L

    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }.onFailure {
                        Toast.makeText(this@MainActivity, "Cannot authorize root directory. Please select a specific sub-folder like Documents or Pictures.", Toast.LENGTH_LONG).show()
                        return@let
                    }
                    selectedUri = uri
                    preferences.edit().putString(PREF_SELECTED_URI, uri.toString()).apply()
                    updateWebState()
                }
            }
        }

    private var tempCameraUri: Uri? = null

    private val scanDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            tempCameraUri?.let { uri ->
                val rootUri = selectedUri
                if (rootUri != null) {
                    val root = DocumentFile.fromTreeUri(this, rootUri)
                    val newFile = root?.createFile("image/jpeg", "Scan_${System.currentTimeMillis()}.jpg")
                    if (newFile != null) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            contentResolver.openOutputStream(newFile.uri)?.use { output ->
                                input.copyTo(output)
                            }
                        }
                        Toast.makeText(this, "Document saved to node", Toast.LENGTH_SHORT).show()
                        updateWebState()
                    }
                }
                contentResolver.delete(uri, null, null)
            }
        }
    }

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            Toast.makeText(this, "Scanned: ${result.contents}", Toast.LENGTH_LONG).show()
        }
    }

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileChooserCallback
            fileChooserCallback = null

            if (callback == null) return@registerForActivityResult

            if (result.resultCode != Activity.RESULT_OK) {
                callback.onReceiveValue(null)
                return@registerForActivityResult
            }

            val uris = mutableListOf<Uri>()
            result.data?.data?.let { uris.add(it) }
            result.data?.clipData?.let { clipData ->
                for (index in 0 until clipData.itemCount) {
                    clipData.getItemAt(index)?.uri?.let { uris.add(it) }
                }
            }

            callback.onReceiveValue(uris.distinct().takeIf { it.isNotEmpty() }?.toTypedArray())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        selectedUri = preferences.getString(PREF_SELECTED_URI, null)?.let(Uri::parse)
        shareCode = preferences.getString(PREF_SHARE_CODE, null) ?: generateAndPersistShareCode()
        
        val savedRelayUrl = preferences.getString(PREF_RELAY_BASE_URL, null).orEmpty()
        if (savedRelayUrl.isBlank()) {
            relayBaseUrl = BuildConfig.RELAY_BASE_URL
            preferences.edit().putString(PREF_RELAY_BASE_URL, relayBaseUrl).apply()
        } else {
            relayBaseUrl = savedRelayUrl
        }

        setupWebView()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServerService.tunnelStatusFlow.collect { status ->
                    tunnelStatus = status.name
                    updateWebState()
                }
            }
        }
        
        // Health metrics polling loop
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val usedMemMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
                healthMem = "$usedMemMb MB"

                val cpuTime = android.os.Process.getElapsedCpuTime()
                val uptime = android.os.SystemClock.elapsedRealtime()
                val cpuPct = if (uptime > 0) ((cpuTime.toFloat() / uptime.toFloat()) * 10).toInt().coerceIn(1, 100) else 1
                healthCpu = "$cpuPct%"

                val rxBytes = android.net.TrafficStats.getTotalRxBytes()
                val diff = rxBytes - lastRxBytes
                if (diff > 1024) {
                    healthPing = "${(20..45).random()} ms"
                    healthIo = "Active"
                } else {
                    healthPing = "42 ms"
                    healthIo = "Idle"
                }
                lastRxBytes = rxBytes

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { 
                    updateWebState() 
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.databaseEnabled = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            
            clearCache(true)
            
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        val msg = "JS Console: ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}"
                        android.util.Log.e("WebViewDebug", msg)
                        if (it.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "JS Error: ${it.message()}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                    this@MainActivity.fileChooserCallback = filePathCallback

                    return try {
                        val chooserIntent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                        filePickerLauncher.launch(chooserIntent)
                        true
                    } catch (e: Exception) {
                        this@MainActivity.fileChooserCallback = null
                        Toast.makeText(this@MainActivity, "Unable to open file picker", Toast.LENGTH_LONG).show()
                        false
                    }
                }
            }
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    android.util.Log.e("WebViewDebug", "Page loaded: $url")
                    updateWebState()
                }

                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    android.util.Log.e("WebViewDebug", "Error loading $failingUrl: $description")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Load Error: $description", Toast.LENGTH_LONG).show()
                    }
                }
            }
            addJavascriptInterface(WebAppInterface(), "Android")
        }
        setContentView(webView)
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun getInitialState(): String {
            val stats = resolveStorageStats(selectedUri ?: Uri.EMPTY)
            val state = JSONObject().apply {
                put("node", JSONObject().apply {
                    put("folderName", resolveFolderName(selectedUri ?: Uri.EMPTY))
                    put("shareCode", shareCode)
                    put("relayBaseUrl", relayBaseUrl)
                    put("isRunning", isNodeRunning)
                    put("tunnelConnected", tunnelStatus == TunnelStatus.Connected.name)
                    put("tunnelStatus", tunnelStatus)
                    put("health", JSONObject().apply {
                        put("cpu", healthCpu)
                        put("memory", healthMem)
                        put("ping", healthPing)
                        put("io", healthIo)
                    })
                })
                put("storage", JSONObject().apply {
                    put("usedBytes", stats.first)
                    put("totalBytes", stats.second)
                    put("freeBytes", if (stats.second > 0L) stats.second - stats.first else 0L)
                })
            }
            android.util.Log.e("API_DEBUG", "Emitted initial SSOT: $state")
            return state.toString()
        }

        @JavascriptInterface
        fun selectFolder() {
            runOnUiThread { this@MainActivity.selectFolder() }
        }

        @JavascriptInterface
        fun toggleNode() {
            runOnUiThread { this@MainActivity.toggleNode() }
        }

        @JavascriptInterface
        fun updateRelayBaseUrl(url: String) {
            runOnUiThread { this@MainActivity.updateRelayBaseUrl(url) }
        }

        @JavascriptInterface
        fun shareInvite() {
            runOnUiThread { this@MainActivity.shareInvite() }
        }

        @JavascriptInterface
        fun copyToClipboard(text: String, toast: String) {
            runOnUiThread { this@MainActivity.copyToClipboard(text, toast) }
        }

        @JavascriptInterface
        fun scanDocument() {
            runOnUiThread { this@MainActivity.scanDocument() }
        }

        @JavascriptInterface
        fun showNotification(title: String, message: String) {
            runOnUiThread { this@MainActivity.showNotification(title, message) }
        }

        @JavascriptInterface
        fun scanQRCode() {
            runOnUiThread { this@MainActivity.scanQRCode() }
        }

        @JavascriptInterface
        fun shareLink(text: String) {
            runOnUiThread {
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Easy Storage Cloud")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(sendIntent, "Share Link"))
            }
        }
    }

    private fun updateWebState() {
        android.util.Log.e("API_DEBUG", "updateWebState STARTED")
        if (!::webView.isInitialized) {
            android.util.Log.e("API_DEBUG", "webView not initialized, aborting")
            return
        }
        val uriToResolve = selectedUri ?: Uri.EMPTY
        android.util.Log.e("API_DEBUG", "Resolving stats for URI: $uriToResolve")
        val stats = resolveStorageStats(uriToResolve)
        android.util.Log.e("API_DEBUG", "Stats resolved: $stats")

        val folderN = resolveFolderName(uriToResolve)
        android.util.Log.e("API_DEBUG", "Folder resolved: $folderN")
        
        val state = try {
            JSONObject().apply {
            put("node", JSONObject().apply {
                put("folderName", resolveFolderName(selectedUri ?: Uri.EMPTY))
                put("shareCode", shareCode)
                put("relayBaseUrl", relayBaseUrl)
                put("lanUrl", buildLocalAccessUrl() ?: "")
                put("isRunning", isNodeRunning)
                put("tunnelConnected", tunnelStatus == TunnelStatus.Connected.name)
                put("tunnelStatus", tunnelStatus)
                put("health", JSONObject().apply {
                    put("cpu", healthCpu)
                    put("memory", healthMem)
                    put("ping", healthPing)
                    put("io", healthIo)
                })
            })
                put("storage", JSONObject().apply {
                    put("usedBytes", stats.first)
                    put("totalBytes", stats.second)
                    put("freeBytes", if (stats.second > 0L) stats.second - stats.first else 0L)
                })
            }
        } catch (e: Exception) {
            android.util.Log.e("API_DEBUG", "Failed to build JSON: ${e.message}")
            return
        }
        android.util.Log.e("API_DEBUG", "Live Web Context Update: $state")
        webView.post {
            android.util.Log.e("API_DEBUG", "Evaluating JS inside webView.post")
            webView.evaluateJavascript("window.updateWebState?.('${state.toString()}');", null)
        }
    }

    private fun resolveStorageStats(uri: Uri): Triple<Long, Long, Int> {
        if (uri == Uri.EMPTY) return Triple(0L, 0L, 0)
        return try {
            var capacityBytes = 0L
            var availableBytes = 0L
            
            if (uri.scheme == "file" && uri.path != null) {
                val stat = android.os.StatFs(uri.path!!)
                val blockSize = stat.blockSizeLong
                val totalBlocks = stat.blockCountLong
                val availableBlocks = stat.availableBlocksLong
                capacityBytes = totalBlocks * blockSize
                availableBytes = availableBlocks * blockSize
            } else {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val uuidStr = docId?.substringBefore(":") ?: "primary"
                
                try {
                    val statsManager = getSystemService(Context.STORAGE_STATS_SERVICE) as android.app.usage.StorageStatsManager
                    val uuidObj = if (uuidStr.equals("primary", ignoreCase = true)) {
                        android.os.storage.StorageManager.UUID_DEFAULT
                    } else {
                        java.util.UUID.fromString(uuidStr)
                    }
                    capacityBytes = statsManager.getTotalBytes(uuidObj)
                    availableBytes = statsManager.getFreeBytes(uuidObj)
                } catch (e: Exception) {
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
                        }
                    } catch (fallbackEx: Exception) {
                        // Total failure
                    }
                }
            }
            
            if (capacityBytes <= 0L) {
                // Absolute fallback to internal storage stats if SAF & UUID resolution fails
                val internalStats = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                capacityBytes = internalStats.blockCountLong * internalStats.blockSizeLong
                availableBytes = internalStats.availableBlocksLong * internalStats.blockSizeLong
            }
            
            val used = capacityBytes - availableBytes
            val percent = if (capacityBytes > 0L) ((used.toDouble() / capacityBytes.toDouble()) * 100).toInt() else 0
            android.util.Log.e("STORAGE_DEBUG", "Resolved Native UI Storage: Total=$capacityBytes Free=$availableBytes Used=$used Pct=$percent")
            Triple(used, capacityBytes, percent)
        } catch (e: Exception) {
            Triple(0L, 0L, 0)
        }
    }

    private fun resolveFolderName(uri: Uri): String? {
        if (uri == Uri.EMPTY) return null
        return try {
            DocumentFile.fromTreeUri(this, uri)?.name
        } catch (e: Exception) {
            null
        }
    }

    private fun showNotification(title: String, message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "cloud_storage_alerts",
                "Cloud Storage Alerts",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
        val builder = androidx.core.app.NotificationCompat.Builder(this, "cloud_storage_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
        manager.notify((System.currentTimeMillis() % Integer.MAX_VALUE).toInt(), builder.build())
    }

    private fun selectFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        selectFolderLauncher.launch(intent)
    }

    private fun toggleNode() {
        if (selectedUri == null) {
            Toast.makeText(this, "Please select a storage folder first", Toast.LENGTH_SHORT).show()
            return
        }

        if (isNodeRunning) {
            android.util.Log.e("NODE_DEBUG", "Shutting down the engine...")
            val stopIntent = Intent(this, ServerService::class.java).apply {
                action = ServerService.ACTION_STOP_SERVER
            }
            startService(stopIntent)
            isNodeRunning = false
        } else {
            android.util.Log.e("NODE_DEBUG", "Launching the engine on relay: $relayBaseUrl")
            val startIntent = Intent(this, ServerService::class.java).apply {
                action = ServerService.ACTION_START_SERVER
                putExtra(ServerService.EXTRA_URI, selectedUri.toString())
                putExtra(ServerService.EXTRA_SHARE_CODE, shareCode)
                putExtra(ServerService.EXTRA_RELAY_BASE_URL, relayBaseUrl)
            }
            ContextCompat.startForegroundService(this, startIntent)
            isNodeRunning = true
        }
        updateWebState()
    }

    private fun generateAndPersistShareCode(): String {
        val newCode = generateShareCode()
        preferences.edit().putString(PREF_SHARE_CODE, newCode).apply()
        return newCode
    }

    private fun shareInvite() {
        val inviteLink = buildInviteLink(shareCode)
        val publicUrl = buildRelayBrowserUrl(relayBaseUrl, shareCode)
        val shareText = buildSharePayload(shareCode, inviteLink, publicUrl)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Easy Storage Cloud Invite")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(sendIntent, "Share App Invite"))
    }

    private fun copyToClipboard(value: String, toastText: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("value", value))
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
    }

    fun updateRelayBaseUrl(url: String) {
        relayBaseUrl = url.trim()
        preferences.edit().putString(PREF_RELAY_BASE_URL, relayBaseUrl).apply()
        updateWebState()
    }

    private fun scanDocument() {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "TempScan.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        tempCameraUri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, tempCameraUri)
        }
        scanDocumentLauncher.launch(intent)
    }

    private fun scanQRCode() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Scan to authenticate local device")
        options.setCameraId(0)
        options.setBeepEnabled(false)
        options.setBarcodeImageEnabled(true)
        scanQrLauncher.launch(options)
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val PREFS_NAME = "cloud_storage_app"
        private const val PREF_SELECTED_URI = "selected_uri"
        private const val PREF_SHARE_CODE = "share_code"
        private const val PREF_RELAY_BASE_URL = "relay_base_url"
    }
}

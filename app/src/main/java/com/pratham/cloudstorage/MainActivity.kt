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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import android.Manifest
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
import com.pratham.cloudstorage.ui.theme.DarkBackground
import com.pratham.cloudstorage.ui.theme.DarkSurface
import com.pratham.cloudstorage.ui.theme.DarkDivider
import com.pratham.cloudstorage.ui.theme.PrimaryBlue
import com.pratham.cloudstorage.ui.theme.TextSecondary
import com.pratham.cloudstorage.ui.theme.TextPrimary
import com.pratham.cloudstorage.ui.theme.SuccessGreen
import com.pratham.cloudstorage.ui.theme.HighlightPurple
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.AlertDialog

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
    private var showPasswordSetup by mutableStateOf(false)
    
    private lateinit var assetLoader: WebViewAssetLoader

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

    private fun formatSpeed(bps: Long): String {
        return when {
            bps >= 1_048_576 -> "%.1f MB/s".format(bps / 1_048_576.0)
            bps >= 1024 -> "${bps / 1024} KB/s"
            else -> "$bps B/s"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = listOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return String.format("%.1f %s", value, units[unitIndex])
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        selectedUri = preferences.getString(PREF_SELECTED_URI, null)?.let(Uri::parse)
        shareCode = preferences.getString(PREF_SHARE_CODE, null) ?: generateAndPersistShareCode()
        
        val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(requiredPermissions)
        
        val savedRelayUrl = preferences.getString(PREF_RELAY_BASE_URL, null).orEmpty()
        if (savedRelayUrl.isBlank()) {
            relayBaseUrl = sanitizeUrl(BuildConfig.RELAY_BASE_URL)
            preferences.edit().putString(PREF_RELAY_BASE_URL, relayBaseUrl).apply()
        } else {
            relayBaseUrl = sanitizeUrl(savedRelayUrl)
        }


        assetLoader = WebViewAssetLoader.Builder()
            .setDomain("app.local.cloud")
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        setupWebView()
        webView.loadUrl("https://app.local.cloud/web/index.html")



        setContent {
            CloudStorageTheme {
                MainScreen()
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServerService.tunnelStatusFlow
                    .debounce(1000L)
                    .distinctUntilChanged()
                    .collect { status ->
                    if (status == TunnelStatus.Error && isNodeRunning) {
                        Toast.makeText(this@MainActivity, "Relay Connection Error: Check Endpoint URL & Internet", Toast.LENGTH_LONG).show()
                    }
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
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                databaseEnabled = true
                // CRITICAL: Allow HTTPS to fetch HTTP
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                userAgentString = userAgentString + " EasyStorageAndroid/1.0"
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
            }
            
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

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    return request?.let { assetLoader.shouldInterceptRequest(it.url) }
                }
            }
            addJavascriptInterface(WebAppInterface(), "Android")
        }
    }
    
    @Composable
    private fun MainScreen() {
        val transferState by UploadNotificationManager.cardState.collectAsState()
        val nodeStatus by UploadNotificationManager.nodeStatus.collectAsState()

        // Trigger password setup only when node starts for the first time
        LaunchedEffect(nodeStatus) {
            if (nodeStatus == NodeStatus.ACTIVE) {
                val authPrefs = getSharedPreferences("NodeAuthSettings", android.content.Context.MODE_PRIVATE)
                if (!authPrefs.contains("password_hash")) {
                    showPasswordSetup = true
                }
            }
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Main WebView area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Node Status Section at the bottom — handles boot animation, live card, and transfers
            NodeStatusSection(
                nodeStatus = nodeStatus,
                transferState = transferState,
                modifier = Modifier
                    .padding(16.dp)
                    .padding(bottom = 8.dp)
            )
        }

        // Password Setup Dialog — blocks UI until password is set
        if (showPasswordSetup) {
            NodePasswordSetupDialog(
                onCredentialsSet = { username, password ->
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    val hash = java.util.Base64.getEncoder().encodeToString(md.digest(password.toByteArray()))
                    val token = java.util.UUID.randomUUID().toString()
                    val authPrefs = getSharedPreferences("NodeAuthSettings", Context.MODE_PRIVATE)
                    authPrefs.edit()
                        .putString("username", username.trim().lowercase())
                        .putString("password_hash", hash)
                        .putString("active_token", token)
                        .apply()
                    showPasswordSetup = false
                    Toast.makeText(this@MainActivity, "Node identity set successfully", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    @Composable
    private fun NodePasswordSetupDialog(onCredentialsSet: (String, String) -> Unit) {
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        Dialog(onDismissRequest = { /* Block dismiss — password is mandatory */ }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Icon
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(PrimaryBlue, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "\uD83D\uDD12",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "Secure Your Node",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Set a username and password to protect your storage node. These will be required to access the web console.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; error = null },
                        label = { Text("Username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = DarkDivider,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = PrimaryBlue,
                            unfocusedLabelColor = TextSecondary,
                            cursorColor = PrimaryBlue
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = DarkDivider,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = PrimaryBlue,
                            unfocusedLabelColor = TextSecondary,
                            cursorColor = PrimaryBlue
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; error = null },
                        label = { Text("Confirm Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryBlue,
                            unfocusedBorderColor = DarkDivider,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = PrimaryBlue,
                            unfocusedLabelColor = TextSecondary,
                            cursorColor = PrimaryBlue
                        )
                    )

                    // Error message
                    error?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = it,
                            color = Color(0xFFEF4444),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            when {
                                username.trim().isEmpty() -> error = "Username is required"
                                password.length < 4 -> error = "Password must be at least 4 characters"
                                password != confirmPassword -> error = "Passwords do not match"
                                else -> onCredentialsSet(username, password)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = "Lock Node",
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "You can change this later in Settings",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }
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
                    put("publicUrl", NodeUrlBuilder.buildWebConsoleUrl(relayBaseUrl, shareCode) ?: "")
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

        @JavascriptInterface
        fun resetNodePassword() {
            runOnUiThread {
                val prefs = getSharedPreferences("NodeAuthSettings", android.content.Context.MODE_PRIVATE)
                prefs.edit().clear().apply()
                android.widget.Toast.makeText(this@MainActivity, "Node Password has been reset", android.widget.Toast.LENGTH_SHORT).show()
                updateWebState() // Broadcast new state if needed
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
                put("publicUrl", NodeUrlBuilder.buildWebConsoleUrl(relayBaseUrl, shareCode) ?: "")
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
            // Safely escape single quotes and backslashes for the JS string literal
            val escapedJson = state.toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'")
            webView.evaluateJavascript("if(window.updateWebState) window.updateWebState('$escapedJson');", null)
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
            preferences.edit().putBoolean("node_was_running", false).apply()
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
            preferences.edit().putBoolean("node_was_running", true).apply()

            // Prompt battery optimization exemption on first node start
            promptBatteryOptimization()
        }
        updateWebState()
    }

    private fun generateAndPersistShareCode(): String {
        val newCode = generateShareCode()
        preferences.edit().putString(PREF_SHARE_CODE, newCode).apply()
        return newCode
    }

    private fun shareInvite() {
        val shareText = NodeUrlBuilder.buildSharePayload(shareCode)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Easy Storage Cloud Invite")
            putExtra(Intent.EXTRA_TEXT, shareText as String)
        }
        startActivity(Intent.createChooser(sendIntent, "Share App Invite"))
    }

    private fun copyToClipboard(value: String, toastText: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("value", value))
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
    }

    fun updateRelayBaseUrl(url: String) {
        val sanitized = sanitizeUrl(url)
        if (sanitized.isBlank()) {
            Toast.makeText(this, "Endpoint cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        relayBaseUrl = sanitized
        preferences.edit().putString(PREF_RELAY_BASE_URL, relayBaseUrl).apply()
        Toast.makeText(this, "Relay Endpoint Updated", Toast.LENGTH_SHORT).show()
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

    private fun promptBatteryOptimization() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("NODE_DEBUG", "Battery optimization prompt failed", e)
        }
    }
}

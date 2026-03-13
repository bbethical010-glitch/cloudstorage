package com.pratham.cloudstorage

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

class MainActivity : ComponentActivity() {

    private lateinit var preferences: SharedPreferences

    private lateinit var webView: WebView

    private var selectedUri by mutableStateOf<Uri?>(null)
    private var shareCode by mutableStateOf("")
    private var relayBaseUrl by mutableStateOf("")
    private var isNodeRunning by mutableStateOf(false)

    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    selectedUri = uri
                    preferences.edit().putString(PREF_SELECTED_URI, uri.toString()).apply()
                    updateWebState()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        selectedUri = preferences.getString(PREF_SELECTED_URI, null)?.let(Uri::parse)
        shareCode = preferences.getString(PREF_SHARE_CODE, null) ?: generateAndPersistShareCode()
        relayBaseUrl = preferences.getString(PREF_RELAY_BASE_URL, null).orEmpty()

        setupWebView()
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
            
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        val msg = "JS Console: ${it.message()} -- From line ${it.lineNumber()} of ${it.sourceId()}"
                        android.util.Log.d("WebViewDebug", msg)
                        if (it.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "JS Error: ${it.message()}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    return true
                }
            }
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    android.util.Log.d("WebViewDebug", "Page loaded: $url")
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
        webView.loadUrl("file:///android_asset/www/index.html")
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun getInitialState(): String {
            val stats = resolveStorageStats(selectedUri ?: Uri.EMPTY)
            val state = JSONObject().apply {
                put("folderName", resolveFolderName(selectedUri ?: Uri.EMPTY))
                put("shareCode", shareCode)
                put("relayBaseUrl", relayBaseUrl)
                put("isNodeRunning", isNodeRunning)
                put("storageUsed", stats.first)
                put("storageTotal", stats.second)
                put("usagePercent", stats.third)
            }
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
    }

    private fun updateWebState() {
        if (!::webView.isInitialized) return
        val stats = resolveStorageStats(selectedUri ?: Uri.EMPTY)
        val state = JSONObject().apply {
            put("folderName", resolveFolderName(selectedUri ?: Uri.EMPTY))
            put("shareCode", shareCode)
            put("relayBaseUrl", relayBaseUrl)
            put("isNodeRunning", isNodeRunning)
            put("storageUsed", stats.first)
            put("storageTotal", stats.second)
            put("usagePercent", stats.third)
        }
        webView.post {
            webView.evaluateJavascript("window.updateWebState?.('${state.toString()}');", null)
        }
    }

    private fun resolveStorageStats(uri: Uri): Triple<Long, Long, Int> {
        if (uri == Uri.EMPTY) return Triple(0L, 0L, 0)
        return try {
            val file = DocumentFile.fromTreeUri(this, uri) ?: return Triple(0L, 0L, 0)
            val stats = android.os.StatFs(uri.path) // Note: This might not work globally for all SAF URIs, but good for local
            // For SAF folders, we might need a different approach or just mock it if StatFs fails
            // Actually SAF doesn't easily give 'total' size of the whole volume via URI always.
            // But we can try to get it from the File object if available or just use system storage stats as fallback.
            
            // Fallback to internal storage stats for visual consistency if SAF stats fail to resolve
            val internalStats = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val total = internalStats.totalBytes / (1024 * 1024 * 1024)
            val free = internalStats.availableBytes / (1024 * 1024 * 1024)
            val used = total - free
            val percent = if (total > 0) ((used.toDouble() / total.toDouble()) * 100).toInt() else 0
            Triple(used, total, percent)
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
            val stopIntent = Intent(this, ServerService::class.java).apply {
                action = ServerService.ACTION_STOP_SERVER
            }
            startService(stopIntent)
            isNodeRunning = false
        } else {
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

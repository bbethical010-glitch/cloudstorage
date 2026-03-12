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

class MainActivity : ComponentActivity() {

    private lateinit var preferences: SharedPreferences

    private var selectedUri by mutableStateOf<Uri?>(null)
    private var shareCode by mutableStateOf("")
    private var relayBaseUrl by mutableStateOf("")
    private var relayBaseUrlDraft by mutableStateOf("")
    private var isNodeRunning by mutableStateOf(false)
    private var showOnboarding by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)
    private var errorMessage by mutableStateOf<String?>(null)
    private var pendingInviteCode by mutableStateOf<String?>(null)
    private var showInviteConsole by mutableStateOf(false)
    private var embeddedConsoleUrl by mutableStateOf<String?>(null)

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
                    statusMessage = "Storage node mounted. The selected drive will remain the source of truth."
                    errorMessage = null
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        selectedUri = preferences.getString(PREF_SELECTED_URI, null)?.let(Uri::parse)
        shareCode = preferences.getString(PREF_SHARE_CODE, null) ?: generateAndPersistShareCode()
        relayBaseUrl = preferences.getString(PREF_RELAY_BASE_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.RELAY_BASE_URL.trim()
        relayBaseUrlDraft = relayBaseUrl
        showOnboarding = !preferences.getBoolean(PREF_ONBOARDING_DONE, false)

        handleIncomingIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EdgeNodeScreen(
                        folderName = selectedUri?.let { resolveFolderName(it) },
                        shareCode = shareCode,
                        relayBaseUrl = relayBaseUrl,
                        relayBaseUrlDraft = relayBaseUrlDraft,
                        localLinks = buildLocalAccessUrls(DEFAULT_PORT),
                        publicConsoleUrl = buildRelayBrowserUrl(relayBaseUrl, shareCode),
                        inviteLink = buildInviteLink(shareCode),
                        isNodeRunning = isNodeRunning,
                        statusMessage = statusMessage,
                        errorMessage = errorMessage,
                        onRelayDraftChange = { relayBaseUrlDraft = it },
                        onSaveRelay = { saveRelayBaseUrl() },
                        onSelectFolderClick = { selectFolder() },
                        onToggleNodeClick = { toggleNode() },
                        onRegenerateCodeClick = { regenerateShareCode() },
                        onShowTutorial = { showOnboarding = true },
                        onShareInviteClick = { shareInvite() },
                        onCopyCodeClick = { copyCodeToClipboard(shareCode) },
                        onOpenEmbeddedConsole = { url -> embeddedConsoleUrl = url },
                        onCopyLocalLinkClick = { link -> copyToClipboard(link, getString(R.string.link_copied)) }
                    )

                    if (showInviteConsole && pendingInviteCode != null) {
                        InviteConsoleScreen(
                            inviteCode = pendingInviteCode.orEmpty(),
                            publicConsoleUrl = buildRelayBrowserUrl(relayBaseUrl, pendingInviteCode.orEmpty()),
                            onConnect = {
                                val url = buildRelayBrowserUrl(relayBaseUrl, pendingInviteCode.orEmpty())
                                if (url.isNullOrBlank()) {
                                    errorMessage = "This invite opened correctly, but no relay endpoint is configured in the app yet."
                                } else {
                                    embeddedConsoleUrl = url
                                }
                                showInviteConsole = false
                            },
                            onCopyCode = {
                                pendingInviteCode?.let { copyCodeToClipboard(it) }
                            },
                            onDismiss = { showInviteConsole = false }
                        )
                    }

                    embeddedConsoleUrl?.let { url ->
                        EmbeddedConsoleScreen(
                            url = url,
                            onClose = { embeddedConsoleUrl = null }
                        )
                    }

                    if (showOnboarding) {
                        OnboardingDialog(
                            onDismiss = {
                                showOnboarding = false
                                preferences.edit().putBoolean(PREF_ONBOARDING_DONE, true).apply()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        val code = data.getQueryParameter("code")?.trim().orEmpty()
        if (code.isBlank()) {
            return
        }
        pendingInviteCode = code
        showInviteConsole = true
        statusMessage = "Invite route opened. The app will keep the access flow inside the node client."
    }

    private fun resolveFolderName(uri: Uri): String? {
        return DocumentFile.fromTreeUri(this, uri)?.name
    }

    private fun selectFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }
        selectFolderLauncher.launch(intent)
    }

    private fun saveRelayBaseUrl() {
        relayBaseUrl = normalizeRelayBaseUrl(relayBaseUrlDraft)
        preferences.edit().putString(PREF_RELAY_BASE_URL, relayBaseUrl).apply()
        statusMessage = if (relayBaseUrl.isBlank()) {
            "Relay endpoint cleared. The node will stay local-network only."
        } else {
            "Relay endpoint saved. Public console URLs will now target $relayBaseUrl."
        }
        errorMessage = null
    }

    private fun toggleNode() {
        if (selectedUri == null) {
            errorMessage = getString(R.string.select_folder_first)
            return
        }

        if (isNodeRunning) {
            val stopIntent = Intent(this, ServerService::class.java).apply {
                action = ServerService.ACTION_STOP_SERVER
            }
            startService(stopIntent)
            isNodeRunning = false
            statusMessage = "Edge node stopped."
            return
        }

        val startIntent = Intent(this, ServerService::class.java).apply {
            action = ServerService.ACTION_START_SERVER
            putExtra(ServerService.EXTRA_URI, selectedUri.toString())
            putExtra(ServerService.EXTRA_SHARE_CODE, shareCode)
            putExtra(ServerService.EXTRA_RELAY_BASE_URL, relayBaseUrl)
        }
        ContextCompat.startForegroundService(this, startIntent)
        isNodeRunning = true
        statusMessage = if (relayBaseUrl.isBlank()) {
            "Edge node started for local access. Add a relay endpoint for worldwide access."
        } else {
            "Edge node started. Requests can be proxied through your relay while files remain on the drive."
        }
        errorMessage = null
    }

    private fun regenerateShareCode() {
        shareCode = generateAndPersistShareCode()
        statusMessage = "New node share code generated."
        errorMessage = null
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
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_chooser_title)))
    }

    private fun copyCodeToClipboard(code: String) {
        copyToClipboard(code, getString(R.string.code_copied))
    }

    private fun copyToClipboard(value: String, toastText: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("value", value))
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PREFS_NAME = "cloud_storage_app"
        private const val PREF_SELECTED_URI = "selected_uri"
        private const val PREF_ONBOARDING_DONE = "onboarding_done"
        private const val PREF_SHARE_CODE = "share_code"
        private const val PREF_RELAY_BASE_URL = "relay_base_url"
    }
}

@Composable
private fun EdgeNodeScreen(
    folderName: String?,
    shareCode: String,
    relayBaseUrl: String,
    relayBaseUrlDraft: String,
    localLinks: List<String>,
    publicConsoleUrl: String?,
    inviteLink: String,
    isNodeRunning: Boolean,
    statusMessage: String?,
    errorMessage: String?,
    onRelayDraftChange: (String) -> Unit,
    onSaveRelay: () -> Unit,
    onSelectFolderClick: () -> Unit,
    onToggleNodeClick: () -> Unit,
    onRegenerateCodeClick: () -> Unit,
    onShowTutorial: () -> Unit,
    onShareInviteClick: () -> Unit,
    onCopyCodeClick: () -> Unit,
    onOpenEmbeddedConsole: (String) -> Unit,
    onCopyLocalLinkClick: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            HeroCard(onShowTutorial = onShowTutorial)

            StorageCard(
                folderName = folderName,
                onSelectFolderClick = onSelectFolderClick
            )

            IdentityCard(
                shareCode = shareCode,
                inviteLink = inviteLink,
                onCopyCodeClick = onCopyCodeClick,
                onRegenerateCodeClick = onRegenerateCodeClick,
                onCopyInviteLinkClick = {
                    clipboardManager.setText(AnnotatedString(inviteLink))
                    Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                }
            )

            RelayCard(
                relayBaseUrl = relayBaseUrl,
                relayBaseUrlDraft = relayBaseUrlDraft,
                publicConsoleUrl = publicConsoleUrl,
                onRelayDraftChange = onRelayDraftChange,
                onSaveRelay = onSaveRelay,
                onOpenEmbeddedConsole = onOpenEmbeddedConsole
            )

            NodeControlCard(
                isNodeRunning = isNodeRunning,
                localLinks = localLinks,
                publicConsoleUrl = publicConsoleUrl,
                onToggleNodeClick = onToggleNodeClick,
                onShareInviteClick = onShareInviteClick,
                onOpenEmbeddedConsole = onOpenEmbeddedConsole,
                onCopyLocalLinkClick = onCopyLocalLinkClick
            )

            ArchitectureCard()

            AnimatedVisibility(
                visible = statusMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                StatusCard(message = statusMessage ?: "", isError = false)
            }

            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                StatusCard(message = errorMessage ?: "", isError = true)
            }
        }
    }
}

@Composable
private fun HeroCard(onShowTutorial: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "External drive edge node",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "The connected pen drive or SSD remains the real storage server. The phone only exposes it through a local node API, and an external relay can forward requests from anywhere without moving the files into cloud storage.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            TextButton(onClick = onShowTutorial) {
                Text("Show quick tutorial")
            }
        }
    }
}

@Composable
private fun StorageCard(
    folderName: String?,
    onSelectFolderClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "1. Mount the storage node",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = folderName ?: "No external storage folder selected yet",
                style = MaterialTheme.typography.bodyLarge,
                color = if (folderName == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            OutlinedButton(onClick = onSelectFolderClick) {
                Text(if (folderName == null) "Choose pen drive or SSD folder" else "Change storage folder")
            }
        }
    }
}

@Composable
private fun IdentityCard(
    shareCode: String,
    inviteLink: String,
    onCopyCodeClick: () -> Unit,
    onRegenerateCodeClick: () -> Unit,
    onCopyInviteLinkClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "2. Node identity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            TechnicalValue(label = "share_code", value = shareCode)
            TechnicalValue(label = "invite_link", value = inviteLink)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCopyCodeClick) {
                    Text("Copy code")
                }
                OutlinedButton(onClick = onCopyInviteLinkClick) {
                    Text("Copy invite")
                }
                OutlinedButton(onClick = onRegenerateCodeClick) {
                    Text("Regenerate")
                }
            }
        }
    }
}

@Composable
private fun RelayCard(
    relayBaseUrl: String,
    relayBaseUrlDraft: String,
    publicConsoleUrl: String?,
    onRelayDraftChange: (String) -> Unit,
    onSaveRelay: () -> Unit,
    onOpenEmbeddedConsole: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "3. Public relay route",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Add the base URL of the relay or tunnel that forwards traffic to this phone. Files still stay on the connected drive.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = relayBaseUrlDraft,
                onValueChange = onRelayDraftChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Relay base URL") },
                placeholder = { Text("https://relay.example.com") },
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSaveRelay) {
                    Text("Save relay")
                }
                if (!publicConsoleUrl.isNullOrBlank()) {
                    OutlinedButton(onClick = { onOpenEmbeddedConsole(publicConsoleUrl) }) {
                        Text("Preview public console")
                    }
                }
            }
            TechnicalValue(
                label = "public_console",
                value = publicConsoleUrl ?: "relay_not_configured"
            )
            if (relayBaseUrl.isBlank()) {
                Text(
                    text = "Without a relay endpoint, the node can only be reached on the same local network.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun NodeControlCard(
    isNodeRunning: Boolean,
    localLinks: List<String>,
    publicConsoleUrl: String?,
    onToggleNodeClick: () -> Unit,
    onShareInviteClick: () -> Unit,
    onOpenEmbeddedConsole: (String) -> Unit,
    onCopyLocalLinkClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "4. Run the node",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (isNodeRunning) "Edge node online" else "Edge node offline",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isNodeRunning) Color(0xFF147D64) else MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onToggleNodeClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isNodeRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isNodeRunning) "Stop node" else "Start node")
                }
                OutlinedButton(onClick = onShareInviteClick) {
                    Text("Share invite")
                }
            }

            if (localLinks.isNotEmpty()) {
                Text(
                    text = "Local console routes",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                localLinks.forEach { link ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = link,
                            modifier = Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = { onCopyLocalLinkClick(link) }) {
                            Text("Copy")
                        }
                    }
                }
            }

            if (!publicConsoleUrl.isNullOrBlank()) {
                Text(
                    text = "Remote console route",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                TechnicalValue(label = "relay_target", value = publicConsoleUrl)
                OutlinedButton(onClick = { onOpenEmbeddedConsole(publicConsoleUrl) }) {
                    Text("Open remote console in app")
                }
            }
        }
    }
}

@Composable
private fun ArchitectureCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "How this design works",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "The drive stays attached to the phone. The phone exposes a local Ktor node. A relay or reverse tunnel only forwards requests so remote users can reach that node from anywhere. The relay is for reachability, not for storing your files.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun TechnicalValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            textAlign = TextAlign.End,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun InviteConsoleScreen(
    inviteCode: String,
    publicConsoleUrl: String?,
    onConnect: () -> Unit,
    onCopyCode: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF071018))
            .padding(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1622)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1C9ED8))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color(0xFF36F3C8), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "EDGE NODE INVITE DETECTED",
                            color = Color(0xFF89F5DD),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Connect to a remote drive node",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "This invite stays app-first. If a relay endpoint exists, the app can open the remote console inside the embedded client.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFFB9C7D5)
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF09131E)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2F4863))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "SESSION METADATA",
                        color = Color(0xFF6DD3FF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    TechnicalRow(label = "protocol", value = "edge-node-relay")
                    TechnicalRow(label = "share_code", value = inviteCode)
                    TechnicalRow(label = "public_console", value = publicConsoleUrl ?: "not_configured")
                    TechnicalRow(label = "storage_mode", value = "drive-stays-on-host")
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1724)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF17445F))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "NEXT STEP",
                        color = Color(0xFF8ED8FF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (publicConsoleUrl.isNullOrBlank()) {
                            "The invite code opened correctly, but this app instance does not have a relay endpoint saved yet."
                        } else {
                            "Press Connect to open the remote node console inside the app."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD2E1EE)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onConnect,
                            enabled = !publicConsoleUrl.isNullOrBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E7490))
                        ) {
                            Text("Connect")
                        }
                        OutlinedButton(onClick = onCopyCode) {
                            Text("Copy code")
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Open app home")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmbeddedConsoleScreen(
    url: String,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF040B11))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0C1622))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "REMOTE NODE CONSOLE",
                        color = Color(0xFF87E1FF),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = url,
                        color = Color(0xFFC7D7E6),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(onClick = onClose) {
                    Text("Close")
                }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        loadUrl(url)
                    }
                },
                update = { webView ->
                    if (webView.url != url) {
                        webView.loadUrl(url)
                    }
                }
            )
        }
    }
}

@Composable
private fun TechnicalRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF173145), RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color(0xFF78A6C5),
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun StatusCard(message: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isError) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(text = message)
        }
    }
}

@Composable
private fun OnboardingDialog(onDismiss: () -> Unit) {
    val pages = remember {
        listOf(
            TutorialPage(
                title = "Mount a drive",
                body = "Choose the folder that represents your pen drive or external SSD on the phone. That storage remains the source of truth."
            ),
            TutorialPage(
                title = "Start the node",
                body = "The app launches an on-device node API that can list files, download them, and accept uploads directly onto the drive."
            ),
            TutorialPage(
                title = "Reach it from anywhere",
                body = "A relay or reverse tunnel gives public reachability. It forwards requests to the phone, but it does not store your files."
            )
        )
    }
    var pageIndex by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = pages[pageIndex].title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = pages[pageIndex].body,
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(pages.size) { index ->
                        Box(
                            modifier = Modifier
                                .size(width = 24.dp, height = 6.dp)
                                .background(
                                    color = if (pageIndex == index) MaterialTheme.colorScheme.primary else Color.LightGray,
                                    shape = RoundedCornerShape(999.dp)
                                )
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(if (pageIndex == pages.lastIndex) "Close" else "Skip")
                    }
                    Button(
                        onClick = {
                            if (pageIndex == pages.lastIndex) {
                                onDismiss()
                            } else {
                                pageIndex += 1
                            }
                        }
                    ) {
                        Text(if (pageIndex == pages.lastIndex) "Start using app" else "Next")
                    }
                }
            }
        }
    }
}

private data class TutorialPage(
    val title: String,
    val body: String
)

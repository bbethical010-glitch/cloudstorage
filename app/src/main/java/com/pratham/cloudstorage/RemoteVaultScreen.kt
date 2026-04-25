package com.pratham.cloudstorage

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RemoteVaultScreen(
    viewModel: RemoteVaultViewModel,
    modifier: Modifier = Modifier
) {
    val status by viewModel.connectionStatus.collectAsState()
    val shareCode by viewModel.shareCode.collectAsState()
    val files by viewModel.remoteFiles.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val error by viewModel.error.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C14))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- Top Bar ---
            RemoteVaultTopBar(
                status = status,
                shareCode = shareCode,
                onDisconnect = { viewModel.disconnect() }
            )

            // --- Content ---
            Box(modifier = Modifier.weight(1f)) {
                when (status) {
                    RemoteConnectionStatus.IDLE, RemoteConnectionStatus.CONNECTING, RemoteConnectionStatus.ERROR -> {
                        ConnectView(
                            shareCode = shareCode,
                            onShareCodeChange = { viewModel.updateShareCode(it) },
                            onConnect = { viewModel.connect() },
                            isConnecting = status == RemoteConnectionStatus.CONNECTING,
                            error = error
                        )
                    }
                    RemoteConnectionStatus.CONNECTED -> {
                        RemoteFileExplorer(
                            path = currentPath,
                            files = files,
                            onFileClick = { file ->
                                if (file.isDirectory) {
                                    viewModel.navigateTo(file.path)
                                } else {
                                    viewModel.downloadFile(file)
                                }
                            },
                            onBackClick = {
                                val parent = currentPath.substringBeforeLast("/", "")
                                viewModel.navigateTo(parent)
                            }
                        )
                    }
                    RemoteConnectionStatus.DISCONNECTED -> {
                        // Handled by IDLE state logic for now
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteVaultTopBar(
    status: RemoteConnectionStatus,
    shareCode: String,
    onDisconnect: () -> Unit
) {
    Surface(
        color = Color(0xFF111420),
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "REMOTE VAULT",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF3B82F6),
                        letterSpacing = 0.1.sp
                    )
                )
                if (status == RemoteConnectionStatus.CONNECTED) {
                    Text(
                        text = "NODE: $shareCode",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF22C55E)
                        )
                    )
                }
            }

            if (status == RemoteConnectionStatus.CONNECTED) {
                IconButton(onClick = onDisconnect) {
                    Icon(
                        imageVector = Icons.Rounded.Logout,
                        contentDescription = "Disconnect",
                        tint = Color(0xFFEF4444)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectView(
    shareCode: String,
    onShareCodeChange: (String) -> Unit,
    onConnect: () -> Unit,
    isConnecting: Boolean,
    error: String?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Dns,
            contentDescription = null,
            tint = Color(0xFF3B82F6),
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Connect to Remote Node",
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        )
        
        Text(
            text = "Enter the 5-6 character ShareCode of the host device",
            style = TextStyle(
                fontSize = 14.sp,
                color = Color(0xFF7A8099),
                textAlign = TextAlign.Center
            ),
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp)
        )

        OutlinedTextField(
            value = shareCode,
            onValueChange = onShareCodeChange,
            label = { Text("ShareCode", color = Color(0xFF3A3F58)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            ),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                focusedBorderColor = Color(0xFF3B82F6),
                unfocusedBorderColor = Color(0xFF1C2035),
                cursorColor = Color(0xFF3B82F6)
            ),
            enabled = !isConnecting
        )

        if (error != null) {
            Text(
                text = error,
                color = Color(0xFFEF4444),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3B82F6),
                disabledContainerColor = Color(0xFF1C2035)
            ),
            enabled = !isConnecting && shareCode.isNotBlank()
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text("Establish P2P Link", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun RemoteFileExplorer(
    path: String,
    files: List<RemoteFile>,
    onFileClick: (RemoteFile) -> Unit,
    onBackClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // --- Breadcrumbs ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F121D))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (path.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(0xFF7A8099),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onBackClick() }
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            
            Text(
                text = if (path.isEmpty()) "/" else path,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color(0xFF7A8099)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // --- File List ---
        if (files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No files found",
                    color = Color(0xFF3A3F58),
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(files) { file ->
                    val progress = viewModel.getFileProgress(file.path)
                    RemoteFileRow(
                        file = file, 
                        progress = progress,
                        onClick = { onFileClick(file) }
                    )
                }
            }
        }
    }
}

@Composable
fun RemoteFileRow(
    file: RemoteFile,
    progress: Float?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getFileIcon(file),
                contentDescription = null,
                tint = if (file.isDirectory) Color(0xFFFACC15) else Color(0xFF7A8099),
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                if (!file.isDirectory) {
                    Text(
                        text = if (progress != null) "Downloading..." else formatFileSize(file.size),
                        style = TextStyle(
                            fontSize = 11.sp,
                            color = if (progress != null) Color(0xFF3B82F6) else Color(0xFF3A3F58),
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            if (!file.isDirectory) {
                if (progress != null) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = Color(0xFF3B82F6),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(end = 4.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Download",
                        tint = Color(0xFF3B82F6).copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = Color(0xFF1C2035),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        
        if (progress != null) {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(horizontal = 16.dp),
                color = Color(0xFF3B82F6),
                trackColor = Color(0xFF1C2035)
            )
        }
    }
}

private fun getFileIcon(file: RemoteFile): ImageVector {
    return if (file.isDirectory) {
        Icons.Rounded.Folder
    } else {
        when {
            file.name.endsWith(".jpg", true) || file.name.endsWith(".png", true) -> Icons.Rounded.Image
            file.name.endsWith(".mp4", true) || file.name.endsWith(".mkv", true) -> Icons.Rounded.VideoFile
            file.name.endsWith(".pdf", true) -> Icons.Rounded.PictureAsPdf
            file.name.endsWith(".zip", true) || file.name.endsWith(".tar", true) -> Icons.Rounded.FolderZip
            else -> Icons.Rounded.Description
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return String.format("%.1f %s", value, units[unitIndex])
}

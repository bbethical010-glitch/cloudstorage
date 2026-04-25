package com.pratham.cloudstorage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val TAG = "P2PClientService"
private const val CHANNEL_ID = "P2PClientChannel"
private const val NOTIFICATION_ID = 101

/**
 * Service to manage outbound P2P client connections and background downloads.
 */
class P2PClientService : Service(), DownloadListener {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    
    private var client: RemoteNodeClient? = null
    private val _clientFlow = MutableStateFlow<RemoteNodeClient?>(null)
    val clientFlow = _clientFlow.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    private val totalBytesMap = mutableMapOf<String, Long>()

    inner class LocalBinder : Binder() {
        fun getService(): P2PClientService = this@P2PClientService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Starts a P2P client connection for a specific share code.
     */
    fun startClient(shareCode: String, relayUrl: String) {
        if (client != null) {
            client?.disconnect()
        }

        client = RemoteNodeClient(this, relayUrl, shareCode, this).also {
            it.connect()
            _clientFlow.value = it
        }
        
        showForegroundNotification("Connecting to $shareCode...")
    }

    /**
     * Disconnects the current client.
     */
    fun stopClient() {
        client?.disconnect()
        client = null
        _clientFlow.value = null
        stopForeground(true)
        releaseWakeLock()
    }

    private fun showForegroundNotification(text: String, progress: Int = -1) {
        val notification = createNotification(text, progress)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String, progress: Int = -1) {
        val notification = createNotification(text, progress)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotification(text: String, progress: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Remote Vault Link")
            .setContentText(text)
            .setOngoing(true)
        
        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        }
        
        return builder.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EasyStorage::P2PClientWakeLock")
            wakeLock?.acquire(30 * 60 * 1000L) // 30 min max for a single file
            Log.i(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i(TAG, "WakeLock released")
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "P2P Client", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    // --- DownloadListener Implementation ---

    override fun onDownloadStarted(requestId: String, fileName: String, totalBytes: Long) {
        totalBytesMap[requestId] = totalBytes
        acquireWakeLock()
        updateNotification("Downloading $fileName...", 0)
    }

    override fun onDownloadProgress(requestId: String, downloadedBytes: Long) {
        val total = totalBytesMap[requestId] ?: -1L
        if (total > 0) {
            val progress = downloadedBytes.toFloat() / total.toFloat()
            _downloadProgress.value = _downloadProgress.value + (requestId to progress)
            
            // Throttle notification updates to prevent system lag
            if (downloadedBytes % (1024 * 512) == 0L) {
                updateNotification("Downloading...", (progress * 100).toInt())
            }
        }
    }

    override fun onDownloadCompleted(requestId: String, file: File) {
        totalBytesMap.remove(requestId)
        _downloadProgress.value = _downloadProgress.value - requestId
        updateNotification("Download complete: ${file.name}")
        
        if (client?.getActiveStreamCount() == 0) {
            scope.launch {
                delay(5000)
                if (client?.getActiveStreamCount() == 0) {
                    releaseWakeLock()
                }
            }
        }
    }

    override fun onDownloadFailed(requestId: String, error: String) {
        totalBytesMap.remove(requestId)
        _downloadProgress.value = _downloadProgress.value - requestId
        updateNotification("Download failed: $error")
        if (client?.getActiveStreamCount() == 0) {
            releaseWakeLock()
        }
    }

    override fun onDestroy() {
        stopClient()
        scope.cancel()
        super.onDestroy()
    }
}

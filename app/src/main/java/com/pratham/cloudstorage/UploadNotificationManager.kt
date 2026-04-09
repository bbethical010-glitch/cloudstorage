package com.pratham.cloudstorage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * States for the UI transfer card
 */
sealed class TransferCardState {
    object Idle : TransferCardState()
    
    data class Active(
        val fileName: String,
        val progressPercent: Int,        // 0–100
        val bytesWritten: Long,
        val totalBytes: Long,
        val speedBytesPerSecond: Long,
        val activeCount: Int,            // total simultaneous uploads
        val elapsedMs: Long
    ) : TransferCardState()
    
    data class Completing(
        val fileName: String,
        val totalBytes: Long
    ) : TransferCardState()
}

/**
 * Manages reliable Android foreground notifications for file uploads.
 * Handles individual file tracking, summary grouping, and automatic cleanup.
 */
class UploadNotificationManager(private val context: Context) {

    private val notificationManager = 
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    // Tracks active uploads for aggregate summary calculation
    private val activeUploads = ConcurrentHashMap<String, UploadProgress>()
    private val serverActionId = 1 // Matches ServerService foreground ID to keep them in same group if desired

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var completionJob: Job? = null
    
    companion object {
        const val CHANNEL_ID = "UploadProgressChannel"
        const val SUMMARY_ID = 1000

        private val _cardState = MutableStateFlow<TransferCardState>(TransferCardState.Idle)
        val cardState: StateFlow<TransferCardState> = _cardState.asStateFlow()

        fun updateCardState(newState: TransferCardState) {
            _cardState.value = newState
        }
    }

    data class UploadProgress(
        val filename: String,
        val bytesTransferred: Long,
        val totalBytes: Long,
        val startTime: Long = System.currentTimeMillis()
    ) {
        val percent: Int get() = if (totalBytes > 0) (bytesTransferred * 100 / totalBytes).toInt() else 0
        val speedBps: Long get() {
            val duration = (System.currentTimeMillis() - startTime) / 1000.0
            return if (duration > 0) (bytesTransferred / duration).toLong() else 0L
        }
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Uploads",
                NotificationManager.IMPORTANCE_LOW // Low ensures silence
            ).apply {
                description = "Shows real-time progress for file uploads to this node"
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun onUploadStarted(transferId: String, filename: String, totalBytes: Long) {
        completionJob?.cancel() // Cancel any pending transition to idle
        activeUploads[transferId] = UploadProgress(filename, 0L, totalBytes)
        
        updateCardState(TransferCardState.Active(
            fileName = filename,
            progressPercent = 0,
            bytesWritten = 0L,
            totalBytes = totalBytes,
            speedBytesPerSecond = 0L,
            activeCount = activeUploads.size,
            elapsedMs = 0L
        ))
        
        updateNotification(transferId)
        updateSummaryNotification()
    }

    fun onProgressUpdate(transferId: String, bytesWritten: Long) {
        val progress = activeUploads[transferId] ?: return
        val updated = progress.copy(bytesTransferred = bytesWritten)
        activeUploads[transferId] = updated
        
        // Update card state
        val current = _cardState.value
        if (current is TransferCardState.Active) {
            updateCardState(current.copy(
                progressPercent = updated.percent,
                bytesWritten = bytesWritten,
                speedBytesPerSecond = updated.speedBps,
                activeCount = activeUploads.size
            ))
        }
        
        updateNotification(transferId)
        updateSummaryNotification()
    }

    fun onUploadComplete(transferId: String) {
        val progress = activeUploads.remove(transferId) ?: return
        
        if (activeUploads.isEmpty()) {
            // Last upload done — show completing state then transition to idle
            updateCardState(TransferCardState.Completing(
                fileName = progress.filename,
                totalBytes = progress.totalBytes
            ))
            
            completionJob = scope.launch {
                delay(2500) // Hold completing state for 2.5 seconds
                updateCardState(TransferCardState.Idle)
            }
        } else {
            // More uploads still active — update card with next one
            val next = activeUploads.values.first()
            updateCardState(TransferCardState.Active(
                fileName = next.filename,
                progressPercent = next.percent,
                bytesWritten = next.bytesTransferred,
                totalBytes = next.totalBytes,
                speedBytesPerSecond = next.speedBps,
                activeCount = activeUploads.size,
                elapsedMs = (System.currentTimeMillis() - next.startTime)
            ))
        }
        
        // Brief completion notification that is dismissible
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Upload Complete")
            .setContentText(progress.filename)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOngoing(false)

        notificationManager.notify(transferId.hashCode(), builder.build())
        updateSummaryNotification()
    }

    fun onUploadFailed(transferId: String, error: String) {
        val progress = activeUploads.remove(transferId)
        val filename = progress?.filename ?: "File"
        
        if (activeUploads.isEmpty()) {
            updateCardState(TransferCardState.Idle)
        }
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Upload Failed")
            .setContentText("$filename: $error")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)

        notificationManager.notify(transferId.hashCode(), builder.build())
        updateSummaryNotification()
    }

    private fun updateNotification(transferId: String) {
        val progress = activeUploads[transferId] ?: return
        
        val speedText = formatSpeed(progress.speedBps)
        val progressText = "${formatBytes(progress.bytesTransferred)} / ${formatBytes(progress.totalBytes)} ($speedText)"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading ${progress.filename}")
            .setContentText(progressText)
            .setSubText("${progress.percent}%")
            .setProgress(100, progress.percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup("uploads")

        notificationManager.notify(transferId.hashCode(), builder.build())
    }

    private fun updateSummaryNotification() {
        val count = activeUploads.size
        if (count == 0) {
            notificationManager.cancel(SUMMARY_ID)
            return
        }

        val totalBytes = activeUploads.values.sumOf { it.totalBytes }
        val transferred = activeUploads.values.sumOf { it.bytesTransferred }
        val avgPercent = if (totalBytes > 0) (transferred * 100 / totalBytes).toInt() else 0

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Easy Storage: Transfers")
            .setContentText("Uploading $count file(s) - $avgPercent%")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setGroup("uploads")
            .setGroupSummary(true)
            .setOngoing(true)

        notificationManager.notify(SUMMARY_ID, builder.build())
    }

    fun cancelAll() {
        activeUploads.clear()
        notificationManager.cancel(SUMMARY_ID)
        // Note: Individual notifications might persist if not explicitly cleared, 
        // but summary cancel helps. For full cleanup:
        // notificationManager.cancelAll() // Careful: might cancel ServerService notification too
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun formatSpeed(bps: Long): String {
        return "${formatBytes(bps)}/s"
    }
}

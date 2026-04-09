package com.pratham.cloudstorage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    
    companion object {
        const val CHANNEL_ID = "UploadProgressChannel"
        const val SUMMARY_ID = 1000
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
        activeUploads[transferId] = UploadProgress(filename, 0L, totalBytes)
        updateNotification(transferId)
        updateSummaryNotification()
    }

    fun onProgressUpdate(transferId: String, bytesWritten: Long) {
        val progress = activeUploads[transferId] ?: return
        activeUploads[transferId] = progress.copy(bytesTransferred = bytesWritten)
        updateNotification(transferId)
        updateSummaryNotification()
    }

    fun onUploadComplete(transferId: String) {
        val progress = activeUploads.remove(transferId) ?: return
        
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

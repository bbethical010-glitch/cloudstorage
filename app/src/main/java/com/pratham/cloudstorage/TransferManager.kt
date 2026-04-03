package com.pratham.cloudstorage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────────────────────────────
// Transfer Manager — Native State Management for WebRTC Live Stats
//
// This singleton tracks the progress of binary file transfers (upload/download)
// occurring over the WebRTC DataChannel. It provides a throttled StateFlow for
// observing metrics in Jetpack Compose without overwhelming the UI thread.
// ──────────────────────────────────────────────────────────────────────────────

data class TransferState(
    val filename: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val speedBps: Long = 0L,
    val isComplete: Boolean = false,
    val isDownload: Boolean = false
) {
    val progress: Float get() = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f
    val percent: Int get() = (progress * 100).toInt()
}

object TransferManager {
    private val _transferState = MutableStateFlow<TransferState?>(null)
    val transferState = _transferState.asStateFlow()

    private var lastUpdateMs = 0L
    private var lastBytesTransferred = 0L
    private val UPDATE_INTERVAL_MS = 400L // Throttle UI updates to ~2.5 per second

    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Initialize a new transfer. Resets metrics and calculates baseline.
     */
    fun startTransfer(filename: String, totalBytes: Long, isDownload: Boolean) {
        lastUpdateMs = System.currentTimeMillis()
        lastBytesTransferred = 0L
        _transferState.value = TransferState(
            filename = filename,
            bytesTransferred = 0L,
            totalBytes = totalBytes,
            isDownload = isDownload
        )
    }

    /**
     * Update progress with live metrics. 
     * Uses internal throttling to ensure smooth UI performance.
     */
    fun updateProgress(bytesTransferred: Long) {
        val currentState = _transferState.value ?: return
        val now = System.currentTimeMillis()
        
        // If this is the final chunk or the interval has elapsed, update the state
        if (bytesTransferred >= currentState.totalBytes || now - lastUpdateMs >= UPDATE_INTERVAL_MS) {
            val timeElapsedSec = (now - lastUpdateMs) / 1000.0
            val bytesSinceLastTick = bytesTransferred - lastBytesTransferred
            
            val speed = if (timeElapsedSec > 0) {
                (bytesSinceLastTick / timeElapsedSec).toLong()
            } else {
                0L
            }

            _transferState.value = currentState.copy(
                bytesTransferred = bytesTransferred,
                speedBps = speed,
                isComplete = bytesTransferred >= currentState.totalBytes
            )

            lastUpdateMs = now
            lastBytesTransferred = bytesTransferred

            if (bytesTransferred >= currentState.totalBytes) {
                handleCompletion()
            }
        }
    }

    private fun handleCompletion() {
        scope.launch {
            delay(3000) // Wait 3 seconds for the user to see the "Success" state
            _transferState.value = null // Dismiss the notification card
        }
    }

    fun clear() {
        _transferState.value = null
    }
}

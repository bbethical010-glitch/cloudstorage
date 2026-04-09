package com.pratham.cloudstorage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// ──────────────────────────────────────────────────────────────────────────────
// Transfer Manager — Native State Management for WebRTC Live Stats
//
// This singleton tracks the progress of binary file transfers (upload/download)
// occurring over the WebRTC DataChannel. It now supports multiple concurrent
// transfers and provides an aggregate view for the UI.
// ──────────────────────────────────────────────────────────────────────────────

data class TransferItem(
    val id: String,
    val filename: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val isDownload: Boolean,
    val lastUpdateMs: Long
)

data class TransferState(
    val totalFiles: Int,
    val activeFiles: Int,
    val completedFiles: Int,
    val totalBytesTransferred: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val primaryFileName: String, // The name of the most recent/largest active file
    val isDownload: Boolean,
    val isComplete: Boolean
) {
    val progress: Float get() = if (totalBytes > 0) totalBytesTransferred.toFloat() / totalBytes else 0f
    val percent: Int get() = (progress * 100).toInt()
}

object TransferManager {
    private val _transferState = MutableStateFlow<TransferState?>(null)
    val transferState = _transferState.asStateFlow()

    private val activeTransfers = ConcurrentHashMap<String, TransferItem>()
    private var completedFilesCount = 0
    private var totalBytesTransferredHistorical = 0L // Bytes from finished transfers in this session
    
    private var lastGlobalUpdateMs = 0L
    private var lastGlobalBytesTransferred = 0L
    private val UPDATE_INTERVAL_MS = 400L

    private val scope = CoroutineScope(Dispatchers.Main)
    private var dismissalJob: kotlinx.coroutines.Job? = null

    /**
     * Initialize a new transfer in the queue.
     */
    fun startTransfer(id: String, filename: String, totalBytes: Long, isDownload: Boolean) {
        dismissalJob?.cancel()
        
        activeTransfers[id] = TransferItem(
            id = id,
            filename = filename,
            bytesTransferred = 0L,
            totalBytes = totalBytes,
            isDownload = isDownload,
            lastUpdateMs = System.currentTimeMillis()
        )
        
        updateAggregateState()
    }

    /**
     * Update progress for a specific transfer in the queue.
     */
    fun updateProgress(id: String, bytesTransferred: Long) {
        val item = activeTransfers[id] ?: return
        val updatedItem = item.copy(
            bytesTransferred = bytesTransferred,
            lastUpdateMs = System.currentTimeMillis()
        )
        
        if (bytesTransferred >= item.totalBytes) {
            activeTransfers.remove(id)
            completedFilesCount++
            totalBytesTransferredHistorical += item.totalBytes
            
            if (activeTransfers.isEmpty()) {
                handleAllComplete()
            }
        } else {
            activeTransfers[id] = updatedItem
        }
        
        val now = System.currentTimeMillis()
        if (now - lastGlobalUpdateMs >= UPDATE_INTERVAL_MS) {
            updateAggregateState()
        }
    }

    private fun updateAggregateState() {
        val now = System.currentTimeMillis()
        val currentActive = activeTransfers.values.toList()
        
        val totalActiveBytes = currentActive.sumOf { it.bytesTransferred }
        val grandTotalTransferred = totalBytesTransferredHistorical + totalActiveBytes
        
        val totalBytes = totalBytesTransferredHistorical + currentActive.sumOf { it.totalBytes }
        
        val timeElapsedSec = (now - lastGlobalUpdateMs) / 1000.0
        val bytesSinceLastTick = grandTotalTransferred - lastGlobalBytesTransferred
        
        val speed = if (timeElapsedSec > 0 && lastGlobalUpdateMs > 0) {
            (bytesSinceLastTick / timeElapsedSec).toLong()
        } else {
            0L
        }

        val primaryFile = currentActive.maxByOrNull { it.totalBytes }?.filename ?: ""
        val isDownload = currentActive.any { it.isDownload }

        _transferState.value = TransferState(
            totalFiles = activeTransfers.size + completedFilesCount,
            activeFiles = activeTransfers.size,
            completedFiles = completedFilesCount,
            totalBytesTransferred = grandTotalTransferred,
            totalBytes = totalBytes,
            speedBps = speed,
            primaryFileName = primaryFile,
            isDownload = isDownload,
            isComplete = activeTransfers.isEmpty() && completedFilesCount > 0
        )

        lastGlobalUpdateMs = now
        lastGlobalBytesTransferred = grandTotalTransferred
    }

    fun getActiveTransfers(): List<TransferItem> {
        return activeTransfers.values.toList()
    }

    private fun handleAllComplete() {
        updateAggregateState()
        dismissalJob = scope.launch {
            delay(5000) // Keep the "All Complete" state for 5 seconds
            clear()
        }
    }

    fun clear() {
        activeTransfers.clear()
        completedFilesCount = 0
        totalBytesTransferredHistorical = 0L
        lastGlobalBytesTransferred = 0L
        lastGlobalUpdateMs = 0L
        _transferState.value = null
    }
}

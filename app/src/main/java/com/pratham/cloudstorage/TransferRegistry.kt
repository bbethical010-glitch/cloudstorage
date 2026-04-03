package com.pratham.cloudstorage

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

object TransferRegistry {

    data class TransferStatus(
        val transferId: String,
        val fileName: String,
        val totalBytes: Long,
        val bytesWritten: Long,
        val startedAt: Long = System.currentTimeMillis(),
        val isComplete: Boolean = false,
        val isFailed: Boolean = false,
        val errorMessage: String? = null
    ) {
        val progressPercent: Int
            get() = if (totalBytes > 0)
                ((bytesWritten.toDouble() / totalBytes.toDouble()) * 100)
                    .toInt().coerceIn(0, 100)
            else 0

        val speedBytesPerSecond: Long
            get() {
                val elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000.0
                return if (elapsedSeconds > 0.1)
                    (bytesWritten / elapsedSeconds).toLong()
                else 0L
            }

        val isActive: Boolean
            get() = !isComplete && !isFailed
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _transfers = MutableStateFlow<Map<String, TransferStatus>>(emptyMap())
    val transfers: StateFlow<Map<String, TransferStatus>> = _transfers.asStateFlow()

    val activeTransfers: StateFlow<List<TransferStatus>> = _transfers
        .map { map -> map.values.filter { it.isActive }.sortedByDescending { it.startedAt } }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    fun onTransferStarted(transferId: String, fileName: String, totalBytes: Long) {
        _transfers.update { current ->
            current + (transferId to TransferStatus(
                transferId = transferId,
                fileName = fileName,
                totalBytes = totalBytes,
                bytesWritten = 0L
            ))
        }
    }

    fun onChunkWritten(transferId: String, bytesWritten: Long) {
        _transfers.update { current ->
            val existing = current[transferId] ?: return@update current
            current + (transferId to existing.copy(bytesWritten = bytesWritten))
        }
    }

    fun onTransferComplete(transferId: String) {
        _transfers.update { current ->
            val existing = current[transferId] ?: return@update current
            current + (transferId to existing.copy(
                isComplete = true,
                bytesWritten = existing.totalBytes
            ))
        }
        scope.launch {
            delay(3000)
            _transfers.update { it - transferId }
        }
    }

    fun onTransferFailed(transferId: String, error: String) {
        _transfers.update { current ->
            val existing = current[transferId] ?: return@update current
            current + (transferId to existing.copy(
                isFailed = true,
                errorMessage = error
            ))
        }
        scope.launch {
            delay(5000)
            _transfers.update { it - transferId }
        }
    }

    // Generate a deterministic transferId from filename + totalSize
    // This ensures all chunks of the same file share the same transferId
    // without requiring the web console to send an explicit ID
    fun generateTransferId(fileName: String, totalSize: Long): String {
        return "${fileName.replace(Regex("[^a-zA-Z0-9]"), "_")}_${totalSize}"
    }
}

package com.pratham.cloudstorage

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Remote Vault screen.
 * Handles signaling, WebRTC connection state, and remote file browsing via P2PClientService.
 */
class RemoteVaultViewModel(application: Application) : AndroidViewModel(application) {
    
    private var p2pService: P2PClientService? = null
    private var isBound = false

    private val _connectionStatus = MutableStateFlow(RemoteConnectionStatus.IDLE)
    val connectionStatus = _connectionStatus.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath = _currentPath.asStateFlow()

    private val _remoteFiles = MutableStateFlow<List<RemoteFile>>(emptyList())
    val remoteFiles = _remoteFiles.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _shareCode = MutableStateFlow("")
    val shareCode = _shareCode.asStateFlow()

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress = _downloadProgress.asStateFlow()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as P2PClientService.LocalBinder
            p2pService = binder.getService()
            isBound = true
            
            // Observe the client state from the service
            viewModelScope.launch {
                p2pService?.clientFlow?.collect { client ->
                    if (client != null) {
                        launch {
                            client.connectionStatus.collect { status ->
                                _connectionStatus.value = status
                                if (status == RemoteConnectionStatus.CONNECTED && _remoteFiles.value.isEmpty()) {
                                    navigateTo("")
                                }
                            }
                        }
                        launch {
                            client.remoteFiles.collect { files ->
                                _remoteFiles.value = files
                            }
                        }
                    } else {
                        _connectionStatus.value = RemoteConnectionStatus.IDLE
                        _remoteFiles.value = emptyList()
                    }
                }
            }
            
            // Observe download progress
            viewModelScope.launch {
                p2pService?.downloadProgress?.collect { progress ->
                    _downloadProgress.value = progress
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            p2pService = null
            isBound = false
        }
    }

    init {
        val intent = Intent(application, P2PClientService::class.java)
        application.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Updates the share code without connecting.
     */
    fun updateShareCode(code: String) {
        _shareCode.value = code.uppercase()
    }

    /**
     * Initiates a WebRTC connection to the remote node via signaling relay.
     */
    fun connect() {
        val code = _shareCode.value
        if (code.length < 5) {
            _error.value = "Invalid ShareCode"
            return
        }

        _error.value = null
        val relayUrl = BuildConfig.RELAY_BASE_URL
        
        if (isBound) {
            p2pService?.startClient(code, relayUrl)
        } else {
            _error.value = "P2P Service not available"
        }
    }

    /**
     * Disconnects the active WebRTC session.
     */
    fun disconnect() {
        p2pService?.stopClient()
    }

    /**
     * Navigates to a specific directory on the remote node.
     */
    fun navigateTo(path: String) {
        _currentPath.value = path
        p2pService?.clientFlow?.value?.sendRequest("/api/files?path=$path", type = "files")
    }

    private val pathToRequestId = mutableMapOf<String, String>()

    /**
     * Initiates a background download of the specified file.
     */
    fun downloadFile(file: RemoteFile) {
        if (file.isDirectory) return
        val requestId = java.util.UUID.randomUUID().toString()
        pathToRequestId[file.path] = requestId
        p2pService?.clientFlow?.value?.sendRequest(
            path = "/api/download?path=${file.path}",
            type = "download",
            requestId = requestId
        )
    }
    
    /**
     * Returns the download progress (0.0 to 1.0) for a given file path.
     */
    fun getFileProgress(path: String): Float? {
        val reqId = pathToRequestId[path] ?: return null
        return _downloadProgress.value[reqId]
    }

    /**
     * Clears the current error state.
     */
    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}

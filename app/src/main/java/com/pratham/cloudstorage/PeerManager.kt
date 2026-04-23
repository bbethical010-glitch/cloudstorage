package com.pratham.cloudstorage

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ──────────────────────────────────────────────────────────────────────────────
// Peer Manager — Multi-User Connection Lifecycle Tracker
//
// Wraps the existing WebRTCPeer (which already manages a ConcurrentHashMap of
// PeerConnections internally) and adds an observability layer:
//
//   • connectedPeers: StateFlow<List<PeerInfo>>  — for dashboard UI
//   • peerEvents:     SharedFlow<PeerEvent>       — for toast notifications
//
// This class does NOT replace WebRTCPeer's internal connection management.
// It simply listens to peer connect/disconnect callbacks and maintains a
// parallel registry of metadata (display names, timestamps) for the UI.
// ──────────────────────────────────────────────────────────────────────────────

private const val TAG = "PeerManager"

/**
 * Metadata about a connected browser peer, exposed to the UI.
 */
data class PeerInfo(
    val browserId: String,
    val connectedAt: Long,
    val displayName: String
)

/**
 * Lifecycle event emitted when a peer joins or leaves.
 */
sealed class PeerEvent {
    data class Joined(val peer: PeerInfo) : PeerEvent()
    data class Left(val browserId: String, val displayName: String) : PeerEvent()
}

class PeerManager(
    private val context: Context,
    private val rootUri: Uri,
    private val onSignal: suspend (String) -> Unit
) {
    private val peerCounter = AtomicInteger(0)
    private val peerRegistry = ConcurrentHashMap<String, PeerInfo>()

    private val _connectedPeers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val connectedPeers: StateFlow<List<PeerInfo>> = _connectedPeers.asStateFlow()

    private val _peerEvents = MutableSharedFlow<PeerEvent>(extraBufferCapacity = 32)
    val peerEvents: SharedFlow<PeerEvent> = _peerEvents.asSharedFlow()

    /**
     * The underlying WebRTCPeer instance that handles actual PeerConnection
     * and DataChannel management. Created with lifecycle callbacks that
     * feed into this PeerManager's observability layer.
     */
    val webRTCPeer: WebRTCPeer = WebRTCPeer(
        context = context,
        rootUri = rootUri,
        onSignal = onSignal,
        onPeerConnected = { browserId -> onPeerConnected(browserId) },
        onPeerDisconnected = { browserId -> onPeerDisconnected(browserId) }
    )

    /**
     * Forward a signaling message to the underlying WebRTCPeer.
     */
    fun handleSignalingMessage(json: String) {
        webRTCPeer.handleSignalingMessage(json)
    }

    /**
     * Returns the set of currently connected browser IDs.
     */
    fun getConnectedPeerIds(): Set<String> = peerRegistry.keys.toSet()

    /**
     * Returns the current peer count.
     */
    fun getPeerCount(): Int = peerRegistry.size

    private fun onPeerConnected(browserId: String) {
        val displayName = "User ${peerCounter.incrementAndGet()}"
        val info = PeerInfo(
            browserId = browserId,
            connectedAt = System.currentTimeMillis(),
            displayName = displayName
        )
        peerRegistry[browserId] = info
        publishState()
        _peerEvents.tryEmit(PeerEvent.Joined(info))
        Log.i(TAG, "Peer connected: $displayName ($browserId) — total: ${peerRegistry.size}")
    }

    private fun onPeerDisconnected(browserId: String) {
        val removed = peerRegistry.remove(browserId)
        if (removed != null) {
            publishState()
            _peerEvents.tryEmit(PeerEvent.Left(browserId, removed.displayName))
            Log.i(TAG, "Peer disconnected: ${removed.displayName} ($browserId) — total: ${peerRegistry.size}")
        }
    }

    private fun publishState() {
        _connectedPeers.value = peerRegistry.values.toList()
            .sortedBy { it.connectedAt }
    }

    /**
     * Clean up all connections and reset state.
     */
    fun destroy() {
        webRTCPeer.destroy()
        peerRegistry.clear()
        _connectedPeers.value = emptyList()
        Log.i(TAG, "PeerManager destroyed")
    }
}

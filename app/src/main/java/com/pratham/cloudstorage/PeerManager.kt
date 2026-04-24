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

// ── Peer Roles ───────────────────────────────────────────────────────────────
object PeerRole {
    const val VIEWER = "viewer"           // Read-only: GET files, download, storage, status
    const val CONTRIBUTOR = "contributor" // Read + upload + create folders
    const val MANAGER = "manager"         // Full access except server settings
    const val ADMIN = "admin"             // Full access (node owner)

    val ALL = setOf(VIEWER, CONTRIBUTOR, MANAGER, ADMIN)
    fun isValid(role: String): Boolean = role.lowercase() in ALL
}

/**
 * Metadata about a connected browser peer, exposed to the UI.
 */
data class PeerInfo(
    val browserId: String,
    val connectedAt: Long,
    val displayName: String,
    val role: String = PeerRole.VIEWER
)

/**
 * Lifecycle event emitted when a peer joins or leaves.
 */
sealed class PeerEvent {
    data class Joined(val peer: PeerInfo) : PeerEvent()
    data class Left(val browserId: String, val displayName: String) : PeerEvent()
}

// ── Activity Event ───────────────────────────────────────────────────────────

/**
 * Represents a file-system activity performed on the node.
 */
data class ActivityEvent(
    val action: String,      // "upload", "delete", "rename", "create_folder", "bulk_delete", "bulk_move"
    val fileName: String,    // Primary file/folder name affected
    val actor: String,       // Display name of the user who performed the action
    val timestamp: Long = System.currentTimeMillis(),
    val details: String = "" // Optional extra info (e.g., "renamed to X", "3 items")
)

/**
 * Thread-safe ring buffer for recent activity events.
 */
class ActivityRingBuffer(private val capacity: Int = 50) {
    private val buffer = arrayOfNulls<ActivityEvent>(capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(event: ActivityEvent) {
        buffer[head] = event
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized
    fun getAll(): List<ActivityEvent> {
        if (size == 0) return emptyList()
        val result = mutableListOf<ActivityEvent>()
        val start = if (size < capacity) 0 else head
        for (i in 0 until size) {
            val idx = (start + i) % capacity
            buffer[idx]?.let { result.add(it) }
        }
        return result.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun clear() {
        buffer.fill(null)
        head = 0
        size = 0
    }
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

    /**
     * Update a connected peer's role. Returns true if the peer was found and updated.
     */
    fun updatePeerRole(browserId: String, role: String): Boolean {
        val existing = peerRegistry[browserId] ?: return false
        if (!PeerRole.isValid(role)) return false
        val updated = existing.copy(role = role.lowercase())
        peerRegistry[browserId] = updated
        publishState()
        Log.i(TAG, "Role updated: ${updated.displayName} ($browserId) → $role")
        return true
    }

    /**
     * Get a peer's role. Returns null if peer not found.
     */
    fun getPeerRole(browserId: String): String? = peerRegistry[browserId]?.role

    /**
     * Get peer info by browserId.
     */
    fun getPeerInfo(browserId: String): PeerInfo? = peerRegistry[browserId]

    private fun onPeerConnected(browserId: String) {
        val displayName = "User ${peerCounter.incrementAndGet()}"
        val info = PeerInfo(
            browserId = browserId,
            connectedAt = System.currentTimeMillis(),
            displayName = displayName,
            role = PeerRole.VIEWER
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

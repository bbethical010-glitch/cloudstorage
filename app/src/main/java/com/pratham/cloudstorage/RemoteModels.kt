package com.pratham.cloudstorage

/**
 * Represents a file or directory on a remote node.
 */
data class RemoteFile(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val mimeType: String? = null
)

/**
 * Status of a connection to a remote node.
 */
enum class RemoteConnectionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

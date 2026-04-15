package com.pratham.cloudstorage

import android.os.Build

object NodeUrlBuilder {
    // Standard configuration
    private const val RELAY_BASE = "https://relay.easystorage.cloud"

    /**
     * Standard URL for remote access via the relay browser
     */
    fun buildRelayBrowserUrl(nodeId: String): String {
        return "$RELAY_BASE/node/$nodeId"
    }

    /**
     * Standard invite link used for sharing the node
     */
    fun buildInviteLink(nodeId: String): String {
        return "$RELAY_BASE/join/$nodeId"
    }

    /**
     * Returns a rich payload description for sharing
     */
    fun buildSharePayload(nodeId: String): String {
        return "Connect to my private cloud storage node on Easy Storage Cloud!\n\n" +
                "Node ID: $nodeId\n" +
                "Link: ${buildInviteLink(nodeId)}"
    }
}

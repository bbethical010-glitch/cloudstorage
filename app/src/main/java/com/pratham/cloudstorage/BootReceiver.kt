package com.pratham.cloudstorage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts the ServerService after device boot if the node was previously running.
 * Reads persisted state from SharedPreferences to determine whether auto-start is needed.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("cloud_storage_app", Context.MODE_PRIVATE)
        val wasRunning = prefs.getBoolean("node_was_running", false)
        val savedUri = prefs.getString("selected_uri", null)
        val shareCode = prefs.getString("share_code", null)
        val relayBaseUrl = prefs.getString("relay_base_url", null)

        if (!wasRunning || savedUri.isNullOrBlank() || shareCode.isNullOrBlank()) {
            Log.d(TAG, "Node was not running or missing config — skipping auto-start")
            return
        }

        Log.d(TAG, "Boot detected — restarting node service")

        val startIntent = Intent(context, ServerService::class.java).apply {
            action = ServerService.ACTION_START_SERVER
            putExtra(ServerService.EXTRA_URI, savedUri)
            putExtra(ServerService.EXTRA_SHARE_CODE, shareCode)
            putExtra(ServerService.EXTRA_RELAY_BASE_URL, relayBaseUrl.orEmpty())
        }

        try {
            ContextCompat.startForegroundService(context, startIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart node service on boot", e)
        }
    }
}

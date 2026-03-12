package com.pratham.cloudstorage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START_NODE -> startNode(context, intent)
            ACTION_STOP_NODE -> stopNode(context)
        }
    }

    private fun startNode(context: Context, intent: Intent) {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selectedUri = intent.getStringExtra(EXTRA_URI)
            ?: preferences.getString(PREF_SELECTED_URI, null)
        val shareCode = intent.getStringExtra(EXTRA_SHARE_CODE)
            ?: preferences.getString(PREF_SHARE_CODE, null)
            ?: ""
        val relayBaseUrl = intent.getStringExtra(EXTRA_RELAY_BASE_URL)
            ?: preferences.getString(PREF_RELAY_BASE_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.RELAY_BASE_URL

        if (selectedUri.isNullOrBlank()) {
            return
        }

        val startIntent = Intent(context, ServerService::class.java).apply {
            action = ServerService.ACTION_START_SERVER
            putExtra(ServerService.EXTRA_URI, selectedUri)
            putExtra(ServerService.EXTRA_SHARE_CODE, shareCode)
            putExtra(ServerService.EXTRA_RELAY_BASE_URL, relayBaseUrl)
        }

        ContextCompat.startForegroundService(context, startIntent)
    }

    private fun stopNode(context: Context) {
        val stopIntent = Intent(context, ServerService::class.java).apply {
            action = ServerService.ACTION_STOP_SERVER
        }
        context.startService(stopIntent)
    }

    companion object {
        const val ACTION_START_NODE = "com.pratham.cloudstorage.debug.START_NODE"
        const val ACTION_STOP_NODE = "com.pratham.cloudstorage.debug.STOP_NODE"
        const val EXTRA_URI = "uri"
        const val EXTRA_SHARE_CODE = "share_code"
        const val EXTRA_RELAY_BASE_URL = "relay_base_url"

        private const val PREFS_NAME = "cloud_storage_app"
        private const val PREF_SELECTED_URI = "selected_uri"
        private const val PREF_SHARE_CODE = "share_code"
        private const val PREF_RELAY_BASE_URL = "relay_base_url"
    }
}

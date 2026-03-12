package com.pratham.cloudstorage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat

class DebugStartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            ACTION_STOP_NODE -> stopNode()
            else -> startNode()
        }

        finish()
    }

    private fun startNode() {
        val preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

        val startIntent = Intent(this, ServerService::class.java).apply {
            action = ServerService.ACTION_START_SERVER
            putExtra(ServerService.EXTRA_URI, selectedUri)
            putExtra(ServerService.EXTRA_SHARE_CODE, shareCode)
            putExtra(ServerService.EXTRA_RELAY_BASE_URL, relayBaseUrl)
        }

        ContextCompat.startForegroundService(this, startIntent)
    }

    private fun stopNode() {
        val stopIntent = Intent(this, ServerService::class.java).apply {
            action = ServerService.ACTION_STOP_SERVER
        }
        startService(stopIntent)
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

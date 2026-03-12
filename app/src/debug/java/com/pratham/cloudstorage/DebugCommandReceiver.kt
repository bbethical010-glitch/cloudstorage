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
        // On Android 12+, BroadcastReceivers cannot call startForegroundService() directly
        // (mAllowStartForeground is false). We launch DebugStartActivity as a trampoline;
        // it runs in the foreground context and can start the service.
        val activityIntent = Intent(context, DebugStartActivity::class.java).apply {
            action = DebugStartActivity.ACTION_START_NODE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.getStringExtra(EXTRA_URI)?.let { putExtra(EXTRA_URI, it) }
            intent.getStringExtra(EXTRA_SHARE_CODE)?.let { putExtra(EXTRA_SHARE_CODE, it) }
            intent.getStringExtra(EXTRA_RELAY_BASE_URL)?.let { putExtra(EXTRA_RELAY_BASE_URL, it) }
        }
        context.startActivity(activityIntent)
    }

    private fun stopNode(context: Context) {
        context.startService(Intent(context, ServerService::class.java).apply {
            action = ServerService.ACTION_STOP_SERVER
        })
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

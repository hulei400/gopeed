package com.gopeed.gopeed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Starts [GopeedApiService] after the device finishes booting, so the Go
 * backend and its API server (port 9999, including the MCP endpoint) are
 * up without the user having to open the app first.
 *
 * The service is START_STICKY and reads its start arguments (storage dir,
 * notification title/text) from SharedPreferences, which the Dart side
 * persists on every normal start — so a boot start reuses the last-known
 * configuration. The Go backend itself reads the persisted API config
 * (enable, address, token) from BoltDB, so the API server comes up with
 * the user's settings.
 *
 * BOOT_COMPLETED is one of the broadcasts that exempts the receiver from
 * Android 12+ background-start restrictions, so starting the foreground
 * service here is allowed. The service calls startForeground immediately
 * in onStartCommand, inside the foreground-service timeout.
 *
 * Note: on ColorOS (and other OEM skins) the system may still block the
 * boot start unless the user allows "auto-start" for Gopeed in the system
 * app settings. That is a device-level gate, not something this receiver
 * can override.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            ContextCompat.startForegroundService(
                context,
                GopeedApiService.newStartIntent(context),
            )
        } catch (e: Exception) {
            Log.w(TAG, "failed to start GopeedApiService on boot", e)
        }
    }

    private companion object {
        private const val TAG = "GopeedBootReceiver"
    }
}

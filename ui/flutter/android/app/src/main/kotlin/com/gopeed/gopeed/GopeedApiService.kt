package com.gopeed.gopeed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gopeed.libgopeed.Libgopeed
import org.json.JSONObject

/**
 * Native foreground service that keeps the Gopeed Go backend (and its API
 * server) alive after the app is swiped away from Recents.
 *
 * Unlike the flutter_foreground_task service, which only anchors the
 * process, this service owns the backend: every (re)start calls
 * [Libgopeed.start], and Go's rest.Start is idempotent, so a service
 * restart after a swipe-kill brings the API server back without any Dart
 * code running.
 *
 * Start arguments (storage dir, notification title/text) are persisted in
 * SharedPreferences, because the system restarts a START_STICKY service
 * with a null intent and no Dart isolate is alive at that point.
 */
class GopeedApiService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The intent is null when the system restarts a START_STICKY service.
        intent?.let { saveStartArgs(it) }
        startForegroundCompat(buildNotification())
        // The backend does disk I/O and starts an HTTP server; keep it off
        // the main thread. startForeground above is already done, so the
        // service is safe from the foreground-service timeout.
        Thread {
            try {
                Libgopeed.start(buildStartConfig())
            } catch (e: Exception) {
                Log.e(TAG, "Libgopeed.start failed", e)
            }
        }.start()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The task was swiped away: ask the system to start us again right
        // away instead of only relying on the START_STICKY restart. This
        // can throw when the app is background-restricted; the sticky
        // restart remains as the fallback.
        try {
            startService(newStartIntent(this))
        } catch (e: Exception) {
            Log.w(TAG, "restart after onTaskRemoved failed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildStartConfig(): String {
        // In NativeMode Go reads the persisted API config (enable, address,
        // token) from BoltDB itself, so only storage settings are needed.
        val cfg = JSONObject()
        cfg.put("storage", "bolt")
        cfg.put("storageDir", prefs().getString(KEY_STORAGE_DIR, null) ?: filesDir.absolutePath)
        cfg.put("refreshInterval", 0)
        return cfg.toString()
    }

    private fun buildNotification(): Notification {
        val prefs = prefs()
        val title = prefs.getString(KEY_TITLE, null)
            ?: getString(R.string.gopeed_api_keep_alive_title)
        val text = prefs.getString(KEY_TEXT, null)
            ?: getString(R.string.gopeed_api_keep_alive_text)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setShowWhen(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.setContentIntent(pendingIntent)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gopeed_api_keep_alive_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun saveStartArgs(intent: Intent) {
        val editor = prefs().edit()
        intent.getStringExtra(EXTRA_STORAGE_DIR)?.takeIf { it.isNotEmpty() }?.let {
            editor.putString(KEY_STORAGE_DIR, it)
        }
        intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotEmpty() }?.let {
            editor.putString(KEY_TITLE, it)
        }
        intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotEmpty() }?.let {
            editor.putString(KEY_TEXT, it)
        }
        editor.apply()
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "GopeedApiService"
        private const val CHANNEL_ID = "gopeed_api_service"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "gopeed_api_keep_alive"
        private const val KEY_STORAGE_DIR = "storage_dir"
        private const val KEY_TITLE = "notif_title"
        private const val KEY_TEXT = "notif_text"

        const val EXTRA_STORAGE_DIR = "storageDir"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"

        fun newStartIntent(context: Context): Intent =
            Intent(context, GopeedApiService::class.java)

        fun start(context: Context, storageDir: String, title: String, text: String) {
            val intent = newStartIntent(context).apply {
                putExtra(EXTRA_STORAGE_DIR, storageDir)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(newStartIntent(context))
        }
    }
}

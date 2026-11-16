package com.gopeed.gopeed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.gopeed.libgopeed.Libgopeed
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native foreground service that keeps the Gopeed Go backend (and its API
 * server) alive after the app is swiped away from Recents.
 *
 * This is the app's only foreground service: every (re)start calls
 * [Libgopeed.start], and Go's rest.Start is idempotent, so a service
 * restart after a swipe-kill brings the API server back without any Dart
 * code running.
 *
 * The service runs as [ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE] on
 * Android 14+, because the dataSync type is capped at 6 hours per 24 hours
 * by the system — a cap the API server cannot live with (the first
 * keep-alive build crashed in a loop once the inherited budget was
 * exhausted). On older Android versions there is no such cap, so dataSync
 * is used there.
 *
 * Start arguments (storage dir, notification title/text) are persisted in
 * SharedPreferences, because the system restarts a START_STICKY service
 * with a null intent and no Dart isolate is alive at that point.
 *
 * The service also owns ALL Android download notifications (progress,
 * completion, failure). It polls the Go backend's REST API every 2 seconds
 * on a background thread, so notifications keep working after the app is
 * swiped away — the Dart notification controller only runs while the
 * Flutter engine is alive, which ends at the swipe. See
 * [syncDownloadNotifications].
 */
class GopeedApiService : Service() {

    // Download-notification state. One notification id per task, so a
    // completion notification replaces the progress bar instead of stacking
    // a second notification. Ids start at 10000 to never collide with the
    // keep-alive foreground notification (1001).
    private val downloadNotificationIds = mutableMapOf<String, Int>()
    private var nextDownloadNotificationId = 10000

    // Tasks whose done/error notification already fired. Their progress
    // notification was replaced by the final one, so the poller must not
    // cancel it when the task leaves the downloading set.
    private val finalizedTaskIds = mutableSetOf<String>()

    // The first successful poll only baselines already-finished tasks (marks
    // them finalized without notifying), so a service restart does not spam
    // completion alerts for downloads that finished in an earlier session.
    private var firstPoll = true

    @Volatile
    private var pollRunning = false
    private var pollThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createDownloadNotificationChannel()
        startDownloadPolling()
    }

    override fun onDestroy() {
        stopDownloadPolling()
        super.onDestroy()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: specialUse has no daily time budget, unlike
            // dataSync (6 h / 24 h). Fully qualified on purpose: an
            // unqualified constant fails compileReleaseKotlin.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Pre-14 has no dataSync time cap, so dataSync is fine here.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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

    private fun createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(DOWNLOAD_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.gopeed_downloads_channel),
            // DEFAULT: progress stays silent, completions still show in the
            // shade. The keep-alive channel is LOW; this one is separate so
            // the user can control download alerts independently.
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.gopeed_downloads_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------
    // Download notifications (native polling)
    // ------------------------------------------------------------------

    private fun startDownloadPolling() {
        if (pollRunning) return
        pollRunning = true
        val thread = Thread {
            while (pollRunning) {
                try {
                    pollDownloadTasks()
                } catch (e: Exception) {
                    // Never let a poll failure kill the service.
                    Log.w(TAG, "download poll failed", e)
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.apply { isDaemon = true; name = "gopeed-download-poll" }
        pollThread = thread
        thread.start()
    }

    private fun stopDownloadPolling() {
        pollRunning = false
        pollThread?.interrupt()
        pollThread = null
    }

    private fun pollDownloadTasks() {
        val address = apiServerAddress() ?: return
        val tasks = fetchTasks(address) ?: return
        syncDownloadNotifications(tasks)
    }

    /** Reads the API server address from the same-process Go backend. Null
     * when the server is not running (or the call fails): skip this round. */
    private fun apiServerAddress(): String? {
        return try {
            val result = JSONObject(Libgopeed.getAPIServerState())
            val state = result.optJSONObject("state") ?: return null
            if (!state.optBoolean("running", false)) return null
            state.optString("address", "").takeIf { it.isNotEmpty() } ?: DEFAULT_API_ADDRESS
        } catch (e: Exception) {
            Log.w(TAG, "getAPIServerState failed", e)
            null
        }
    }

    /** GETs the full task list from the Go REST API. Null on any failure. */
    private fun fetchTasks(address: String): List<TaskSnapshot>? {
        val url = URL("http://$address/api/v1/tasks?status=ready&status=running&status=pause&status=wait&status=error&status=done")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) return null
            return parseTasks(root.optJSONArray("data") ?: JSONArray())
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTasks(data: JSONArray): List<TaskSnapshot> {
        val out = ArrayList<TaskSnapshot>(data.length())
        for (i in 0 until data.length()) {
            try {
                out.add(TaskSnapshot.fromJson(data.getJSONObject(i)))
            } catch (e: Exception) {
                Log.w(TAG, "skip unparsable task at index $i", e)
            }
        }
        return out
    }

    /**
     * Mirrors the Dart `_syncProgressNotifications` logic, but driven by the
     * polled task list instead of task events (there is no Dart isolate to
     * receive events once the app is swiped away):
     * - running task -> progress notification (new or updated).
     * - task newly done/error -> final notification under the same id,
     *   then marked finalized so later polls never repeat it.
     * - task paused or deleted (not downloading, not finalized, not
     *   done/error) -> cancel its notification.
     */
    private fun syncDownloadNotifications(tasks: List<TaskSnapshot>) {
        val byId = tasks.associateBy { it.id }
        val downloading = tasks.filter { it.status == "running" }.associateBy { it.id }

        // Progress for actively downloading tasks.
        for (task in downloading.values) {
            showProgressNotification(task)
        }

        // Final notifications for tasks that just finished (or failed).
        // On the very first poll these are only baselined, not notified.
        for (task in tasks) {
            if (finalizedTaskIds.contains(task.id)) continue
            when (task.status) {
                "done" -> {
                    if (firstPoll) {
                        finalizedTaskIds.add(task.id)
                    } else {
                        finalizedTaskIds.add(task.id)
                        showDoneNotification(task)
                    }
                }
                "error" -> {
                    if (firstPoll) {
                        finalizedTaskIds.add(task.id)
                    } else {
                        finalizedTaskIds.add(task.id)
                        showErrorNotification(task)
                    }
                }
            }
        }
        firstPoll = false

        // Cancel progress for tasks that stopped without a final notice
        // (paused or deleted). Done/error are left alone: their final
        // notification already replaced the progress bar under the same id.
        for (id in downloadNotificationIds.keys.toList()) {
            if (downloading.containsKey(id)) continue
            if (finalizedTaskIds.contains(id)) continue
            val record = byId[id]
            if (record != null && (record.status == "done" || record.status == "error")) {
                continue
            }
            notificationManager().cancel(downloadNotificationIds[id]!!)
            downloadNotificationIds.remove(id)
        }
    }

    private fun notificationIdForTask(taskId: String): Int {
        return downloadNotificationIds.getOrPut(taskId) { nextDownloadNotificationId++ }
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun showProgressNotification(task: TaskSnapshot) {
        val percent = task.progressPercent
        val text = if (task.totalBytes != null && task.totalBytes > 0) {
            "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)} · ${formatBytes(task.speedBytes)}/s"
        } else {
            "${formatBytes(task.downloadedBytes)} · ${formatBytes(task.speedBytes)}/s"
        }
        val notification = NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(task.fileName)
            .setContentText(text)
            .setShowWhen(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent ?: 0, percent == null)
            .build()
        notificationManager().notify(notificationIdForTask(task.id), notification)
    }

    private fun showDoneNotification(task: TaskSnapshot) {
        val builder = NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.download_notification_done))
            .setContentText(task.fileName)
            .setShowWhen(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openTargetIntent(task))
        notificationManager().notify(notificationIdForTask(task.id), builder.build())
    }

    private fun showErrorNotification(task: TaskSnapshot) {
        val notification = NotificationCompat.Builder(this, DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.download_notification_error))
            .setContentText(task.fileName)
            .setShowWhen(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager().notify(notificationIdForTask(task.id), notification)
    }

    /**
     * Content intent for a completion notification. Single-file tasks open
     * the file (an APK goes to the package installer via its MIME type);
     * folder tasks just bring the app back. The file is shared through
     * FileProvider, never a file:// URI.
     */
    private fun openTargetIntent(task: TaskSnapshot): PendingIntent? {
        val notificationId = notificationIdForTask(task.id)
        if (!task.canOpenFile) {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
            return PendingIntent.getActivity(
                this,
                notificationId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val file = File(task.storagePath)
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider failed for ${task.storagePath}", e)
            return null
        }
        val ext = file.extension.lowercase(Locale.US)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            this,
            notificationId,
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Human-readable byte size, matching the Dart ByteSizeFormatter:
     * binary units (1024), at most two decimal places, trailing zeroes
     * trimmed (e.g. "12.5 MB", "1.25 MB", "2 MB", "0 B").
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        val formatted = if (unitIndex == 0) {
            value.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", value)
                .trimEnd('0')
                .trimEnd('.')
        }
        return "$formatted ${units[unitIndex]}"
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
        private const val DOWNLOAD_CHANNEL_ID = "gopeed_downloads"
        private const val DEFAULT_API_ADDRESS = "127.0.0.1:9999"
        private const val POLL_INTERVAL_MS = 2000L
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

/**
 * The fields of one Gopeed task, derived from the REST API JSON exactly
 * like `TaskRecord.fromApi` on the Dart side.
 */
private class TaskSnapshot private constructor(
    val id: String,
    val fileName: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val speedBytes: Long,
    val storagePath: String,
    val canOpenFile: Boolean,
) {
    /** 0-100, or null when the total size is unknown (indeterminate). */
    val progressPercent: Int?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0) return null
            return ((downloadedBytes * 100L / total).coerceIn(0L, 100L)).toInt()
        }

    companion object {
        fun fromJson(json: JSONObject): TaskSnapshot {
            val id = json.optString("id", "")
            val name = json.optString("name", "")
            val status = json.optString("status", "")

            val progress = json.optJSONObject("progress")
            val downloaded = progress?.optLong("downloaded", 0L) ?: 0L
            val speed = progress?.optLong("speed", 0L) ?: 0L

            val meta = json.optJSONObject("meta")
            val opts = meta?.optJSONObject("opts")
            val res = meta?.optJSONObject("res")
            val req = meta?.optJSONObject("req")
            val optsPath = opts?.optString("path", "") ?: ""
            val optsSize = opts?.optLong("size", 0L) ?: 0L
            val resSize = res?.optLong("size", 0L) ?: 0L
            val resName = res?.optString("name", "") ?: ""
            val reqUrl = req?.optString("url", "") ?: ""

            val total = if (resSize > 0) resSize else optsSize
            val fileName = when {
                name.isNotEmpty() -> name
                resName.isNotEmpty() -> resName
                else -> reqUrl
            }
            val storagePath = if (optsPath.isEmpty()) {
                fileName
            } else {
                File(optsPath, fileName).path
            }
            return TaskSnapshot(
                id = id,
                fileName = fileName,
                status = status,
                downloadedBytes = downloaded,
                totalBytes = if (total > 0) total else null,
                speedBytes = speed,
                storagePath = storagePath,
                // Same rule as the task details page: only single-file tasks
                // offer "open file"; folder tasks (e.g. multi-file torrents)
                // can only bring the app back.
                canOpenFile = resName.isEmpty(),
            )
        }
    }
}

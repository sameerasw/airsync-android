package com.sameerasw.airsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sameerasw.airsync.R
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.quickshare.OutboundQuickShareConnection
import com.sameerasw.airsync.quickshare.QuickShareDiscovery
import com.sameerasw.airsync.utils.NotificationUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.net.Socket

/**
 * Foreground service that sends one or more files to the paired Mac via Quick Share, initiated
 * from within the AirSync app (share-sheet target) rather than Android's system Quick Share.
 * Mirrors QuickShareService's structure, but drives OutboundQuickShareConnection instead.
 */
class OutboundQuickShareService : Service() {

    companion object {
        private const val TAG = "OutboundQuickShare"
        private const val NOTIFICATION_ID = 2101
        private const val CHANNEL_ID = "quick_share_send_channel"
        private const val DISCOVERY_TIMEOUT_MS = 15_000L

        const val EXTRA_URIS = "uris"

        fun start(context: Context, uris: List<Uri>) {
            val intent = Intent(context, OutboundQuickShareService::class.java).apply {
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private lateinit var dataStoreManager: DataStoreManager
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var discovery: QuickShareDiscovery? = null
    private var connection: OutboundQuickShareConnection? = null
    @Volatile
    private var connecting = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        dataStoreManager = DataStoreManager.getInstance(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Preparing to send..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Preparing to send..."))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uris: List<Uri> = intent?.getParcelableArrayListExtra(EXTRA_URIS) ?: emptyList()
        if (uris.isEmpty()) {
            Log.w(TAG, "No files to send, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val pairedDevice = dataStoreManager.getLastConnectedDevice().first()
            if (pairedDevice == null) {
                Log.w(TAG, "No paired Mac, cannot send")
                updateNotification("No paired Mac found")
                stopSelf()
                return@launch
            }

            // Must match SyncManager's fallback chain exactly — this is what the Mac stored as
            // AppState.shared.device?.name during pairing, and its Quick Share auto-accept does
            // an exact string match against it (no normalization), so any divergence here means
            // it silently falls through to a manual consent notification instead of auto-accepting.
            val ourName = dataStoreManager.getDeviceName().first().ifBlank { null }
                ?: com.sameerasw.airsync.utils.DeviceInfoUtil.getDeviceName(this@OutboundQuickShareService)
            val files = uris.mapNotNull { toPendingFile(it) }
            if (files.isEmpty()) {
                Log.w(TAG, "Could not read any of the shared files")
                updateNotification("Couldn't read the selected file(s)")
                stopSelf()
                return@launch
            }

            updateNotification("Looking for ${pairedDevice.name}...")
            findAndSend(pairedDevice.name, ourName, files)
        }

        return START_NOT_STICKY
    }

    private fun findAndSend(
        macName: String,
        ourName: String,
        files: List<OutboundQuickShareConnection.PendingFile>
    ) {
        val d = QuickShareDiscovery(this)
        discovery = d

        val timeoutJob = serviceScope.launch {
            kotlinx.coroutines.delay(DISCOVERY_TIMEOUT_MS)
            if (connection == null) {
                Log.w(TAG, "Discovery timed out")
                updateNotification("Couldn't find $macName")
                d.stop()
                kotlinx.coroutines.delay(2000)
                stopSelf()
            }
        }

        d.start(macName) { resolved ->
            if (connecting) return@start // already connecting/connected
            connecting = true
            timeoutJob.cancel()
            d.stop()

            // NSD resolve callbacks land on the main thread — do the blocking socket connect
            // off of it.
            serviceScope.launch {
                try {
                    val socket = Socket(resolved.host, resolved.port)
                    val conn = OutboundQuickShareConnection(
                        context = this@OutboundQuickShareService,
                        socket = socket,
                        ourDeviceName = ourName,
                        files = files
                    )
                    connection = conn
                    wireCallbacks(conn, macName)
                    conn.start()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to $macName", e)
                    updateNotification("Couldn't connect to $macName")
                    kotlinx.coroutines.delay(2000)
                    stopSelf()
                }
            }
        }
    }

    private fun wireCallbacks(conn: OutboundQuickShareConnection, macName: String) {
        conn.onConnectionReady = { pin ->
            Log.d(TAG, "Connection ready, PIN: $pin")
            updateNotification("Connecting to $macName (PIN $pin)...")
        }

        conn.onRejected = {
            Log.d(TAG, "Transfer rejected by $macName")
            updateNotification("$macName declined the transfer")
            serviceScope.launch {
                kotlinx.coroutines.delay(2000)
                stopSelf()
            }
        }

        var lastUpdate = 0L
        conn.onFileProgress = { fileName, percent, bytesTransferred, totalSize, transferId ->
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 800) {
                lastUpdate = now
                NotificationUtil.showFileProgress(
                    this,
                    transferId.hashCode(),
                    fileName,
                    percent,
                    transferId,
                    isSending = true
                )
            }
        }

        conn.onFileComplete = { fileName, transferId, success ->
            NotificationUtil.showFileComplete(
                this,
                transferId.hashCode(),
                fileName,
                success,
                isSending = true
            )
        }

        conn.onError = { e ->
            Log.e(TAG, "Transfer error", e)
            updateNotification("Failed to send to $macName")
        }

        conn.onFinished = {
            Log.d(TAG, "Transfer to $macName finished")
            updateNotification("Sent to $macName")
            serviceScope.launch {
                kotlinx.coroutines.delay(1500)
                stopSelf()
            }
        }
    }

    private fun toPendingFile(uri: Uri): OutboundQuickShareConnection.PendingFile? {
        return try {
            // Files we cached ourselves (see QuickShareSendActivity.copyUrisToCache) — a plain
            // java.io.File is always the accurate source of truth for these, and ContentResolver
            // has no provider registered for the "file" scheme to query metadata from anyway.
            if (uri.scheme == "file") {
                val f = java.io.File(uri.path ?: return null)
                if (!f.exists() || f.length() <= 0) {
                    Log.w(TAG, "Cached file missing or empty: $uri")
                    return null
                }
                return OutboundQuickShareConnection.PendingFile(
                    uri = uri,
                    name = f.name,
                    mimeType = contentResolver.getType(uri) ?: "application/octet-stream",
                    size = f.length()
                )
            }

            var name = "file"
            var size = 0L
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }

            // OpenableColumns.SIZE is unreliable for some providers (returns 0/null) — the Mac
            // hard-rejects any chunk that would exceed the size we declared in FileMetadata, so
            // a wrong-but-truthy 0 here breaks the very first real chunk. Fall back to the AFD's
            // stat size, which reflects the real underlying file.
            if (size <= 0) {
                size = try {
                    contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                } catch (e: Exception) {
                    -1L
                }
            }
            if (size <= 0) {
                Log.w(TAG, "Could not determine size for $uri, skipping")
                return null
            }

            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            OutboundQuickShareConnection.PendingFile(
                uri = uri,
                name = name,
                mimeType = mimeType,
                size = size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read metadata for $uri", e)
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Quick Share (Sending)",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sending via AirSync")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_laptop_24)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        discovery?.stop()
        connection?.closeConnection()
        // Clean up the cache copies QuickShareSendActivity made (see copyUrisToCache) now that
        // this transfer attempt is done, successful or not.
        File(cacheDir, "quickshare_outgoing").deleteRecursively()
        super.onDestroy()
    }
}

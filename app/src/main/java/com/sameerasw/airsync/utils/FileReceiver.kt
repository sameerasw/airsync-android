package com.sameerasw.airsync.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.sameerasw.airsync.R
import com.sameerasw.airsync.utils.transfer.FileTransferProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

object FileReceiver {
    private const val CHANNEL_ID = "airsync_file_transfer"

    private data class IncomingFileState(
        val name: String,
        val size: Int,
        val mime: String,
        val chunkSize: Int,
        val isClipboard: Boolean = false,
        var checksum: String? = null,
        var receivedBytes: Int = 0,
        var index: Int = 0,
        var pfd: android.os.ParcelFileDescriptor? = null,
        var uri: Uri? = null,
        // Speed / ETA tracking
        var lastUpdateTime: Long = System.currentTimeMillis(),
        var bytesAtLastUpdate: Int = 0,
        var smoothedSpeed: Double? = null
    )

    private val incoming = ConcurrentHashMap<String, IncomingFileState>()

    fun clearAll() {
        incoming.keys.forEach { id ->
            incoming.remove(id)?.let { state ->
                try {
                    state.pfd?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun ensureChannel(context: Context) {
        // Delegate to shared NotificationUtil
        NotificationUtil.createFileChannel(context)
    }

    fun cancelTransfer(context: Context, id: String) {
        val state = incoming.remove(id) ?: return
        Log.d("FileReceiver", "Cancelling incoming transfer $id")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Close and delete
                state.pfd?.close()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    state.uri?.let { context.contentResolver.delete(it, null, null) }
                }

                // Cancel notification
                NotificationManagerCompat.from(context).cancel(id.hashCode())

                // Send network cancel
                WebSocketUtil.sendMessage(FileTransferProtocol.buildCancel(id))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun handleInit(
        context: Context,
        id: String,
        name: String,
        size: Int,
        mime: String,
        chunkSize: Int,
        checksum: String? = null,
        isClipboard: Boolean = false
    ) {
        ensureChannel(context)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Files.getContentUri("external")
                }

                val uri = resolver.insert(collection, values)
                val pfd = uri?.let { resolver.openFileDescriptor(it, "rw") }

                if (uri != null && pfd != null) {
                    incoming[id] = IncomingFileState(
                        name = name,
                        size = size,
                        mime = mime,
                        chunkSize = chunkSize,
                        isClipboard = isClipboard,
                        checksum = checksum,
                        pfd = pfd,
                        uri = uri
                    )
                    if (!isClipboard) {
                        NotificationUtil.showFileProgress(context, id.hashCode(), name, 0, id)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun handleChunk(context: Context, id: String, index: Int, base64Chunk: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state = incoming[id] ?: return@launch
                val bytes = android.util.Base64.decode(base64Chunk, android.util.Base64.NO_WRAP)

                synchronized(state) {
                    state.pfd?.fileDescriptor?.let { fd ->
                        val channel = java.io.FileOutputStream(fd).channel
                        val offset = index.toLong() * state.chunkSize
                        channel.position(offset)
                        channel.write(java.nio.ByteBuffer.wrap(bytes))
                        state.receivedBytes += bytes.size
                        state.index = index
                    }
                }

                updateProgressNotification(context, id, state)
                // send ack for this chunk
                try {
                    val ack = FileTransferProtocol.buildChunkAck(id, index)
                    WebSocketUtil.sendMessage(ack)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun handleComplete(context: Context, id: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state = incoming[id] ?: return@launch
                // Wait for all bytes to be received (in case writes are still queued)
                val start = System.currentTimeMillis()
                val timeoutMs = 15_000L // 15s timeout
                while (state.receivedBytes < state.size && System.currentTimeMillis() - start < timeoutMs) {
                    kotlinx.coroutines.delay(100)
                }

                // Now flush and close
                state.pfd?.close()

                // Mark file as not pending (Android Q+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    state.uri?.let { context.contentResolver.update(it, values, null, null) }
                }

                // Verify checksum if available
                val resolver = context.contentResolver
                var verified = true
                state.uri?.let { uri ->
                    try {
                        resolver.openInputStream(uri)?.use { input ->
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(8192)
                            var read = input.read(buffer)
                            while (read > 0) {
                                digest.update(buffer, 0, read)
                                read = input.read(buffer)
                            }
                            val computed =
                                digest.digest().joinToString("") { String.format("%02x", it) }
                            val expected = state.checksum
                            if (expected != null && expected != computed) {
                                verified = false
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                // Notify user with an action to open the file
                val notifId = id.hashCode()
                if (!state.isClipboard) {
                    NotificationUtil.showFileComplete(
                        context,
                        notifId,
                        state.name,
                        verified,
                        isSending = false,
                        contentUri = state.uri
                    )
                }

                // If this was a clipboard sync request, copy image to clipboard
                if (state.isClipboard) {
                    state.uri?.let { uri ->
                        if (state.mime.startsWith("image/")) {
                            val copied = ClipboardUtil.copyUriToClipboard(context, uri)
                            if (copied) {
                                launch(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.image_copied_to_clipboard),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } else {
                            launch(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.file_received_from_clipboard),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }

                // Send transferVerified back to sender
                try {
                    val verifyJson =
                        FileTransferProtocol.buildTransferVerified(
                            id,
                            verified
                        )
                    WebSocketUtil.sendMessage(verifyJson)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                incoming.remove(id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private fun updateProgressNotification(context: Context, id: String, state: IncomingFileState) {
        if (state.isClipboard) return

        val now = System.currentTimeMillis()
        val timeDiff = (now - state.lastUpdateTime) / 1000.0

        if (timeDiff >= 1.0) {
            val bytesDiff = state.receivedBytes - state.bytesAtLastUpdate
            val intervalSpeed = if (timeDiff > 0) bytesDiff / timeDiff else 0.0

            val alpha = 0.4
            val lastSpeed = state.smoothedSpeed
            val newSpeed = if (lastSpeed != null) {
                alpha * intervalSpeed + (1.0 - alpha) * lastSpeed
            } else {
                intervalSpeed
            }
            state.smoothedSpeed = newSpeed

            var etaString: String? = null
            if (newSpeed > 0) {
                val remainingBytes = (state.size - state.receivedBytes).coerceAtLeast(0)
                val secondsRemaining = (remainingBytes / newSpeed).toLong()

                etaString = if (secondsRemaining < 60) {
                    "$secondsRemaining sec remaining"
                } else {
                    val mins = secondsRemaining / 60
                    "$mins min remaining"
                }
            }

            state.lastUpdateTime = now
            state.bytesAtLastUpdate = state.receivedBytes

            val percent = if (state.size > 0) (state.receivedBytes * 100 / state.size) else 0
            NotificationUtil.showFileProgress(
                context,
                id.hashCode(),
                state.name,
                percent,
                id,
                isSending = false,
                etaString = etaString
            )
        } else if (state.receivedBytes == 0) {
            // Initial
            NotificationUtil.showFileProgress(
                context,
                id.hashCode(),
                state.name,
                0,
                id,
                isSending = false,
                etaString = "Calculating..."
            )
            state.lastUpdateTime = now
            state.bytesAtLastUpdate = 0
        }
    }
}

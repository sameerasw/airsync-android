package com.sameerasw.airsync.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import com.sameerasw.airsync.utils.transfer.FileTransferProtocol
import java.util.Collections
import java.util.Set

object FileReceiver {
    private const val CHANNEL_ID = "airsync_file_transfer"

    private data class ChunkData(val index: Int, val content: String)

    private data class IncomingFileState(
        val name: String,
        val size: Long,
        val mime: String,
        var checksum: String? = null,
        var receivedBytes: Long = 0,
        var pfd: android.os.ParcelFileDescriptor? = null,
        var channel: java.nio.channels.FileChannel? = null,
        var uri: Uri? = null,
        var lastNotificationUpdate: Long = 0,
        val receivedChunks: MutableSet<Int> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap()),
        val chunkQueue: kotlinx.coroutines.channels.Channel<ChunkData> = kotlinx.coroutines.channels.Channel(kotlinx.coroutines.channels.Channel.UNLIMITED)
    )

    private val incoming = ConcurrentHashMap<String, IncomingFileState>()
    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    
    // Minimum interval between notification updates (ms)
    private const val NOTIFICATION_UPDATE_INTERVAL = 250L
    private const val CHUNK_SIZE = 64 * 1024

    fun ensureChannel(context: Context) {
        // Delegate to shared NotificationUtil
        NotificationUtil.createFileChannel(context)
    }

    fun handleInit(context: Context, id: String, name: String, size: Long, mime: String, checksum: String? = null) {
        ensureChannel(context)
        
        // Also initialize FileReceiveManager for UI tracking
        val totalChunks = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        FileReceiveManager.initFileTransfer(id, name, size, totalChunks, checksum)
        
        scope.launch {
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
                // Open with "rw" mode to allow random access writing via FileChannel
                val pfd = uri?.let { resolver.openFileDescriptor(it, "rw") }

                if (uri != null && pfd != null) {
                    val channel = java.io.FileOutputStream(pfd.fileDescriptor).channel
                    val state = IncomingFileState(
                        name = name, 
                        size = size, 
                        mime = mime, 
                        checksum = checksum, 
                        pfd = pfd, 
                        channel = channel, 
                        uri = uri
                    )
                    incoming[id] = state
                    NotificationUtil.showFileProgress(context, id.hashCode(), name, 0)
                    
                    // Start processing loop for this file
                    processRequiredChunks(context, id, state)
                } else {
                    pfd?.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun processRequiredChunks(context: Context, id: String, state: IncomingFileState) {
        scope.launch {
            for (chunk in state.chunkQueue) {
                try {
                    // Check if already cancelled
                    if (!incoming.containsKey(id)) break
                    
                    // Skip if already processed
                    if (state.receivedChunks.contains(chunk.index)) {
                        sendAck(id, chunk.index)
                        continue
                    }
                    
                    // Decode bytes
                    val bytes = android.util.Base64.decode(chunk.content, android.util.Base64.NO_WRAP)
                    
                    // Write to specific offset
                    val offset = chunk.index.toLong() * CHUNK_SIZE
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    
                    state.channel?.write(buffer, offset)
                    state.receivedChunks.add(chunk.index)
                    state.receivedBytes += bytes.size
                    
                    // Update progress
                    updateProgressNotification(context, id, state)
                    FileReceiveManager.receiveChunk(id, chunk.index, chunk.content)
                    
                    sendAck(id, chunk.index)
                    
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun sendAck(id: String, index: Int) {
        try {
            val ack = FileTransferProtocol.buildChunkAck(id, index)
            WebSocketUtil.sendMessage(ack)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun handleChunk(context: Context, id: String, index: Int, base64Chunk: String) {
        val state = incoming[id]
        if (state != null) {
            // Non-blocking send to channel
            state.chunkQueue.trySend(ChunkData(index, base64Chunk))
        }
    }

    fun handleComplete(context: Context, id: String) {
        scope.launch {
            try {
                val state = incoming[id] ?: return@launch
                
                // Close channel to stop processing loop? 
                // Wait, logic is: all chunks should be in queue or processed.
                // We wait for receivedBytes to equal size.
                
                val start = System.currentTimeMillis()
                val timeoutMs = 15_000L // 15s timeout
                while (state.receivedBytes < state.size && System.currentTimeMillis() - start < timeoutMs) {
                    kotlinx.coroutines.delay(100)
                }
                
                state.chunkQueue.close() // Close channel

                // Force flush to disk
                try {
                    state.channel?.force(true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                // Close resources
                try {
                    state.channel?.close()
                    state.pfd?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

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
                            val buffer = ByteArray(65536) // 64KB buffer
                            var read = input.read(buffer)
                            while (read > 0) {
                                digest.update(buffer, 0, read)
                                read = input.read(buffer)
                            }
                            val computed = digest.digest().joinToString("") { String.format("%02x", it) }
                            val expected = state.checksum
                            if (expected != null && expected != computed) {
                                verified = false
                                android.util.Log.e("FileReceiver", "Checksum mismatch: expected=$expected, computed=$computed")
                            } else {
                                android.util.Log.d("FileReceiver", "Checksum verified: $computed")
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        verified = false
                    }
                }

                // Notify user with an action to open the file
                val notifId = id.hashCode()
                NotificationUtil.showFileComplete(context, notifId, state.name, verified, state.uri)
                
                // Update FileReceiveManager
                FileReceiveManager.completeTransfer(id, verified)

                // Send transferVerified back to sender
                try {
                    val verifyJson = FileTransferProtocol.buildTransferVerified(id, verified)
                    WebSocketUtil.sendMessage(verifyJson)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                incoming.remove(id)
            } catch (e: Exception) {
                e.printStackTrace()
                // Mark as failed in FileReceiveManager
                FileReceiveManager.completeTransfer(id, false)
            }
        }
    }
    
    /**
     * Cancel an ongoing transfer
     */
    fun cancelTransfer(context: Context, id: String) {
        scope.launch {
            try {
                val state = incoming[id] ?: return@launch
                state.chunkQueue.close()
                
                // Close resources
                try {
                    state.channel?.close()
                    state.pfd?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                // Delete partial file
                state.uri?.let { uri ->
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                // Cancel notification
                NotificationUtil.cancelNotification(context, id.hashCode())
                
                // Update FileReceiveManager
                FileReceiveManager.cancelTransfer(id)
                
                incoming.remove(id)
                android.util.Log.d("FileReceiver", "Transfer cancelled: $id")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    /**
     * Cancel all ongoing transfers (called on disconnect)
     */
    fun cancelAllTransfers(context: Context) {
        val transferIds = incoming.keys.toList()
        android.util.Log.d("FileReceiver", "Cancelling ${transferIds.size} active transfers")
        
        transferIds.forEach { id ->
            cancelTransfer(context, id)
        }
        
        // Also cancel in FileReceiveManager
        FileReceiveManager.cancelAllTransfers(context)
    }

    private fun showProgress(context: Context, id: String) {
        NotificationUtil.showFileProgress(context, id.hashCode(), "Receiving...", 0)
    }

    private fun updateProgressNotification(context: Context, id: String, state: IncomingFileState) {
        val now = System.currentTimeMillis()
        // Throttle notification updates to avoid overwhelming the system
        if (now - state.lastNotificationUpdate < NOTIFICATION_UPDATE_INTERVAL) {
            return
        }
        state.lastNotificationUpdate = now
        
        val percent = if (state.size > 0) (state.receivedBytes * 100 / state.size).toInt() else 0
        NotificationUtil.showFileProgress(context, id.hashCode(), state.name, percent)
    }
}

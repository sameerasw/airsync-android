package com.sameerasw.airsync.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.UUID
import com.sameerasw.airsync.utils.transfer.FileTransferProtocol
import com.sameerasw.airsync.utils.transfer.FileTransferUtils
import kotlinx.coroutines.delay

object FileSender {
    private val outgoingAcks = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Int>>()
    private val transferStatus = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun clearAll() {
        outgoingAcks.clear()
        transferStatus.clear()
    }

    fun handleAck(id: String, index: Int) {
        outgoingAcks[id]?.add(index)
    }

    fun handleVerified(id: String, verified: Boolean) {
        transferStatus[id] = verified
    }

    fun cancelTransfer(id: String) {
        // Remove from acks to stop the loop
        if (outgoingAcks.remove(id) != null) {
            Log.d("FileSender", "Cancelling transfer $id")
            // Send cancel message
            WebSocketUtil.sendMessage(FileTransferProtocol.buildCancel(id))
            transferStatus.remove(id)
        }
    }

    fun sendFile(context: Context, uri: Uri, chunkSize: Int = 64 * 1024) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resolver = context.contentResolver
                val name = resolver.getFileName(uri) ?: "shared_file"
                val mime = resolver.getType(uri) ?: "application/octet-stream"

                // 1. Get size
                val size = resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1 && cursor.moveToFirst()) cursor.getInt(sizeIndex) else -1
                } ?: -1

                if (size < 0) {
                    Log.e("FileSender", "Could not determine file size for $uri")
                    return@launch
                }

                // 2. Compute Checksum (Streaming)
                Log.d("FileSender", "Computing checksum for $name...")
                val checksum = resolver.openInputStream(uri)?.use { input ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var read = input.read(buffer)
                    while (read > 0) {
                        digest.update(buffer, 0, read)
                        read = input.read(buffer)
                    }
                    digest.digest().joinToString("") { String.format("%02x", it) }
                } ?: return@launch

                val transferId = UUID.randomUUID().toString()
                outgoingAcks[transferId] = java.util.Collections.synchronizedSet(mutableSetOf<Int>())

                Log.d("FileSender", "Starting transfer id=$transferId name=$name size=$size")

                // 3. Init
                WebSocketUtil.sendMessage(FileTransferProtocol.buildInit(transferId, name, size, mime, chunkSize, checksum))

                // 4. Send Chunks with Sliding Window
                val windowSize = 8
                val totalChunks = if (size == 0) 1 else (size + chunkSize - 1) / chunkSize
                
                // Buffer of sent chunks in the current window: index -> (base64, lastSentTime, attempts)
                data class SentChunk(val base64: String, var lastSent: Long, var attempts: Int)
                val sentBuffer = java.util.concurrent.ConcurrentHashMap<Int, SentChunk>()
                
                var nextIndexToSend = 0
                val ackWaitMs = 2000L
                val maxRetries = 5

                resolver.openInputStream(uri)?.use { input ->
                    while (true) {
                        val acks = outgoingAcks[transferId] ?: break
                        
                        // find baseIndex = smallest unacked index
                        var baseIndex = 0
                        while (acks.contains(baseIndex)) {
                            sentBuffer.remove(baseIndex)
                            baseIndex++
                        }
                        
                        if (baseIndex >= totalChunks) break
                        
                        // Fill window
                        while (nextIndexToSend < totalChunks && (nextIndexToSend - baseIndex) < windowSize) {
                            val chunk = ByteArray(chunkSize)
                            val read = input.read(chunk)
                            if (read > 0) {
                                val actualChunk = if (read < chunkSize) chunk.copyOf(read) else chunk
                                val base64 = FileTransferUtils.base64NoWrap(actualChunk)
                                WebSocketUtil.sendMessage(FileTransferProtocol.buildChunk(transferId, nextIndexToSend, base64))
                                sentBuffer[nextIndexToSend] = SentChunk(base64, System.currentTimeMillis(), 1)
                                nextIndexToSend++
                            } else if (nextIndexToSend < totalChunks) {
                                break
                            }
                        }
                        
                        // Retransmit logic
                        val now = System.currentTimeMillis()
                        var failed = false
                        for ((idx, sent) in sentBuffer) {
                            if (acks.contains(idx)) continue
                            if (now - sent.lastSent > ackWaitMs) {
                                if (sent.attempts >= maxRetries) {
                                    Log.e("FileSender", "Failed to send chunk $idx after $maxRetries attempts")
                                    failed = true
                                    break
                                }
                                Log.d("FileSender", "Retransmitting chunk $idx (attempt ${sent.attempts + 1})")
                                WebSocketUtil.sendMessage(FileTransferProtocol.buildChunk(transferId, idx, sent.base64))
                                sent.lastSent = now
                                sent.attempts++
                            }
                        }
                        
                        if (failed) break
                        delay(10)
                    }
                }
                
                // 5. Complete
                Log.d("FileSender", "Transfer $transferId completed")
                WebSocketUtil.sendMessage(FileTransferProtocol.buildComplete(transferId, name, size, checksum))
                outgoingAcks.remove(transferId)

            } catch (e: Exception) {
                Log.e("FileSender", "Error sending file: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}

// Extension helper to get filename
fun android.content.ContentResolver.getFileName(uri: Uri): String? {
    var name: String? = null
    val returnCursor = this.query(uri, null, null, null, null)
    returnCursor?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIndex)
        }
    }
    return name
}

// get mime type
fun android.content.ContentResolver.getType(uri: Uri): String? = this.getType(uri)

package com.sameerasw.airsync.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.util.UUID
import com.sameerasw.airsync.utils.transfer.FileTransferProtocol
import com.sameerasw.airsync.utils.transfer.FileTransferUtils

object FileSender {
    private const val LARGE_FILE_THRESHOLD = 10 * 1024 * 1024 // 10MB
    
    fun sendFile(context: Context, uri: Uri, chunkSize: Int = 64 * 1024) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val resolver = context.contentResolver
                val name = resolver.getFileName(uri) ?: "shared_file"
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                
                // Get file size first
                val fileSize = resolver.openInputStream(uri)?.use { it.available() } ?: 0
                
                if (fileSize > LARGE_FILE_THRESHOLD) {
                    // Use streaming approach for large files
                    sendLargeFile(context, uri, name, mime, chunkSize)
                } else {
                    // Use in-memory approach for small files
                    sendSmallFile(context, uri, name, mime, chunkSize)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private suspend fun sendSmallFile(context: Context, uri: Uri, name: String, mime: String, chunkSize: Int) {
        val resolver = context.contentResolver
        val input: InputStream? = resolver.openInputStream(uri)
        if (input == null) return

        val bytes = input.readBytes().also { input.close() }
        val checksum = FileTransferUtils.sha256Hex(bytes)

        val transferId = UUID.randomUUID().toString()

        // Init
        val initJson = FileTransferProtocol.buildInit(
            id = transferId,
            name = name,
            size = bytes.size,
            mime = mime,
            checksum = checksum
        )
        WebSocketUtil.sendMessage(initJson)

        // Chunks with rate limiting to prevent network overload
        var offset = 0
        var index = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val base64 = FileTransferUtils.base64NoWrap(chunk)
            val chunkJson = FileTransferProtocol.buildChunk(transferId, index, base64)
            WebSocketUtil.sendMessage(chunkJson)

            index += 1
            offset = end
            
            // Small delay every 10 chunks to prevent overwhelming the network
            if (index % 10 == 0) {
                kotlinx.coroutines.delay(10)
            }
        }

        // Complete
        val completeJson = FileTransferProtocol.buildComplete(
            id = transferId,
            name = name,
            size = bytes.size,
            checksum = checksum
        )
        WebSocketUtil.sendMessage(completeJson)
    }
    
    private suspend fun sendLargeFile(context: Context, uri: Uri, name: String, mime: String, chunkSize: Int) {
        val resolver = context.contentResolver
        val transferId = UUID.randomUUID().toString()
        
        // Calculate checksum using streaming (memory-efficient)
        val checksum = resolver.openInputStream(uri)?.use { stream ->
            FileTransferUtils.sha256HexFromStream(stream)
        } ?: return
        
        // Get file size
        val fileSize = resolver.openInputStream(uri)?.use { stream ->
            var size = 0L
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                size += bytesRead
            }
            size.toInt()
        } ?: return
        
        // Init
        val initJson = FileTransferProtocol.buildInit(
            id = transferId,
            name = name,
            size = fileSize,
            mime = mime,
            checksum = checksum
        )
        WebSocketUtil.sendMessage(initJson)
        
        // Stream chunks
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(chunkSize)
            var bytesRead: Int
            var index = 0
            
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                val chunk = if (bytesRead == chunkSize) buffer else buffer.copyOf(bytesRead)
                val base64 = FileTransferUtils.base64NoWrap(chunk)
                val chunkJson = FileTransferProtocol.buildChunk(transferId, index, base64)
                WebSocketUtil.sendMessage(chunkJson)
                
                index += 1
                
                // Small delay every 10 chunks to prevent overwhelming the network
                if (index % 10 == 0) {
                    kotlinx.coroutines.delay(10)
                }
            }
        }
        
        // Complete
        val completeJson = FileTransferProtocol.buildComplete(
            id = transferId,
            name = name,
            size = fileSize,
            checksum = checksum
        )
        WebSocketUtil.sendMessage(completeJson)
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

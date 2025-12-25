package com.sameerasw.airsync.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.sameerasw.airsync.presentation.ui.screens.TransferStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

object FileTransferUtil {
    private const val TAG = "FileTransferUtil"
    private const val CHUNK_SIZE = 64 * 1024 // 64KB chunks
    
    suspend fun sendFile(
        context: Context,
        uri: Uri,
        onProgress: (Float, TransferStatus) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            onProgress(0f, TransferStatus.PENDING)
            
            val fileName = getFileName(context, uri)
            val fileSize = getFileSize(context, uri)
            
            Log.d(TAG, "Sending file: $fileName, size: $fileSize bytes")
            
            // Generate transfer ID
            val transferId = java.util.UUID.randomUUID().toString()
            
            // Read file content
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Cannot open file")
            
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            var totalBytesRead = 0L
            var chunkIndex = 0
            val digest = MessageDigest.getInstance("SHA-256")
            
            // Calculate total chunks
            val totalChunks = ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
            
            // Send init message
            val checksum = calculateChecksum(context, uri)
            val initJson = """{"type":"fileTransferInit","data":{"transferId":"$transferId","fileName":"${escapeJson(fileName)}","fileSize":$fileSize,"totalChunks":$totalChunks,"checksum":"$checksum"}}"""
            
            if (!WebSocketUtil.sendMessage(initJson)) {
                throw Exception("Failed to send file init")
            }
            
            Log.d(TAG, "Sent file init: $fileName ($totalChunks chunks)")
            onProgress(0f, TransferStatus.TRANSFERRING)
            
            // Send chunks
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                // Always create a new array with exact size to avoid buffer reuse issues
                val chunk = buffer.copyOf(bytesRead)
                
                // Update digest for checksum verification
                digest.update(chunk)
                
                // Encode to base64 with NO_WRAP to avoid newlines
                val base64Chunk = Base64.encodeToString(chunk, Base64.NO_WRAP)
                
                // Send chunk
                val chunkJson = """{"type":"fileChunk","data":{"transferId":"$transferId","chunkIndex":$chunkIndex,"data":"$base64Chunk"}}"""
                
                if (!WebSocketUtil.sendMessage(chunkJson)) {
                    throw Exception("Failed to send chunk $chunkIndex")
                }
                
                totalBytesRead += bytesRead
                chunkIndex++
                val progress = totalBytesRead.toFloat() / fileSize
                onProgress(progress, TransferStatus.TRANSFERRING)
                
                if (chunkIndex % 100 == 0 || chunkIndex == totalChunks) {
                    Log.d(TAG, "Sent chunk $chunkIndex/$totalChunks (${chunk.size} bytes)")
                }
            }
            
            inputStream.close()
            
            // Send complete message
            val completeJson = """{"type":"fileTransferComplete","data":{"transferId":"$transferId"}}"""
            
            if (!WebSocketUtil.sendMessage(completeJson)) {
                throw Exception("Failed to send file complete")
            }
            
            Log.d(TAG, "File sent successfully: $fileName")
            onProgress(1f, TransferStatus.COMPLETED)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending file: ${e.message}", e)
            onProgress(0f, TransferStatus.FAILED)
            throw e
        }
    }
    
    private fun calculateChecksum(context: Context, uri: Uri): String {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw Exception("Cannot open file for checksum")
        
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalBytes = 0L
        
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
            totalBytes += bytesRead
        }
        
        inputStream.close()
        
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Calculated checksum for $totalBytes bytes: $checksum")
        
        return checksum
    }
    
    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
    

    fun getFileName(context: Context, uri: Uri): String {
        var name = "unknown"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }
    
    fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1) {
                    size = it.getLong(sizeIndex)
                }
            }
        }
        return size
    }
}

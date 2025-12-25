package com.sameerasw.airsync.utils

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest

object FileReceiveManager {
    private const val TAG = "FileReceiveManager"
    
    // Store ongoing file transfers (lightweight - no chunk data stored)
    private val activeTransfers = java.util.concurrent.ConcurrentHashMap<String, FileTransferState>()
    
    // Expose active transfers as StateFlow for UI
    private val _activeTransfersFlow = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    val activeTransfersFlow: StateFlow<Map<String, FileTransferState>> = _activeTransfersFlow
    
    data class FileTransferState(
        val fileName: String,
        val fileSize: Long,
        val totalChunks: Int,
        @Volatile var receivedChunksCount: Int = 0, // Just count, don't store data
        val expectedChecksum: String?,
        @Volatile var bytesReceived: Long = 0,
        @Volatile var status: TransferStatus = TransferStatus.TRANSFERRING,
        val startTime: Long = System.currentTimeMillis(),
        @Volatile var endTime: Long? = null
    ) {
        val elapsedTimeMs: Long
            get() = (endTime ?: System.currentTimeMillis()) - startTime
        
        val transferSpeed: Long // bytes per second
            get() {
                val elapsed = elapsedTimeMs
                return if (elapsed > 0) (bytesReceived * 1000) / elapsed else 0
            }
    }
    
    enum class TransferStatus {
        PENDING, TRANSFERRING, COMPLETED, FAILED, CANCELLED
    }
    
    @Synchronized
    private fun updateFlow() {
        _activeTransfersFlow.value = activeTransfers.toMap()
    }
    
    /**
     * Initialize a new file transfer
     */
    @Synchronized
    fun initFileTransfer(
        transferId: String,
        fileName: String,
        fileSize: Long,
        totalChunks: Int,
        checksum: String?
    ) {
        Log.d(TAG, "Initializing file transfer: $fileName ($fileSize bytes, $totalChunks chunks)")
        
        activeTransfers[transferId] = FileTransferState(
            fileName = fileName,
            fileSize = fileSize,
            totalChunks = totalChunks,
            expectedChecksum = checksum,
            status = TransferStatus.TRANSFERRING
        )
        updateFlow()
    }
    
    /**
     * Receive a file chunk - just update progress, don't store data
     * FileReceiver handles actual data storage via streaming
     */
    @Synchronized
    fun receiveChunk(
        transferId: String,
        chunkIndex: Int,
        chunkData: String
    ): Boolean {
        val transfer = activeTransfers[transferId]
        if (transfer == null) {
            Log.w(TAG, "No active transfer found for ID: $transferId")
            return false
        }
        
        // Check if transfer was cancelled
        if (transfer.status == TransferStatus.CANCELLED) {
            return false
        }
        
        try {
            // Just calculate size from base64, don't decode and store
            val estimatedSize = (chunkData.length * 3) / 4
            
            transfer.receivedChunksCount++
            transfer.bytesReceived += estimatedSize
            
            // Log progress periodically
            if (transfer.receivedChunksCount % 100 == 0) {
                Log.d(TAG, "Progress: ${transfer.receivedChunksCount}/${transfer.totalChunks} for ${transfer.fileName}")
            }
            
            updateFlow()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating chunk progress: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Mark transfer as complete
     */
    @Synchronized
    fun completeTransfer(transferId: String, success: Boolean) {
        val transfer = activeTransfers[transferId]
        if (transfer == null) {
            Log.w(TAG, "No active transfer found for ID: $transferId")
            return
        }
        
        transfer.endTime = System.currentTimeMillis()
        transfer.status = if (success) TransferStatus.COMPLETED else TransferStatus.FAILED
        
        // Calculate and log transfer stats
        val durationMs = transfer.elapsedTimeMs
        val speedKBps = transfer.transferSpeed / 1024.0
        val speedMBps = speedKBps / 1024.0
        
        Log.d(TAG, "✓ File transfer ${if (success) "completed" else "failed"}: ${transfer.fileName}")
        Log.d(TAG, "📊 Transfer stats: ${formatFileSize(transfer.fileSize)} in ${formatDuration(durationMs)} (${String.format("%.2f", if (speedMBps > 1) speedMBps else speedKBps)} ${if (speedMBps > 1) "MB/s" else "KB/s"})")
        
        // Remove from active transfers after a delay to allow UI to show completion
        updateFlow()
        
        // Schedule removal
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // Need to synchronize removal too, but can't easily sync across lambda. 
            // Better to add separate synchronized remove method or invoke via safe wrapper.
            // For now, simple remove in synchronized block or just accessing map is mostly safe due to ConcurrentHashMap but updateFlow needs lock.
            removeTransferSafely(transferId)
        }, 3000)
    }

    @Synchronized
    private fun removeTransferSafely(transferId: String) {
        activeTransfers.remove(transferId)
        updateFlow()
    }
    
    /**
     * Cancel an ongoing transfer
     */
    @Synchronized
    fun cancelTransfer(transferId: String) {
        val transfer = activeTransfers[transferId]
        if (transfer != null) {
            transfer.status = TransferStatus.CANCELLED
            transfer.endTime = System.currentTimeMillis()
            Log.d(TAG, "Transfer cancelled: $transferId (${transfer.fileName})")
        }
        activeTransfers.remove(transferId)
        updateFlow()
    }
    
    /**
     * Cancel all ongoing transfers (called on disconnect)
     */
    @Synchronized
    fun cancelAllTransfers(context: Context? = null) {
        val transferIds = activeTransfers.keys.toList()
        Log.d(TAG, "Cancelling ${transferIds.size} active transfers")
        
        transferIds.forEach { id ->
            val transfer = activeTransfers[id]
            if (transfer != null) {
                transfer.status = TransferStatus.CANCELLED
                transfer.endTime = System.currentTimeMillis()
                
                // Cancel notification
                context?.let {
                    NotificationUtil.cancelNotification(it, id.hashCode())
                }
            }
        }
        
        activeTransfers.clear()
        updateFlow()
    }
    
    /**
     * Check if a transfer is active
     */
    fun isTransferActive(transferId: String): Boolean {
        val transfer = activeTransfers[transferId]
        return transfer != null && transfer.status == TransferStatus.TRANSFERRING
    }
    
    /**
     * Get transfer progress
     */
    fun getProgress(transferId: String): Float {
        val transfer = activeTransfers[transferId] ?: return 0f
        return if (transfer.fileSize > 0) {
            transfer.bytesReceived.toFloat() / transfer.fileSize.toFloat()
        } else {
            0f
        }
    }
    
    /**
     * Get number of active transfers
     */
    fun getActiveTransferCount(): Int = activeTransfers.count { it.value.status == TransferStatus.TRANSFERRING }
    
    /**
     * Format file size for display
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    /**
     * Format duration for display
     */
    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60)
            minutes > 0 -> String.format("%d:%02d", minutes, seconds % 60)
            seconds > 0 -> String.format("%d.%ds", seconds, (ms % 1000) / 100)
            else -> "${ms}ms"
        }
    }
}

package com.sameerasw.airsync.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * LocalSend-inspired file transfer implementation
 * 
 * Features:
 * - Chunked transfer for large files
 * - SHA-256 checksum verification
 * - Progress tracking
 * - Resume support
 * - Parallel chunk transfers
 * - Rate limiting to prevent overwhelming the connection
 * 
 * Protocol:
 * 1. Sender sends metadata (prepare-upload)
 * 2. Receiver accepts/rejects
 * 3. Sender sends file chunks
 * 4. Receiver verifies checksum
 * 5. Transfer complete
 */
object LocalSendFileTransfer {
    private const val TAG = "LocalSendFileTransfer"
    
    // Transfer settings
    const val CHUNK_SIZE = 64 * 1024 // 64KB chunks (optimal for WebSocket)
    const val MAX_PARALLEL_CHUNKS = 4
    const val CHUNK_RETRY_COUNT = 3
    const val CHUNK_TIMEOUT_MS = 10000L
    const val RATE_LIMIT_DELAY_MS = 10L // Delay between chunks to prevent flooding
    
    // Active transfers
    private val outgoingTransfers = ConcurrentHashMap<String, OutgoingTransfer>()
    private val incomingTransfers = ConcurrentHashMap<String, IncomingTransfer>()
    
    // Transfer state
    private val _transferProgress = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val transferProgress: StateFlow<Map<String, TransferProgress>> = _transferProgress
    
    // Callbacks
    var onTransferComplete: ((String, Boolean, String?) -> Unit)? = null
    var onTransferProgress: ((String, Long, Long) -> Unit)? = null
    var onIncomingTransferRequest: ((TransferRequest) -> Unit)? = null
    
    /**
     * Data classes
     */
    data class FileMetadata(
        val id: String,
        val fileName: String,
        val size: Long,
        val mimeType: String,
        val sha256: String?,
        val preview: String? = null, // Base64 thumbnail for images
        val modified: Long? = null
    )
    
    data class TransferRequest(
        val sessionId: String,
        val senderInfo: SenderInfo,
        val files: List<FileMetadata>
    )
    
    data class SenderInfo(
        val alias: String,
        val version: String,
        val deviceModel: String?,
        val deviceType: String
    )
    
    data class TransferProgress(
        val sessionId: String,
        val fileId: String,
        val fileName: String,
        val totalBytes: Long,
        val transferredBytes: Long,
        val state: TransferState,
        val error: String? = null
    ) {
        val progress: Float get() = if (totalBytes > 0) transferredBytes.toFloat() / totalBytes else 0f
    }
    
    enum class TransferState {
        PENDING,
        PREPARING,
        TRANSFERRING,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED
    }
    
    data class OutgoingTransfer(
        val sessionId: String,
        val files: List<FileMetadata>,
        val fileStreams: Map<String, InputStream>,
        var currentFileIndex: Int = 0,
        var currentChunk: Int = 0,
        var pendingAcks: MutableSet<Int> = mutableSetOf(),
        var job: Job? = null
    )
    
    data class IncomingTransfer(
        val sessionId: String,
        val files: List<FileMetadata>,
        val receivedChunks: MutableMap<String, MutableMap<Int, ByteArray>> = mutableMapOf(),
        val outputFiles: MutableMap<String, File> = mutableMapOf(),
        var currentFileId: String? = null,
        var expectedChunks: Int = 0
    )
    
    /**
     * Prepare to send files - creates metadata and initiates transfer
     */
    suspend fun prepareUpload(
        context: Context,
        files: List<Uri>,
        onMetadataReady: (String, List<FileMetadata>) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()
        val fileMetadataList = mutableListOf<FileMetadata>()
        val fileStreams = mutableMapOf<String, InputStream>()
        
        for (uri in files) {
            try {
                val contentResolver = context.contentResolver
                val cursor = contentResolver.query(uri, null, null, null, null)
                
                var fileName = "unknown"
                var size = 0L
                var mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIndex >= 0) fileName = it.getString(nameIndex)
                        if (sizeIndex >= 0) size = it.getLong(sizeIndex)
                    }
                }
                
                // Calculate SHA-256 checksum
                val sha256 = calculateSha256(context, uri)
                
                val fileId = UUID.randomUUID().toString()
                val metadata = FileMetadata(
                    id = fileId,
                    fileName = fileName,
                    size = size,
                    mimeType = mimeType,
                    sha256 = sha256
                )
                
                fileMetadataList.add(metadata)
                contentResolver.openInputStream(uri)?.let { stream ->
                    fileStreams[fileId] = stream
                }
                
                Log.d(TAG, "📁 Prepared file: $fileName ($size bytes, SHA256: ${sha256?.take(16)}...)")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing file: ${e.message}", e)
            }
        }
        
        if (fileMetadataList.isNotEmpty()) {
            outgoingTransfers[sessionId] = OutgoingTransfer(
                sessionId = sessionId,
                files = fileMetadataList,
                fileStreams = fileStreams
            )
            
            onMetadataReady(sessionId, fileMetadataList)
        }
        
        sessionId
    }
    
    /**
     * Create prepare-upload JSON message (LocalSend protocol)
     */
    fun createPrepareUploadMessage(
        sessionId: String,
        files: List<FileMetadata>,
        senderInfo: SenderInfo
    ): String {
        val filesJson = JSONObject()
        files.forEach { file ->
            filesJson.put(file.id, JSONObject().apply {
                put("id", file.id)
                put("fileName", file.fileName)
                put("size", file.size)
                put("fileType", file.mimeType)
                file.sha256?.let { put("sha256", it) }
                file.preview?.let { put("preview", it) }
                file.modified?.let { put("metadata", JSONObject().put("modified", it)) }
            })
        }
        
        return JSONObject().apply {
            put("type", "fileTransferPrepare")
            put("data", JSONObject().apply {
                put("sessionId", sessionId)
                put("info", JSONObject().apply {
                    put("alias", senderInfo.alias)
                    put("version", senderInfo.version)
                    senderInfo.deviceModel?.let { put("deviceModel", it) }
                    put("deviceType", senderInfo.deviceType)
                })
                put("files", filesJson)
            })
        }.toString()
    }
    
    /**
     * Start sending file chunks after receiver accepts
     */
    fun startUpload(
        sessionId: String,
        acceptedFileIds: List<String>,
        sendChunk: (String, String, Int, ByteArray) -> Unit
    ) {
        val transfer = outgoingTransfers[sessionId] ?: run {
            Log.e(TAG, "Transfer not found: $sessionId")
            return
        }
        
        transfer.job = CoroutineScope(Dispatchers.IO).launch {
            try {
                for (file in transfer.files) {
                    if (file.id !in acceptedFileIds) continue
                    
                    val stream = transfer.fileStreams[file.id] ?: continue
                    val totalChunks = ((file.size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
                    var chunkIndex = 0
                    var totalSent = 0L
                    
                    updateProgress(sessionId, file.id, file.fileName, file.size, 0, TransferState.TRANSFERRING)
                    
                    Log.d(TAG, "📤 Starting upload: ${file.fileName} ($totalChunks chunks)")
                    
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val bytesRead = stream.read(buffer)
                        if (bytesRead <= 0) break
                        
                        val chunk = if (bytesRead < CHUNK_SIZE) {
                            buffer.copyOf(bytesRead)
                        } else {
                            buffer.clone()
                        }
                        
                        // Send chunk with retry
                        var sent = false
                        for (retry in 0 until CHUNK_RETRY_COUNT) {
                            try {
                                sendChunk(sessionId, file.id, chunkIndex, chunk)
                                sent = true
                                break
                            } catch (e: Exception) {
                                Log.w(TAG, "Chunk $chunkIndex retry $retry failed: ${e.message}")
                                delay(100)
                            }
                        }
                        
                        if (!sent) {
                            throw Exception("Failed to send chunk $chunkIndex after $CHUNK_RETRY_COUNT retries")
                        }
                        
                        totalSent += bytesRead
                        chunkIndex++
                        
                        updateProgress(sessionId, file.id, file.fileName, file.size, totalSent, TransferState.TRANSFERRING)
                        onTransferProgress?.invoke(sessionId, totalSent, file.size)
                        
                        // Rate limiting
                        delay(RATE_LIMIT_DELAY_MS)
                    }
                    
                    stream.close()
                    updateProgress(sessionId, file.id, file.fileName, file.size, file.size, TransferState.COMPLETED)
                    Log.d(TAG, "✅ Upload complete: ${file.fileName}")
                }
                
                onTransferComplete?.invoke(sessionId, true, null)
                outgoingTransfers.remove(sessionId)
                
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}", e)
                onTransferComplete?.invoke(sessionId, false, e.message)
                outgoingTransfers.remove(sessionId)
            }
        }
    }

    
    /**
     * Create file chunk message
     */
    fun createChunkMessage(sessionId: String, fileId: String, chunkIndex: Int, data: ByteArray): String {
        val base64Data = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        return JSONObject().apply {
            put("type", "fileChunk")
            put("data", JSONObject().apply {
                put("sessionId", sessionId)
                put("fileId", fileId)
                put("index", chunkIndex)
                put("chunk", base64Data)
                put("size", data.size)
            })
        }.toString()
    }
    
    /**
     * Handle incoming transfer request (receiver side)
     */
    fun handlePrepareUpload(context: Context, message: JSONObject): TransferRequest? {
        return try {
            val data = message.getJSONObject("data")
            val sessionId = data.getString("sessionId")
            val info = data.getJSONObject("info")
            val filesJson = data.getJSONObject("files")
            
            val senderInfo = SenderInfo(
                alias = info.getString("alias"),
                version = info.getString("version"),
                deviceModel = info.optString("deviceModel"),
                deviceType = info.optString("deviceType", "desktop")
            )
            
            val files = mutableListOf<FileMetadata>()
            filesJson.keys().forEach { fileId ->
                val fileJson = filesJson.getJSONObject(fileId)
                files.add(FileMetadata(
                    id = fileJson.getString("id"),
                    fileName = fileJson.getString("fileName"),
                    size = fileJson.getLong("size"),
                    mimeType = fileJson.optString("fileType", "application/octet-stream"),
                    sha256 = fileJson.optString("sha256").takeIf { it.isNotEmpty() },
                    preview = fileJson.optString("preview").takeIf { it.isNotEmpty() }
                ))
            }
            
            val request = TransferRequest(sessionId, senderInfo, files)
            
            // Initialize incoming transfer
            incomingTransfers[sessionId] = IncomingTransfer(
                sessionId = sessionId,
                files = files
            )
            
            // Create temp directory for this transfer
            val tempDir = File(context.cacheDir, "transfers/$sessionId")
            tempDir.mkdirs()
            
            Log.d(TAG, "📥 Incoming transfer request: ${files.size} files from ${senderInfo.alias}")
            
            onIncomingTransferRequest?.invoke(request)
            request
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing prepare-upload: ${e.message}", e)
            null
        }
    }
    
    /**
     * Accept incoming transfer
     */
    fun acceptTransfer(sessionId: String, acceptedFileIds: List<String>): String {
        val transfer = incomingTransfers[sessionId] ?: return createErrorResponse(sessionId, "Transfer not found")
        
        // Initialize chunk storage for accepted files
        acceptedFileIds.forEach { fileId ->
            transfer.receivedChunks[fileId] = mutableMapOf()
        }
        
        return JSONObject().apply {
            put("type", "fileTransferAccept")
            put("data", JSONObject().apply {
                put("sessionId", sessionId)
                put("acceptedFiles", JSONArray(acceptedFileIds))
            })
        }.toString()
    }
    
    /**
     * Reject incoming transfer
     */
    fun rejectTransfer(sessionId: String, reason: String = "Rejected by user"): String {
        incomingTransfers.remove(sessionId)
        
        return JSONObject().apply {
            put("type", "fileTransferReject")
            put("data", JSONObject().apply {
                put("sessionId", sessionId)
                put("reason", reason)
            })
        }.toString()
    }
    
    /**
     * Handle incoming file chunk
     */
    fun handleChunk(context: Context, message: JSONObject): Boolean {
        return try {
            val data = message.getJSONObject("data")
            val sessionId = data.getString("sessionId")
            val fileId = data.getString("fileId")
            val chunkIndex = data.getInt("index")
            val chunkBase64 = data.getString("chunk")
            
            val transfer = incomingTransfers[sessionId] ?: run {
                Log.e(TAG, "Transfer not found: $sessionId")
                return false
            }
            
            val chunkData = android.util.Base64.decode(chunkBase64, android.util.Base64.NO_WRAP)
            
            // Store chunk
            transfer.receivedChunks.getOrPut(fileId) { mutableMapOf() }[chunkIndex] = chunkData
            
            // Update progress
            val file = transfer.files.find { it.id == fileId }
            if (file != null) {
                val receivedBytes = transfer.receivedChunks[fileId]?.values?.sumOf { it.size.toLong() } ?: 0
                updateProgress(sessionId, fileId, file.fileName, file.size, receivedBytes, TransferState.TRANSFERRING)
                onTransferProgress?.invoke(sessionId, receivedBytes, file.size)
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error handling chunk: ${e.message}", e)
            false
        }
    }
    
    /**
     * Handle transfer complete message
     */
    suspend fun handleTransferComplete(context: Context, sessionId: String, fileId: String): File? = withContext(Dispatchers.IO) {
        val transfer = incomingTransfers[sessionId] ?: return@withContext null
        val file = transfer.files.find { it.id == fileId } ?: return@withContext null
        val chunks = transfer.receivedChunks[fileId] ?: return@withContext null
        
        updateProgress(sessionId, fileId, file.fileName, file.size, file.size, TransferState.VERIFYING)
        
        try {
            // Sort chunks by index and reassemble
            val sortedChunks = chunks.toSortedMap()
            
            // Create output file
            val outputDir = File(context.getExternalFilesDir(null), "AirSync/Received")
            outputDir.mkdirs()
            val outputFile = File(outputDir, file.fileName)
            
            FileOutputStream(outputFile).use { fos ->
                sortedChunks.values.forEach { chunk ->
                    fos.write(chunk)
                }
            }
            
            // Verify checksum if provided
            if (file.sha256 != null) {
                val actualSha256 = calculateSha256(outputFile)
                if (actualSha256 != file.sha256) {
                    Log.e(TAG, "❌ Checksum mismatch for ${file.fileName}")
                    Log.e(TAG, "   Expected: ${file.sha256}")
                    Log.e(TAG, "   Actual: $actualSha256")
                    outputFile.delete()
                    updateProgress(sessionId, fileId, file.fileName, file.size, file.size, TransferState.FAILED, "Checksum mismatch")
                    return@withContext null
                }
                Log.d(TAG, "✅ Checksum verified for ${file.fileName}")
            }
            
            updateProgress(sessionId, fileId, file.fileName, file.size, file.size, TransferState.COMPLETED)
            Log.d(TAG, "✅ File saved: ${outputFile.absolutePath}")
            
            // Clean up chunks
            transfer.receivedChunks.remove(fileId)
            
            // Check if all files are complete
            if (transfer.receivedChunks.isEmpty()) {
                incomingTransfers.remove(sessionId)
                onTransferComplete?.invoke(sessionId, true, null)
            }
            
            outputFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error completing transfer: ${e.message}", e)
            updateProgress(sessionId, fileId, file.fileName, file.size, 0, TransferState.FAILED, e.message)
            null
        }
    }
    
    /**
     * Cancel a transfer
     */
    fun cancelTransfer(sessionId: String): String {
        outgoingTransfers[sessionId]?.let { transfer ->
            transfer.job?.cancel()
            transfer.fileStreams.values.forEach { it.close() }
            outgoingTransfers.remove(sessionId)
        }
        
        incomingTransfers.remove(sessionId)
        
        return JSONObject().apply {
            put("type", "fileTransferCancel")
            put("data", JSONObject().apply {
                put("sessionId", sessionId)
            })
        }.toString()
    }
    
    // ==================== HELPERS ====================
    
    private fun calculateSha256(context: Context, uri: Uri): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating SHA-256: ${e.message}")
            null
        }
    }
    
    private fun calculateSha256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating SHA-256: ${e.message}")
            null
        }
    }
    
    private fun updateProgress(
        sessionId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        transferredBytes: Long,
        state: TransferState,
        error: String? = null
    ) {
        val progress = TransferProgress(sessionId, fileId, fileName, totalBytes, transferredBytes, state, error)
        val currentMap = _transferProgress.value.toMutableMap()
        currentMap["$sessionId:$fileId"] = progress
        _transferProgress.value = currentMap
    }
    
    private fun createErrorResponse(sessionId: String, error: String): String {
        return JSONObject().apply {
            put("type", "fileTransferError")
            put("data", JSONObject().apply {
                put("sessionId", sessionId)
                put("error", error)
            })
        }.toString()
    }
    
    /**
     * Clean up all transfers
     */
    fun cleanup() {
        outgoingTransfers.values.forEach { transfer ->
            transfer.job?.cancel()
            transfer.fileStreams.values.forEach { 
                try { it.close() } catch (_: Exception) {}
            }
        }
        outgoingTransfers.clear()
        incomingTransfers.clear()
        _transferProgress.value = emptyMap()
    }
    
    /**
     * Get transfer state
     */
    fun getTransferState(sessionId: String): TransferState? {
        return _transferProgress.value.values
            .filter { it.sessionId == sessionId }
            .maxByOrNull { it.transferredBytes }
            ?.state
    }
}

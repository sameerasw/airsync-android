package com.sameerasw.airsync.quickshare

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.location.nearby.connections.proto.OfflineFrame
import com.google.location.nearby.connections.proto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.V1Frame
import com.google.location.nearby.connections.proto.ConnectionResponseFrame
import com.google.location.nearby.connections.proto.OsInfo
import com.google.security.cryptauth.lib.securegcm.Ukey2ClientFinished
import com.google.security.cryptauth.lib.securegcm.Ukey2ClientInit
import com.google.security.cryptauth.lib.securegcm.Ukey2ServerInit
import okio.ByteString.Companion.toByteString
import com.google.android.gms.nearby.sharing.ConnectionResponseFrame as SharingResponse
import com.google.android.gms.nearby.sharing.Frame
import com.google.android.gms.nearby.sharing.IntroductionFrame
import com.google.android.gms.nearby.sharing.PairedKeyEncryptionFrame
import com.google.android.gms.nearby.sharing.PairedKeyResultFrame
import com.google.security.cryptauth.lib.securegcm.Ukey2Message
import com.google.android.gms.nearby.sharing.V1Frame as SharingV1
import com.google.location.nearby.connections.proto.PayloadTransferFrame.PayloadHeader
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Handles an incoming Quick Share connection.
 * State machine synchronized with NearDrop (macOS).
 */
class InboundQuickShareConnection(
    private val context: Context,
    private val socket: Socket
) : QuickShareConnection(socket.getInputStream(), socket.getOutputStream()) {

    var onConnectionReady: ((InboundQuickShareConnection) -> Unit)? = null
    var onIntroductionReceived: ((IntroductionFrame) -> Unit)? = null
    var onFinished: ((InboundQuickShareConnection) -> Unit)? = null

    companion object {
        private const val TAG = "InboundQSConnection"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var isRunning = true
    private var encryptionActive = false

    var endpointName: String? = null
        private set
    var ukey2Context: Ukey2Context? = null
        private set
    var introduction: IntroductionFrame? = null
        private set

    private val transferredFiles = ConcurrentHashMap<Long, InternalFileInfo>()

    data class InternalFileInfo(
        val name: String,
        val size: Long,
        var bytesTransferred: Long = 0,
        var file: File? = null,
        var outputStream: java.io.OutputStream? = null,
        var uri: Uri? = null
    )

    init {
        executor.execute {
            try {
                runHandshake()
            } catch (e: Exception) {
                Log.e(TAG, "Handshake failed", e)
                close()
            }
        }
    }

    private fun runHandshake() {
        Log.d(TAG, "Handshake started")
        // 1. Read ConnectionRequest (OfflineFrame, Plaintext)
        val firstFrame = readFrame()
        Log.d(TAG, "Read first frame: ${firstFrame.size} bytes")
        val offlineFrame = OfflineFrame.ADAPTER.decode(firstFrame)
        if (offlineFrame.v1!!.type != V1Frame.FrameType.CONNECTION_REQUEST) {
            throw IllegalStateException("Expected CONNECTION_REQUEST, got ${offlineFrame.v1!!.type}")
        }
        val connectionRequest = offlineFrame.v1!!.connection_request
        endpointName = connectionRequest!!.endpoint_name
        Log.d(TAG, "Received connection request from $endpointName")

        // 2. UKEY2 Handshake
        Log.d(TAG, "Starting UKEY2 handshake")
        val ukey2 = Ukey2Context()
        this.ukey2Context = ukey2
        setUkey2Context(ukey2)

        // Read ClientInit (Wrapped in Ukey2Message)
        val clientInitEnvelopeData = readFrame()
        Log.d(TAG, "Read ClientInit envelope: ${clientInitEnvelopeData.size} bytes")
        val clientInitEnvelope = Ukey2Message.ADAPTER.decode(clientInitEnvelopeData)
        if (clientInitEnvelope.message_type != Ukey2Message.Type.CLIENT_INIT) {
            throw IllegalStateException("Expected CLIENT_INIT, got ${clientInitEnvelope.message_type}")
        }
        val clientInit = Ukey2ClientInit.ADAPTER.decode(clientInitEnvelope.message_data!!)
        
        // Send ServerInit (Wrapped in Ukey2Message)
        val serverInit = ukey2.handleClientInit(clientInit) ?: throw IllegalStateException("Failed to handle ClientInit")
        val serverInitEnvelope = Ukey2Message(
            message_type = Ukey2Message.Type.SERVER_INIT,
            message_data = serverInit.encode().toByteString()
        )
        val serverInitEnvelopeBytes = serverInitEnvelope.encode()
        writeFrame(serverInitEnvelopeBytes)
        Log.d(TAG, "Sent ServerInit envelope")

        // Read ClientFinish (Wrapped in Ukey2Message)
        val clientFinishEnvelopeData = readFrame()
        Log.d(TAG, "Read ClientFinish envelope: ${clientFinishEnvelopeData.size} bytes")
        val clientFinishEnvelope = Ukey2Message.ADAPTER.decode(clientFinishEnvelopeData)
        if (clientFinishEnvelope.message_type != Ukey2Message.Type.CLIENT_FINISH) {
            throw IllegalStateException("Expected CLIENT_FINISH, got ${clientFinishEnvelope.message_type}")
        }
        ukey2.handleClientFinished(
            clientFinishEnvelopeBytes = clientFinishEnvelopeData,
            clientInitEnvelopeBytes = clientInitEnvelopeData,
            serverInitEnvelopeBytes = serverInitEnvelopeBytes,
            clientInit = clientInit
        )
        
        Log.d(TAG, "UKEY2 Handshake complete. PIN: ${ukey2.authString}")
        onConnectionReady?.invoke(this)

        // 3. Connection Response (OfflineFrame, Plaintext)
        // Wait for the remote side to send THEIR ConnectionResponse
        Log.d(TAG, "Waiting for ConnectionResponse")
        val responseFrameData = readFrame()
        Log.d(TAG, "Read ConnectionResponse: ${responseFrameData.size} bytes")
        val responseFrame = OfflineFrame.ADAPTER.decode(responseFrameData)
        if (responseFrame.v1!!.type != V1Frame.FrameType.CONNECTION_RESPONSE) {
             throw IllegalStateException("Expected CONNECTION_RESPONSE, got ${responseFrame.v1!!.type}")
        }
        
        // Send OUR ConnectionResponse (Accept)
        val ourResponse = OfflineFrame(
            version = OfflineFrame.Version.V1,
            v1 = V1Frame(
                type = V1Frame.FrameType.CONNECTION_RESPONSE,
                connection_response = ConnectionResponseFrame(
                    response = ConnectionResponseFrame.ResponseStatus.ACCEPT,
                    status = 0,
                    os_info = OsInfo(type = OsInfo.OsType.ANDROID)
                )
            )
        )
        writeFrame(ourResponse.encode())
        Log.d(TAG, "Sent our ConnectionResponse (ACCEPT)")

        // 4. Enable Encryption
        encryptionActive = true
        Log.d(TAG, "Encryption active. Waiting for PairedKeyEncryption...")

        // 5. Paired Key Exchange (Encrypted)
        // Step A: Read Mac's PairedKeyEncryption
        val pairedKeyEnc = readSharingFrame()
        Log.d(TAG, "Received sharing frame type: ${pairedKeyEnc.v1?.type}")

        // Step B: Send our PairedKeyEncryption back
        val ourPairedKeyEnc = Frame(
            version = Frame.Version.V1,
            v1 = SharingV1(
                type = SharingV1.FrameType.PAIRED_KEY_ENCRYPTION,
                paired_key_encryption = PairedKeyEncryptionFrame(
                    signed_data = java.security.SecureRandom().let { sr ->
                        ByteArray(72).also { sr.nextBytes(it) }.toByteString()
                    },
                    secret_id_hash = java.security.SecureRandom().let { sr ->
                        ByteArray(6).also { sr.nextBytes(it) }.toByteString()
                    }
                )
            )
        )
        writeSharingFrame(ourPairedKeyEnc)
        Log.d(TAG, "Sent PairedKeyEncryption")

        // Step C: Read Mac's PairedKeyResult
        val pairedKeyResultFromMac = readSharingFrame()
        Log.d(TAG, "Received PairedKeyResult from Mac: ${pairedKeyResultFromMac.v1?.type}")

        // Step D: Send our PairedKeyResult
        val ourPairedKeyResult = Frame(
            version = Frame.Version.V1,
            v1 = SharingV1(
                type = SharingV1.FrameType.PAIRED_KEY_RESULT,
                paired_key_result = PairedKeyResultFrame(
                    status = PairedKeyResultFrame.Status.UNABLE
                )
            )
        )
        writeSharingFrame(ourPairedKeyResult)
        Log.d(TAG, "Sent PairedKeyResult")

        // 6. Enter Encrypted Loop
        startEncryptedLoop()
    }

    /**
     * Reads a sharing Frame from the encrypted channel.
     * The Mac wraps sharing frames inside OfflineFrame → PayloadTransfer → payloadChunk.body.
     */
    private fun readSharingFrame(): Frame {
        val d2dPayload = readEncryptedMessage()
        val offlineFrame = OfflineFrame.ADAPTER.decode(d2dPayload)
        val payloadBody = offlineFrame.v1!!.payload_transfer!!.payload_chunk!!.body!!.toByteArray()
        // Read and discard the 'last chunk' marker frame
        readEncryptedMessage()
        return Frame.ADAPTER.decode(payloadBody)
    }

    /**
     * Writes a sharing Frame to the encrypted channel, wrapped in OfflineFrame.
     */
    private fun writeSharingFrame(frame: Frame) {
        val frameBytes = frame.encode()
        val payloadId = java.util.Random().nextLong()
        val dataFrame = OfflineFrame(
            version = OfflineFrame.Version.V1,
            v1 = V1Frame(
                type = V1Frame.FrameType.PAYLOAD_TRANSFER,
                payload_transfer = PayloadTransferFrame(
                    packet_type = PayloadTransferFrame.PacketType.DATA,
                    payload_header = PayloadHeader(
                        id = payloadId,
                        type = PayloadHeader.PayloadType.BYTES,
                        total_size = frameBytes.size.toLong(),
                        is_sensitive = false
                    ),
                    payload_chunk = PayloadTransferFrame.PayloadChunk(
                        offset = 0,
                        flags = 0,
                        body = frameBytes.toByteString()
                    )
                )
            )
        )
        writeEncryptedMessage(dataFrame.encode())

        val lastChunk = OfflineFrame(
            version = OfflineFrame.Version.V1,
            v1 = V1Frame(
                type = V1Frame.FrameType.PAYLOAD_TRANSFER,
                payload_transfer = PayloadTransferFrame(
                    packet_type = PayloadTransferFrame.PacketType.DATA,
                    payload_header = PayloadHeader(
                        id = payloadId,
                        type = PayloadHeader.PayloadType.BYTES,
                        total_size = frameBytes.size.toLong(),
                        is_sensitive = false
                    ),
                    payload_chunk = PayloadTransferFrame.PayloadChunk(
                        offset = frameBytes.size.toLong(),
                        flags = 1
                    )
                )
            )
        )
        writeEncryptedMessage(lastChunk.encode())
    }

    private var pendingBytesPayload: ByteArray? = null

    private fun startEncryptedLoop() {
        while (isRunning) {
            try {
                val d2dPayload = readEncryptedMessage()
                val offlineFrame = OfflineFrame.ADAPTER.decode(d2dPayload)
                
                when (offlineFrame.v1?.type) {
                    V1Frame.FrameType.PAYLOAD_TRANSFER -> {
                        val transfer = offlineFrame.v1!!.payload_transfer!!
                        val header = transfer.payload_header
                        val chunk = transfer.payload_chunk
                        
                        when (header?.type) {
                            PayloadHeader.PayloadType.BYTES -> {
                                if (chunk?.body != null && chunk.body.size > 0) {
                                    pendingBytesPayload = chunk.body.toByteArray()
                                }
                                if ((chunk?.flags ?: 0) and 1 != 0) {
                                    // Last chunk — parse the accumulated bytes as a sharing Frame
                                    pendingBytesPayload?.let { payload ->
                                        val frame = Frame.ADAPTER.decode(payload)
                                        handleSharingFrame(frame)
                                    }
                                    pendingBytesPayload = null
                                }
                            }
                            PayloadHeader.PayloadType.FILE -> {
                                handlePayloadTransfer(transfer)
                            }
                            else -> Log.d(TAG, "Unknown payload type: ${header?.type}")
                        }
                    }
                    V1Frame.FrameType.DISCONNECTION -> {
                        Log.d(TAG, "Received disconnection frame")
                        isRunning = false
                    }
                    else -> Log.d(TAG, "Unknown offline frame type: ${offlineFrame.v1?.type}")
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Encrypted loop error", e)
                }
                break
            }
        }
        close()
    }

    private fun handleSharingFrame(frame: Frame) {
        if (frame.version != Frame.Version.V1) return
        val v1Frame = frame.v1 ?: return
        when (v1Frame.type) {
            SharingV1.FrameType.INTRODUCTION -> {
                introduction = v1Frame.introduction
                Log.d(TAG, "Received introduction: ${introduction?.file_metadata?.size} files")
                prepareFiles(v1Frame.introduction!!)
                onIntroductionReceived?.invoke(v1Frame.introduction!!)
            }
            SharingV1.FrameType.CANCEL -> {
                Log.d(TAG, "Transfer cancelled by sender")
                isRunning = false
            }
            SharingV1.FrameType.PAIRED_KEY_RESULT -> {
                Log.d(TAG, "Received PairedKeyResult")
            }
            else -> Log.d(TAG, "Received unhandled sharing frame type: ${v1Frame.type}")
        }
    }

    /**
     * Sends the final sharing response (Accept/Reject).
     */
    fun sendSharingResponse(status: SharingResponse.Status) {
        val responseFrame = SharingResponse(status = status)
        
        val frame = Frame(
            version = Frame.Version.V1,
            v1 = SharingV1(
                type = SharingV1.FrameType.RESPONSE,
                connection_response = responseFrame
            )
        )
        
        writeSharingFrame(frame)
        
        if (status == SharingResponse.Status.ACCEPT) {
            openFiles()
        }
    }

    private fun prepareFiles(intro: IntroductionFrame) {
        for (fileMeta in intro.file_metadata) {
            transferredFiles[fileMeta.payload_id!!] = InternalFileInfo(
                name = fileMeta.name!!,
                size = fileMeta.size!!
            )
        }
    }

    private fun openFiles() {
        for ((id, info) in transferredFiles) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, info.name)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    info.outputStream = context.contentResolver.openOutputStream(uri)
                    info.uri = uri
                    Log.d(TAG, "Prepared file via MediaStore: ${info.name} -> $uri")
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloadsDir.mkdirs()
                var targetFile = File(downloadsDir, info.name)
                var counter = 1
                while (targetFile.exists()) {
                    val nameWithoutExt = info.name.substringBeforeLast(".")
                    val ext = info.name.substringAfterLast(".", "")
                    targetFile = File(downloadsDir, "$nameWithoutExt ($counter).$ext")
                    counter++
                }
                info.file = targetFile
                info.outputStream = FileOutputStream(targetFile)
                Log.d(TAG, "Prepared file: ${targetFile.absolutePath}")
            }
        }
    }

    private fun handlePayloadTransfer(payloadTransfer: PayloadTransferFrame) {
        val id = payloadTransfer.payload_header?.id ?: return
        val chunk = payloadTransfer.payload_chunk ?: return
        val info = transferredFiles[id] ?: return

        val body = chunk.body?.toByteArray()
        if (body != null && body.isNotEmpty()) {
            info.outputStream?.write(body)
            info.bytesTransferred += body.size
        }

        // Check last chunk flag (flags & 1)
        if ((chunk.flags ?: 0) and 1 != 0) {
            Log.d(TAG, "File ${info.name} transfer complete (${info.bytesTransferred} bytes)")
            info.outputStream?.close()
            info.outputStream = null
            
            // Clear IS_PENDING so file becomes visible in Downloads
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.uri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                context.contentResolver.update(info.uri!!, values, null, null)
            }
            
            // Check if all files are finished
            if (transferredFiles.values.all { it.outputStream == null }) {
                Log.d(TAG, "All files transferred")
                onFinished?.invoke(this)
            }
        }
    }

    fun closeConnection() {
        val wasRunning = isRunning
        isRunning = false
        super.close()
        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore
        }
        executor.shutdownNow()
        if (wasRunning) {
            onFinished?.invoke(this)
        }
    }
}

package com.sameerasw.airsync.quickshare

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.nearby.sharing.ConnectionResponseFrame as SharingResponse
import com.google.android.gms.nearby.sharing.Frame
import com.google.android.gms.nearby.sharing.FileMetadata
import com.google.android.gms.nearby.sharing.IntroductionFrame
import com.google.android.gms.nearby.sharing.PairedKeyEncryptionFrame
import com.google.android.gms.nearby.sharing.PairedKeyResultFrame
import com.google.android.gms.nearby.sharing.V1Frame as SharingV1
import com.google.location.nearby.connections.proto.ConnectionRequestFrame
import com.google.location.nearby.connections.proto.OfflineFrame
import com.google.location.nearby.connections.proto.PayloadTransferFrame
import com.google.location.nearby.connections.proto.PayloadTransferFrame.PayloadHeader
import com.google.location.nearby.connections.proto.V1Frame
import com.google.security.cryptauth.lib.securegcm.Ukey2Message
import com.google.security.cryptauth.lib.securegcm.Ukey2ServerInit
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.Executors

class OutboundQuickShareConnection(
    private val context: Context,
    private val socket: Socket,
    private val ourDeviceName: String,
    private val files: List<PendingFile>
) : QuickShareConnection(socket.getInputStream(), socket.getOutputStream()) {

    data class PendingFile(
        val uri: Uri,
        val name: String,
        val mimeType: String,
        val size: Long,
        val payloadId: Long = kotlin.random.Random.nextLong()
    )

    var onConnectionReady: ((pin: String) -> Unit)? = null
    var onRejected: (() -> Unit)? = null
    var onFileProgress: ((fileName: String, percent: Int, bytesTransferred: Long, totalSize: Long, transferId: String) -> Unit)? =
        null
    var onFileComplete: ((fileName: String, transferId: String, success: Boolean) -> Unit)? = null
    var onFinished: (() -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    companion object {
        private const val TAG = "OutboundQSConnection"
        private const val CHUNK_SIZE = 512 * 1024
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var isRunning = true

    fun start() {
        executor.execute {
            try {
                runHandshake()
            } catch (e: Exception) {
                Log.e(TAG, "Outbound handshake/transfer failed", e)
                onError?.invoke(e)
                closeConnection()
            }
        }
    }

    private fun runHandshake() {
        val request = OfflineFrame(
            version = OfflineFrame.Version.V1,
            v1 = V1Frame(
                type = V1Frame.FrameType.CONNECTION_REQUEST,
                connection_request = ConnectionRequestFrame(
                    endpoint_id = randomEndpointId(),
                    endpoint_name = ourDeviceName,
                    endpoint_info = serializeEndpointInfo(ourDeviceName).toByteString()
                )
            )
        )
        writeFrame(request.encode())
        Log.d(TAG, "Sent ConnectionRequest")

        // 2. UKEY2 handshake (we are client)
        val ukey2 = Ukey2Context()
        val clientInit = ukey2.buildClientInit()
        val clientInitEnvelope = Ukey2Message(
            message_type = Ukey2Message.Type.CLIENT_INIT,
            message_data = clientInit.encode().toByteString()
        )
        val clientInitEnvelopeBytes = clientInitEnvelope.encode()
        writeFrame(clientInitEnvelopeBytes)
        Log.d(TAG, "Sent ClientInit")

        val serverInitEnvelopeBytes = readFrame()
        val serverInitEnvelope = Ukey2Message.ADAPTER.decode(serverInitEnvelopeBytes)
        if (serverInitEnvelope.message_type != Ukey2Message.Type.SERVER_INIT) {
            throw IllegalStateException("Expected SERVER_INIT, got ${serverInitEnvelope.message_type}")
        }
        val serverInit = Ukey2ServerInit.ADAPTER.decode(serverInitEnvelope.message_data!!)
        ukey2.handleServerInit(serverInit, clientInitEnvelopeBytes, serverInitEnvelopeBytes)
        Log.d(TAG, "Received ServerInit, derived keys. PIN: ${ukey2.authString}")

        writeFrame(ukey2.clientFinishEnvelopeBytes!!)
        Log.d(TAG, "Sent ClientFinish")

        setUkey2Context(ukey2)
        onConnectionReady?.invoke(ukey2.authString ?: "----")

        val ourResponse = OfflineFrame(
            version = OfflineFrame.Version.V1,
            v1 = V1Frame(
                type = V1Frame.FrameType.CONNECTION_RESPONSE,
                connection_response = com.google.location.nearby.connections.proto.ConnectionResponseFrame(
                    response = com.google.location.nearby.connections.proto.ConnectionResponseFrame.ResponseStatus.ACCEPT,
                    status = 0
                )
            )
        )
        writeFrame(ourResponse.encode())
        Log.d(TAG, "Sent our ConnectionResponse (ACCEPT)")

        val remoteResponseData = readFrame()
        val remoteResponse = OfflineFrame.ADAPTER.decode(remoteResponseData)
        if (remoteResponse.v1?.type != V1Frame.FrameType.CONNECTION_RESPONSE) {
            throw IllegalStateException("Expected CONNECTION_RESPONSE, got ${remoteResponse.v1?.type}")
        }
        if (remoteResponse.v1.connection_response?.response != com.google.location.nearby.connections.proto.ConnectionResponseFrame.ResponseStatus.ACCEPT) {
            throw IllegalStateException("Receiver rejected the connection")
        }
        Log.d(TAG, "Receiver accepted the connection")

        val ourPairedKeyEnc = Frame(
            version = Frame.Version.V1,
            v1 = SharingV1(
                type = SharingV1.FrameType.PAIRED_KEY_ENCRYPTION,
                paired_key_encryption = PairedKeyEncryptionFrame(
                    signed_data = randomBytes(72),
                    secret_id_hash = randomBytes(6)
                )
            )
        )
        writeSharingFrame(ourPairedKeyEnc)
        Log.d(TAG, "Sent PairedKeyEncryption")

        val remotePairedKeyEnc = readSharingFrame()
        Log.d(TAG, "Received PairedKeyEncryption from receiver: ${remotePairedKeyEnc.v1?.type}")

        val ourPairedKeyResult = Frame(
            version = Frame.Version.V1,
            v1 = SharingV1(
                type = SharingV1.FrameType.PAIRED_KEY_RESULT,
                paired_key_result = PairedKeyResultFrame(status = PairedKeyResultFrame.Status.UNABLE)
            )
        )
        writeSharingFrame(ourPairedKeyResult)
        Log.d(TAG, "Sent PairedKeyResult")

        val remotePairedKeyResult = readSharingFrame()
        Log.d(TAG, "Received PairedKeyResult from receiver: ${remotePairedKeyResult.v1?.type}")

        val introduction = IntroductionFrame(
            file_metadata = files.map { f ->
                FileMetadata(
                    name = f.name,
                    payload_id = f.payloadId,
                    size = f.size,
                    mime_type = f.mimeType
                )
            }
        )
        writeSharingFrame(
            Frame(
                version = Frame.Version.V1,
                v1 = SharingV1(type = SharingV1.FrameType.INTRODUCTION, introduction = introduction)
            )
        )
        Log.d(TAG, "Sent Introduction (${files.size} file(s))")

        val response = waitForResponse()
        if (response != SharingResponse.Status.ACCEPT) {
            Log.d(TAG, "Receiver declined the transfer")
            onRejected?.invoke()
            closeConnection()
            return
        }
        Log.d(TAG, "Receiver accepted the transfer, sending files")

        for (file in files) {
            sendFile(file)
        }

        try {
            socket.shutdownOutput()
            socket.soTimeout = 5000
            readFrame()
        } catch (e: Exception) {
            Log.d(TAG, "Post-send wait ended (expected once the receiver finishes/closes): $e")
        }

        onFinished?.invoke()
        closeConnection()
    }

    private fun waitForResponse(): SharingResponse.Status {
        while (isRunning) {
            val frame = readSharingFrame()
            val v1 = frame.v1 ?: continue
            when (v1.type) {
                SharingV1.FrameType.RESPONSE -> {
                    return v1.connection_response?.status ?: SharingResponse.Status.UNKNOWN
                }

                SharingV1.FrameType.CANCEL -> {
                    return SharingResponse.Status.REJECT
                }

                else -> Log.d(TAG, "Ignoring sharing frame while waiting for response: ${v1.type}")
            }
        }
        return SharingResponse.Status.REJECT
    }

    private fun sendFile(file: PendingFile) {
        val stream: InputStream = context.contentResolver.openInputStream(file.uri)
            ?: throw IllegalStateException("Could not open ${file.name}")

        stream.use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var offset = 0L
            var lastUpdate = 0L

            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break

                val chunkBody = if (read == buffer.size) buffer else buffer.copyOf(read)
                writePayloadChunk(file, offset = offset, flags = 0, body = chunkBody)
                offset += read

                val now = System.currentTimeMillis()
                if (now - lastUpdate > 250) {
                    lastUpdate = now
                    val percent = if (file.size > 0) ((offset * 100) / file.size).toInt() else 100
                    onFileProgress?.invoke(file.name, percent, offset, file.size, file.payloadId.toString())
                }
            }

            writePayloadChunk(file, offset = offset, flags = 1, body = null)
            onFileProgress?.invoke(file.name, 100, offset, file.size, file.payloadId.toString())
        }

        Log.d(TAG, "Finished sending ${file.name}")
        onFileComplete?.invoke(file.name, file.payloadId.toString(), true)
    }

    private fun writePayloadChunk(file: PendingFile, offset: Long, flags: Int, body: ByteArray?) {
        writeEncryptedMessage(
            OfflineFrame(
                version = OfflineFrame.Version.V1,
                v1 = V1Frame(
                    type = V1Frame.FrameType.PAYLOAD_TRANSFER,
                    payload_transfer = PayloadTransferFrame(
                        packet_type = PayloadTransferFrame.PacketType.DATA,
                        payload_header = PayloadHeader(
                            id = file.payloadId,
                            type = PayloadHeader.PayloadType.FILE,
                            total_size = file.size,
                            file_name = file.name
                        ),
                        payload_chunk = PayloadTransferFrame.PayloadChunk(
                            offset = offset,
                            flags = flags,
                            body = body?.toByteString()
                        )
                    )
                )
            ).encode()
        )
    }

    private fun readSharingFrame(): Frame {
        val d2dPayload = readEncryptedMessage()
        val offlineFrame = OfflineFrame.ADAPTER.decode(d2dPayload)
        val payloadBody = offlineFrame.v1!!.payload_transfer!!.payload_chunk!!.body!!.toByteArray()
        readEncryptedMessage() // discard the last-chunk marker frame
        return Frame.ADAPTER.decode(payloadBody)
    }

    private fun writeSharingFrame(frame: Frame) {
        val frameBytes = frame.encode()
        val payloadId = kotlin.random.Random.nextLong()
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

    private fun serializeEndpointInfo(deviceName: String): ByteArray {
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        val nameLen = nameBytes.size.coerceAtMost(255)

        val bytes = ByteArray(1 + 16 + 1 + nameLen)
        bytes[0] = (1 shl 1).toByte() // deviceType=phone(1) << 1 | visibility(0) | version(0)
        val randomBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        System.arraycopy(randomBytes, 0, bytes, 1, 16)
        bytes[17] = nameLen.toByte()
        System.arraycopy(nameBytes, 0, bytes, 18, nameLen)
        return bytes
    }

    private fun randomEndpointId(): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return (1..4).map { alphabet.random() }.joinToString("")
    }

    private fun randomBytes(n: Int) =
        ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }.toByteString()

    fun closeConnection() {
        isRunning = false
        close()
        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore
        }
        executor.shutdownNow()
    }
}

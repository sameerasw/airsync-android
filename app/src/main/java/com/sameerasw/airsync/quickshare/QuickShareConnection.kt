package com.sameerasw.airsync.quickshare

import com.google.security.cryptauth.lib.securegcm.DeviceToDeviceMessage
import com.google.security.cryptauth.lib.securegcm.GcmMetadata
import com.google.security.cryptauth.lib.securegcm.Type
import com.google.security.cryptauth.lib.securemessage.EncScheme
import com.google.security.cryptauth.lib.securemessage.Header
import com.google.security.cryptauth.lib.securemessage.HeaderAndBody
import com.google.security.cryptauth.lib.securemessage.SecureMessage
import com.google.security.cryptauth.lib.securemessage.SigScheme
import okio.ByteString.Companion.toByteString
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles length-prefixed framing and optional encryption for Quick Share connections.
 * Framing and SecureMessage logic synchronized with NearDrop (macOS).
 */
open class QuickShareConnection(
    inputStream: InputStream,
    private val outputStream: OutputStream
) {
    private val dataInputStream = DataInputStream(inputStream)
    private val dataOutputStream = DataOutputStream(outputStream)
    private var ukey2Context: Ukey2Context? = null
    private var decryptSequence = 0
    private var encryptSequence = 0

    fun setUkey2Context(context: Ukey2Context) {
        this.ukey2Context = context
    }

    /**
     * Reads a big-endian length-prefixed frame.
     */
    fun readFrame(): ByteArray {
        val length = dataInputStream.readInt() // readInt is big-endian
        if (length < 0 || length > 10 * 1024 * 1024) {
            throw IllegalStateException("Invalid frame length: $length")
        }
        val frame = ByteArray(length)
        dataInputStream.readFully(frame)
        return frame
    }

    /**
     * Writes a big-endian length-prefixed frame.
     */
    fun writeFrame(frame: ByteArray) {
        dataOutputStream.writeInt(frame.size)
        dataOutputStream.write(frame)
        dataOutputStream.flush()
    }

    /**
     * Reads and decrypts a SecureMessage.
     */
    fun readEncryptedMessage(): ByteArray {
        val context = ukey2Context ?: throw IllegalStateException("UKEY2 context not set")
        val frameData = readFrame()
        
        val smsg = SecureMessage.ADAPTER.decode(frameData)
        
        // 1. Verify HMAC
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(context.receiveHmacKey, "HmacSHA256"))
        val hbBytes = smsg.header_and_body!!.toByteArray()
        val calculatedHmac = mac.doFinal(hbBytes)
        if (!calculatedHmac.contentEquals(smsg.signature!!.toByteArray())) {
            throw SecurityException("SecureMessage HMAC mismatch")
        }

        // 2. Decrypt HeaderAndBody
        val hb = HeaderAndBody.ADAPTER.decode(smsg.header_and_body!!)
        val iv = hb.header_!!.iv!!.toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(context.decryptKey, "AES"), IvParameterSpec(iv))
        val decryptedData = cipher.doFinal(hb.body!!.toByteArray())

        // 3. Parse DeviceToDeviceMessage
        val d2dMsg = DeviceToDeviceMessage.ADAPTER.decode(decryptedData)
        // clientSeq in NearDrop starts at 0 and increments before check
        decryptSequence++
        if (d2dMsg.sequence_number != decryptSequence) {
            throw SecurityException("Sequence number mismatch. Expected $decryptSequence, got ${d2dMsg.sequence_number}")
        }

        return d2dMsg.message!!.toByteArray()
    }

    /**
     * Encrypts and writes a SecureMessage.
     */
    fun writeEncryptedMessage(data: ByteArray) {
        val context = ukey2Context ?: throw IllegalStateException("UKEY2 context not set")

        // 1. Create DeviceToDeviceMessage
        encryptSequence++
        val d2dMsg = DeviceToDeviceMessage(
            message = data.toByteString(),
            sequence_number = encryptSequence
        )
        val serializedD2D = d2dMsg.encode()

        // 2. Encrypt with AES-CBC
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16).also { java.util.Random().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(context.encryptKey, "AES"), IvParameterSpec(iv))
        val encryptedData = cipher.doFinal(serializedD2D)

        // 3. Create HeaderAndBody
        val md = GcmMetadata(
            type = Type.DEVICE_TO_DEVICE_MESSAGE,
            version = 1
        )

        val hb = HeaderAndBody(
            body = encryptedData.toByteString(),
            header_ = Header(
                signature_scheme = SigScheme.HMAC_SHA256,
                encryption_scheme = EncScheme.AES_256_CBC,
                iv = iv.toByteString(),
                public_metadata = md.encode().toByteString()
            )
        )
        val serializedHB = hb.encode()

        // 4. Create SecureMessage with HMAC
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(context.sendHmacKey, "HmacSHA256"))
        val signature = mac.doFinal(serializedHB)

        val smsg = SecureMessage(
            header_and_body = serializedHB.toByteString(),
            signature = signature.toByteString()
        )

        writeFrame(smsg.encode())
    }

    fun close() {
        dataInputStream.close()
        dataOutputStream.close()
    }
}

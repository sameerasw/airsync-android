package com.sameerasw.airsync.utils

import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.LinkedList
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Thread-safe nonce replay cache to prevent replay attacks on AES-GCM messages.
 * Tracks recently-seen 12-byte nonces and rejects duplicates.
 */
private object NonceReplayGuard {
    private const val MAX_ENTRIES = 10_000
    private val lock = Any()
    private val seenNonces = HashSet<ByteBuffer>(MAX_ENTRIES)
    private val nonceOrder = LinkedList<ByteBuffer>()

    /**
     * Returns `true` if the nonce has NOT been seen before (message is fresh).
     * Returns `false` if the nonce is a duplicate (replay detected).
     */
    fun checkAndRecord(nonce: ByteArray): Boolean {
        val key = ByteBuffer.wrap(nonce.copyOf())
        synchronized(lock) {
            if (seenNonces.contains(key)) {
                return false // replay
            }
            seenNonces.add(key)
            nonceOrder.addLast(key)
            // Evict oldest entries when cache is full
            if (nonceOrder.size > MAX_ENTRIES) {
                val evict = nonceOrder.removeFirst()
                seenNonces.remove(evict)
            }
            return true
        }
    }

    /** Clears the replay cache (e.g. on key rotation or reconnect). */
    fun reset() {
        synchronized(lock) {
            seenNonces.clear()
            nonceOrder.clear()
        }
    }
}

object CryptoUtil {

    private const val TAG = "CryptoUtil"
    private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
    private const val NONCE_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    fun decodeKey(base64Key: String): SecretKey? {
        return try {
            // Accept standard and URL-safe Base64 variants.
            var normalized = base64Key.trim()
                .replace(" ", "+")
                .replace("-", "+")
                .replace("_", "/")
                .replace("\n", "")
                .replace("\r", "")

            // Ensure valid padding length for Base64 decoder.
            val pad = normalized.length % 4
            if (pad != 0) {
                normalized += "=".repeat(4 - pad)
            }

            val keyBytes = Base64.decode(normalized, Base64.DEFAULT)
            if (keyBytes.isEmpty()) {
                null
            } else {
                SecretKeySpec(keyBytes, "AES")
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun decryptMessage(base64Combined: String, key: SecretKey): String? {
        return try {
            val combined = Base64.decode(base64Combined, Base64.DEFAULT)

            if (combined.size < NONCE_SIZE_BYTES) {
                return null // Invalid message
            }

            val nonce = combined.copyOfRange(0, NONCE_SIZE_BYTES)

            // Anti-replay: check that this nonce hasn't been used before
            if (!NonceReplayGuard.checkAndRecord(nonce)) {
                Log.w(TAG, "Replay detected: duplicate nonce, dropping message")
                return null
            }

            val ciphertextWithTag = combined.copyOfRange(NONCE_SIZE_BYTES, combined.size)

            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            val spec = GCMParameterSpec(TAG_SIZE_BITS, nonce)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val plainBytes = cipher.doFinal(ciphertextWithTag)
            String(plainBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun encryptMessage(message: String, key: SecretKey): String? {
        return try {
            val nonce = ByteArray(NONCE_SIZE_BYTES)
            SecureRandom().nextBytes(nonce)

            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            val spec = GCMParameterSpec(TAG_SIZE_BITS, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)

            val ciphertext = cipher.doFinal(message.toByteArray(StandardCharsets.UTF_8))

            val combined = nonce + ciphertext
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Computes HMAC-SHA256 over the given data using the raw bytes of the provided key.
     * Returns the result as a lowercase hex string.
     */
    fun hmacSha256(key: SecretKey, data: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        val hmacKey = SecretKeySpec(key.encoded, "HmacSHA256")
        mac.init(hmacKey)
        val result = mac.doFinal(data)
        return result.joinToString("") { "%02x".format(it) }
    }

    /**
     * Decodes a hex string into a byte array.
     */
    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Resets the replay nonce cache (call on key rotation or reconnect).
     */
    fun resetReplayGuard() {
        NonceReplayGuard.reset()
    }
}


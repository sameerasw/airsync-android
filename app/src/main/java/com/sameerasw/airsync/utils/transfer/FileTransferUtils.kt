package com.sameerasw.airsync.utils.transfer

import android.net.Uri
import android.content.ContentResolver
import java.io.InputStream
import java.security.MessageDigest

object FileTransferUtils {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { String.format("%02x", it) }
    }
    
    /**
     * Calculate SHA-256 checksum from an InputStream (streaming, memory-efficient for large files)
     */
    fun sha256HexFromStream(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { String.format("%02x", it) }
    }

    fun base64NoWrap(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun readAllBytes(resolver: ContentResolver, uri: Uri): ByteArray? =
        resolver.openInputStream(uri)?.use { it.readBytes() }
}

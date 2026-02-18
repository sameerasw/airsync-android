package com.sameerasw.airsync.utils.transfer

import android.content.ContentResolver
import android.net.Uri

object FileTransferUtils {
    fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { String.format("%02x", it) }
    }

    fun base64NoWrap(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun readAllBytes(resolver: ContentResolver, uri: Uri): ByteArray? =
        resolver.openInputStream(uri)?.use { it.readBytes() }
}

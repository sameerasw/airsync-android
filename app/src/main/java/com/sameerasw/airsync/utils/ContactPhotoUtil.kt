package com.sameerasw.airsync.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Utility for extracting and encoding contact photos as base64
 *
 * Images are:
 * - Max 180px × 180px (aspect ratio preserved)
 * - PNG format (lossless)
 * - Base64 encoded (standard format with padding)
 * - Max 75KB when encoded (images larger than this fall back to 128px, then discarded if still too large)
 */
object ContactPhotoUtil {
    private const val TAG = "ContactPhotoUtil"
    private const val MAX_SIZE = 180  // 180x180 for better quality
    private const val FALLBACK_SIZE = 128  // Fallback if too large
    private const val MAX_BASE64_SIZE = 75000  // Max 75KB base64 encoded

    /**
     * Get contact photo as base64-encoded string (PNG, max 128px)
     * Uses NO_PADDING Base64 encoding to match app icon encoding standard
     * Returns empty string if photo not found or too large
     */
    fun getContactPhotoBase64(context: Context, phoneNumber: String?): String {
        if (phoneNumber.isNullOrBlank() || phoneNumber == "Unknown") {
            return ""
        }

        return try {
            // Find contact ID from phone number
            val contactId = getContactIdFromPhone(context, phoneNumber)
            if (contactId == null) {
                Log.d(TAG, "Contact not found for number: $phoneNumber")
                return ""
            }

            // Get contact photo URI
            val photoUri = getContactPhotoUri(context, contactId)
            if (photoUri == null) {
                Log.d(TAG, "No photo found for contact: $contactId")
                return ""
            }

            // Load and resize the photo
            val bitmap = loadBitmap(context, photoUri)
            if (bitmap == null) {
                Log.d(TAG, "Failed to load bitmap from URI: $photoUri")
                return ""
            }

            val resizedBitmap = resizeBitmap(bitmap, MAX_SIZE)
            var base64 = encodeBitmapToBase64(resizedBitmap)

            // If image is still too large, try aggressive resizing
            if (base64.length > MAX_BASE64_SIZE) {
                Log.w(TAG, "Image too large (${base64.length} chars), trying smaller size")
                val smallerBitmap = resizeBitmap(bitmap, FALLBACK_SIZE)  // Try 128px
                base64 = encodeBitmapToBase64(smallerBitmap)
            }

            // Final check
            if (base64.length > MAX_BASE64_SIZE) {
                Log.w(TAG, "Image still too large after compression, discarding")
                return ""
            }

            Log.d(TAG, "✅ Contact photo encoded (PNG, 180px max): ${base64.length} bytes")
            base64
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact photo: ${e.message}")
            ""
        }
    }

    /**
     * Get contact ID from phone number
     */
    private fun getContactIdFromPhone(context: Context, phoneNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )

            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup._ID))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact ID: ${e.message}")
            null
        }
    }

    /**
     * Get contact photo URI
     */
    private fun getContactPhotoUri(context: Context, contactId: String): Uri? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.Contacts.CONTENT_URI,
                contactId
            )

            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.Contacts.PHOTO_URI),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val photoUri =
                        cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.Contacts.PHOTO_URI))
                    if (!photoUri.isNullOrEmpty()) Uri.parse(photoUri) else null
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact photo URI: ${e.message}")
            null
        }
    }

    /**
     * Load bitmap from URI
     */
    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri).use { inputStream ->
                if (inputStream != null) {
                    BitmapFactory.decodeStream(inputStream)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap: ${e.message}")
            null
        }
    }

    /**
     * Resize bitmap to max size while maintaining aspect ratio
     */
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        return if (width > maxSize || height > maxSize) {
            val ratio = width.toFloat() / height.toFloat()
            val (newWidth, newHeight) = if (ratio > 1) {
                Pair(maxSize, (maxSize / ratio).toInt())
            } else {
                Pair((maxSize * ratio).toInt(), maxSize)
            }
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
    }

    /**
     * Encode bitmap to base64 string (PNG format with standard Base64)
     * Uses Base64.DEFAULT (with padding) for compatibility
     */
    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val imageBytes = outputStream.toByteArray()
            // Use DEFAULT Base64 encoding (with padding) for standard compatibility
            Base64.encodeToString(imageBytes, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding bitmap to base64: ${e.message}")
            ""
        }
    }
}


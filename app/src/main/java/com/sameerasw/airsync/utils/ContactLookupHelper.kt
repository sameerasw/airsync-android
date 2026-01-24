package com.sameerasw.airsync.utils

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Helper class for phone number normalization and contact lookup with caching.
 * Uses libphonenumber for robust number formatting and PhoneLookup for contact matching.
 */
class ContactLookupHelper(private val context: Context) {

    private val phoneNumberUtil = PhoneNumberUtil.getInstance()
    private val contactCache = ConcurrentHashMap<String, CachedContact>()
    private val negativeCache = ConcurrentHashMap<String, Long>()

    private data class CachedContact(
        val displayName: String?,
        val cachedAtMillis: Long
    )

    /**
     * Normalize a phone number to E.164 format for consistent lookups.
     * Intelligently detects country code from number or uses device locale.
     * Falls back to the original number if normalization fails.
     */
    fun normalizeNumber(number: String, defaultRegion: String? = null): String {
        return try {
            val region = defaultRegion ?: detectCountryCode(number) ?: getDeviceCountryCode()
            val parsed = phoneNumberUtil.parse(number, region)
            phoneNumberUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
        } catch (e: Exception) {
            // If parsing fails, return the original number
            number
        }
    }

    /**
     * Detect country code from phone number format
     * Returns null if cannot detect (will fallback to device locale)
     */
    private fun detectCountryCode(number: String): String? {
        return try {
            when {
                // If number starts with +, try to parse without region hint
                number.startsWith("+") -> {
                    val parsed = phoneNumberUtil.parse(number, "")
                    phoneNumberUtil.getRegionCodeForNumber(parsed)
                }
                // If number starts with 0, it's likely national format - need device locale
                number.startsWith("0") -> null
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get device's country code from SIM card or locale
     */
    private fun getDeviceCountryCode(): String {
        return try {
            // First try to get country from SIM card (most accurate for phone numbers)
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            val simCountry = telephonyManager?.simCountryIso?.uppercase()
            if (!simCountry.isNullOrEmpty()) {
                return simCountry
            }
            
            // Fallback to network country
            val networkCountry = telephonyManager?.networkCountryIso?.uppercase()
            if (!networkCountry.isNullOrEmpty()) {
                return networkCountry
            }
            
            // Fallback to device locale
            context.resources.configuration.locales[0].country.takeIf { it.isNotEmpty() } ?: "US"
        } catch (e: Exception) {
            // Final fallback - try deprecated locale method
            try {
                @Suppress("DEPRECATION")
                context.resources.configuration.locale.country.takeIf { it.isNotEmpty() } ?: "US"
            } catch (e2: Exception) {
                "US"
            }
        }
    }

    /**
     * Find contact display name for a given phone number.
     * Caches results to avoid repeated expensive lookups.
     * Uses negative caching to avoid repeated lookups for non-existent contacts.
     * Returns null for unknown/unfound numbers.
     */
    suspend fun findContactName(number: String): String? = withContext(Dispatchers.IO) {
        if (number.isBlank()) return@withContext null

        val normalizedNumber = try {
            normalizeNumber(number)
        } catch (e: Exception) {
            number  // Use original if normalization fails
        }

        // Check negative cache (contact doesn't exist)
        val negativeTimeMillis = negativeCache[normalizedNumber]
        if (negativeTimeMillis != null && System.currentTimeMillis() - negativeTimeMillis < NEGATIVE_CACHE_TTL) {
            Log.d("ContactLookupHelper", "✓ Negative cache hit for: $normalizedNumber (no contact found)")
            return@withContext null
        }

        // Check positive cache
        val cached = contactCache[normalizedNumber]
        if (cached != null && System.currentTimeMillis() - cached.cachedAtMillis < CONTACT_CACHE_TTL) {
            if (cached.displayName != null && cached.displayName.isNotEmpty()) {
                Log.d("ContactLookupHelper", "✓ Cache hit for: $normalizedNumber -> ${cached.displayName}")
                return@withContext cached.displayName
            } else {
                Log.d("ContactLookupHelper", "✓ Cache hit (no name) for: $normalizedNumber")
                return@withContext null
            }
        }

        // Perform actual lookup - try both normalized and original numbers
        var displayName = performPhoneLookup(normalizedNumber)
        if (displayName.isNullOrEmpty()) {
            displayName = performPhoneLookup(number)
        }

        return@withContext if (!displayName.isNullOrEmpty()) {
            // Verify the name is not just the number itself
            if (displayName == normalizedNumber || displayName == number) {
                Log.d("ContactLookupHelper", "⚠ Lookup returned number as name, treating as unknown: $number")
                negativeCache[normalizedNumber] = System.currentTimeMillis()
                null
            } else {
                // Cache the valid result
                Log.d("ContactLookupHelper", "✓ Found contact for $normalizedNumber: $displayName")
                contactCache[normalizedNumber] = CachedContact(displayName, System.currentTimeMillis())
                negativeCache.remove(normalizedNumber)
                displayName
            }
        } else {
            // Add to negative cache
            Log.d("ContactLookupHelper", "✓ No contact found for: $normalizedNumber")
            negativeCache[normalizedNumber] = System.currentTimeMillis()
            null
        }
    }

    /**
     * Perform actual contact lookup using ContactsContract.PhoneLookup
     */
    private fun performPhoneLookup(number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return@use cursor.getString(0)
                }
                return@use null
            }
        } catch (e: SecurityException) {
            // READ_CONTACTS permission not granted
            null
        } catch (e: Exception) {
            // Other exceptions (query errors, etc.)
            null
        }
    }

    /**
     * Clear all caches - useful when permissions are revoked
     */
    fun clearCache() {
        contactCache.clear()
        negativeCache.clear()
    }

    companion object {
        // 24 hours TTL for positive contact cache
        private const val CONTACT_CACHE_TTL = 24 * 60 * 60 * 1000L

        // 2 hours TTL for negative cache (non-existent contacts)
        private const val NEGATIVE_CACHE_TTL = 2 * 60 * 60 * 1000L
    }
}

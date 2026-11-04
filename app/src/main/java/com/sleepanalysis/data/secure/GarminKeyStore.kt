package com.sleepanalysis.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.AEADBadTagException
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores the Garmin Personal API key encrypted at rest with an Android Keystore AES-256-GCM key
 * (docs/GARMIN_INTEGRATION.md §2). Ciphertext lives in an app-private preferences file; the
 * key material never leaves AndroidKeyStore and the plaintext is never logged or persisted.
 *
 * Deliberately implemented directly on AndroidKeyStore instead of the deprecated
 * androidx.security-crypto library. Blocking operations — call from Dispatchers.IO.
 */
@Singleton
class GarminKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun hasKey(): Boolean = prefs.contains(CIPHERTEXT_KEY)

    /** Returns the stored key in plaintext, or null if none is stored. */
    fun load(): String? {
        val stored = prefs.getString(CIPHERTEXT_KEY, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: AEADBadTagException) {
            // The Keystore key and the stored blob no longer match (restored backup, Keystore
            // reset) — the blob can never decrypt again. Drop it so hasKey() reflects reality
            // instead of reporting a permanently unloadable key.
            clear()
            null
        } catch (_: Exception) {
            // Wrong key state or corrupted ciphertext — treat as absent rather than crashing.
            null
        }
    }

    fun save(plaintext: String) {
        require(plaintext.isNotBlank()) { "API key must not be blank" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.trim().toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(CIPHERTEXT_KEY, blob).apply()
    }

    fun clear() {
        prefs.edit().remove(CIPHERTEXT_KEY).apply()
    }

    /** Masked preview for the settings UI, e.g. "…727". Returns null when no key is stored. */
    fun maskedPreview(): String? = load()?.takeLast(4)?.let { "…$it" }

    private companion object {
        const val PREFS_FILE = "garmin_secure_prefs"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "garmin_personal_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val CIPHERTEXT_KEY = "personal_api_key_v1"
    }
}

package com.github.kr328.clash.service.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun initializeDiagnosticsEncryptionCipher(cipher: Cipher, key: SecretKey): ByteArray {
    cipher.init(Cipher.ENCRYPT_MODE, key)
    return cipher.iv
}

/** Device-bound, private storage for the manual Chisel client credential. */
class DiagnosticsCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Returns only a successfully decrypted credential; invalid state fails closed. */
    fun read(): String? {
        val encodedCiphertext = preferences.getString(CIPHERTEXT, null) ?: return null
        val encodedIv = preferences.getString(IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP))
            )
            cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
                .toString(StandardCharsets.UTF_8)
                .takeIf(String::isNotBlank)
                ?: error("empty credential")
        }.getOrElse {
            // A restored ciphertext cannot be decrypted by this device-bound key.
            runCatching { keyStore.deleteEntry(KEY_ALIAS) }
            preferences.edit().clear().commit()
            null
        }
    }

    fun save(username: String, password: String): Boolean {
        if (username.isBlank() || ':' in username || password.isBlank()) return false
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = initializeDiagnosticsEncryptionCipher(cipher, key())
            val ciphertext = cipher.doFinal("$username:$password".toByteArray(StandardCharsets.UTF_8))
            preferences.edit()
                .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .commit()
        }.getOrDefault(false)
    }

    fun clear(): Boolean {
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
        return preferences.edit().clear().commit()
    }

    private fun key(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private companion object {
        const val PREFERENCES = "diagnostics_credentials"
        const val CIPHERTEXT = "ciphertext"
        const val IV = "iv"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "clod_diagnostics_auth"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }
}

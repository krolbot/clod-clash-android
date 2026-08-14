package com.github.kr328.clash.service.store

import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsCipherTest {
    @Test
    fun `encryption cipher generates its own iv`() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val plaintext = "user:password".toByteArray(StandardCharsets.UTF_8)
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding")

        val iv = initializeDiagnosticsEncryptionCipher(encrypt, key)
        val ciphertext = encrypt.doFinal(plaintext)

        assertEquals(12, iv.size)
        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        assertArrayEquals(plaintext, decrypt.doFinal(ciphertext))
    }
}

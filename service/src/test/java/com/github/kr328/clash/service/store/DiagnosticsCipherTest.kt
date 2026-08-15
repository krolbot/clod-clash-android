package com.github.kr328.clash.service.store

import com.github.kr328.clash.core.model.ExternalControllerAccess
import com.github.kr328.clash.service.model.DiagnosticsSessionAccess
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCipherTest {
    @Test
    fun `session access uses one credential snapshot`() {
        val credential = requireNotNull(DiagnosticsCredential.create("operator", "controller-secret"))
        val session = DiagnosticsSessionAccess.from(credential)
        val controller = session.controller as ExternalControllerAccess.Diagnostics

        assertEquals("controller-secret", controller.secret)
        assertEquals("operator:controller-secret", session.diagnostics?.tunnelAuth)
        assertEquals("controller-secret", session.diagnostics?.controllerSecret)

        val local = DiagnosticsSessionAccess.from(null)
        assertTrue(local.controller === ExternalControllerAccess.LocalOnly)
        assertNull(local.diagnostics)
    }

    @Test
    fun `credential owns chisel auth and controller secret`() {
        val credential = DiagnosticsCredential.create("operator", "secret:with-colon")

        requireNotNull(credential)
        assertEquals("operator:secret:with-colon", credential.chiselAuth)
        assertEquals("secret:with-colon", credential.controllerSecret)
        assertEquals(credential, DiagnosticsCredential.decode(credential.chiselAuth))
    }

    @Test
    fun `credential rejects incomplete values`() {
        assertNull(DiagnosticsCredential.create("", "secret"))
        assertNull(DiagnosticsCredential.create("operator:name", "secret"))
        assertNull(DiagnosticsCredential.create("operator", ""))
        assertNull(DiagnosticsCredential.create("operator name", "secret"))
        assertNull(DiagnosticsCredential.create("operator", "line\nbreak"))
        assertNull(DiagnosticsCredential.create("operator", "пароль"))
        assertNull(DiagnosticsCredential.decode("missing-separator"))
        assertNull(DiagnosticsCredential.decode(":secret"))
        assertNull(DiagnosticsCredential.decode("operator:"))
    }

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

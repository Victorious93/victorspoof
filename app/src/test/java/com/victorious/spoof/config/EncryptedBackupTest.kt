package com.victorious.spoof.config

import com.victorious.spoof.config.EncryptedBackup.BackupCryptoException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class EncryptedBackupTest {
    private val plain = """{"version":1,"entries":[{"packageName":"com.victorious.spoof","clearPayload":"{\"prefs\":{\"ui.theme\":\"dark\"}}"}]}"""
    private val fastIter = 100_000

    private fun fails(block: () -> Unit): String = try { block(); fail("expected failure"); "" } catch (e: BackupCryptoException) { e.message!! }

    @Test fun passphraseRoundTrip() {
        val env = EncryptedBackup.encryptWithPassphrase(plain, "correct horse".toCharArray(), fastIter)
        assertFalse("plaintext leaked", env.contains("ui.theme") || env.contains("dark"))
        assertEquals(plain, EncryptedBackup.decrypt(env, "correct horse".toCharArray()))
        assertEquals("pbkdf2-sha256", EncryptedBackup.kdfOf(env))
    }
    @Test fun wrongPassphraseRejected() {
        val env = EncryptedBackup.encryptWithPassphrase(plain, "correct horse".toCharArray(), fastIter)
        assertTrue(fails { EncryptedBackup.decrypt(env, "wrong horse!".toCharArray()) }.contains("wrong passphrase"))
    }
    @Test fun tamperingDetected() {
        val env = Json.parseToJsonElement(EncryptedBackup.encryptWithPassphrase(plain, "correct horse".toCharArray(), fastIter)).jsonObject
        val ct = java.util.Base64.getDecoder().decode(env["ct"]!!.jsonPrimitive.content); ct[3] = (ct[3].toInt() xor 1).toByte()
        val bad = JsonObject(env + ("ct" to JsonPrimitive(java.util.Base64.getEncoder().encodeToString(ct)))).toString()
        fails { EncryptedBackup.decrypt(bad, "correct horse".toCharArray()) }
        // Header is authenticated too (AAD): downgrading the iteration count breaks decryption.
        val lowered = JsonObject(env + ("iter" to JsonPrimitive(fastIter + 1))).toString()
        fails { EncryptedBackup.decrypt(lowered, "correct horse".toCharArray()) }
    }
    @Test fun freshSaltAndIvEveryTime() {
        val a = EncryptedBackup.encryptWithPassphrase(plain, "correct horse".toCharArray(), fastIter)
        val b = EncryptedBackup.encryptWithPassphrase(plain, "correct horse".toCharArray(), fastIter)
        assertNotEquals(a, b)
    }
    @Test fun shortPassphraseAndWeakKdfRefused() {
        fails { EncryptedBackup.encryptWithPassphrase(plain, "short".toCharArray()) }
        val env = Json.parseToJsonElement(EncryptedBackup.encryptWithPassphrase(plain, "correct horse".toCharArray(), fastIter)).jsonObject
        fails { EncryptedBackup.decrypt(JsonObject(env + ("iter" to JsonPrimitive(1000))).toString(), "correct horse".toCharArray()) }
    }
    @Test fun keyRoundTripStandsInForKeystore() {
        // AndroidKeyStore is not available on the JVM; the same AES-GCM path is exercised with a software key.
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val env = EncryptedBackup.encryptWithKey(plain, key)
        assertEquals("android-keystore", EncryptedBackup.kdfOf(env))
        assertEquals(plain, EncryptedBackup.decrypt(env, keystoreKey = key))
        assertTrue(fails { EncryptedBackup.decrypt(env) }.contains("device that made it"))
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        fails { EncryptedBackup.decrypt(env, keystoreKey = other) }
    }
    @Test fun fullExportImportRoundTripNeverContainsSecrets() {
        val live = mapOf("ui.theme" to JsonPrimitive("dark"), "ui.voicePreset" to JsonPrimitive("Robot"),
            "twilio.authToken" to JsonPrimitive("abc"), "numberPool" to JsonArray(listOf(JsonPrimitive("+14155550100"))))
        val entry = ConfigBackup.exportEntry(live, "local", key = null, clearPayload = true)
        val bundle = buildJsonObject { put("version", 1); put("sourceNodeRef", "local"); put("wrapping", "none"); putJsonArray("entries") { add(entry) } }.toString()
        for (bad in listOf("twilio", "authToken", "numberPool", "4155550100")) assertFalse(bad, bundle.contains(bad, ignoreCase = true))
        val env = EncryptedBackup.encryptWithPassphrase(bundle, "correct horse".toCharArray(), fastIter)
        val payload = ConfigBackup.payloadFromBundle(EncryptedBackup.decrypt(env, "correct horse".toCharArray()), null)
        assertEquals(setOf("ui.theme", "ui.voicePreset"), payload["prefs"]!!.jsonObject.keys)
    }
}

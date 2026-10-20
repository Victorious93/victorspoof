package com.victorious.spoof.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted envelope around the (already allowlisted + refusal-scanned) UNIFIED_BACKUP bundle.
 *
 * Two key sources, both AES-256-GCM with a random 96-bit IV and the envelope header as AAD:
 *  - "pbkdf2-sha256": key derived from a user passphrase (PBKDF2-HMAC-SHA256, 310 000 iterations, 16-byte salt).
 *    Portable: restore on any device with the passphrase.
 *  - "android-keystore": non-exportable key in the Android Keystore. Restores only on this install of this device.
 */
object EncryptedBackup {
    const val FORMAT = "victorspoof-encrypted-backup"
    const val VERSION = 1
    const val PBKDF2_ITERATIONS = 310_000
    const val MIN_PASSPHRASE = 8
    private val enc = Base64.getEncoder(); private val dec = Base64.getDecoder()
    private val rnd = SecureRandom()

    class BackupCryptoException(msg: String) : Exception(msg)

    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKey {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try { return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }

    private fun aad(kdf: String, salt: String?, iter: Int?) = "$FORMAT|$VERSION|$kdf|${salt ?: ""}|${iter ?: 0}".toByteArray()

    private fun seal(plain: String, key: SecretKey, kdf: String, salt: String?, iter: Int?): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (kdf == "android-keystore") c.init(Cipher.ENCRYPT_MODE, key) // Keystore generates the IV itself
        else c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12).also(rnd::nextBytes)))
        c.updateAAD(aad(kdf, salt, iter))
        val ct = c.doFinal(plain.toByteArray())
        return buildJsonObject {
            put("format", FORMAT); put("v", VERSION); put("kdf", kdf)
            salt?.let { put("salt", it) }; iter?.let { put("iter", it) }
            put("iv", enc.encodeToString(c.iv)); put("ct", enc.encodeToString(ct))
        }.toString()
    }

    fun encryptWithPassphrase(plain: String, passphrase: CharArray, iterations: Int = PBKDF2_ITERATIONS): String {
        if (passphrase.size < MIN_PASSPHRASE) throw BackupCryptoException("passphrase must be at least $MIN_PASSPHRASE characters")
        val salt = ByteArray(16).also(rnd::nextBytes)
        val s = enc.encodeToString(salt)
        return seal(plain, deriveKey(passphrase, salt, iterations), "pbkdf2-sha256", s, iterations)
    }

    fun encryptWithKey(plain: String, key: SecretKey) = seal(plain, key, "android-keystore", null, null)

    fun kdfOf(envelope: String): String? = parse(envelope)["kdf"]?.jsonPrimitive?.contentOrNull

    /** [keystoreKey] is used for "android-keystore" envelopes, [passphrase] for "pbkdf2-sha256". */
    fun decrypt(envelope: String, passphrase: CharArray? = null, keystoreKey: SecretKey? = null): String {
        val o = parse(envelope)
        if (o["format"]?.jsonPrimitive?.contentOrNull != FORMAT || o["v"]?.jsonPrimitive?.intOrNull != VERSION)
            throw BackupCryptoException("not a VictorSpoof encrypted backup")
        val kdf = o["kdf"]?.jsonPrimitive?.contentOrNull
        val salt = o["salt"]?.jsonPrimitive?.contentOrNull; val iter = o["iter"]?.jsonPrimitive?.intOrNull
        val iv = dec.decode(o["iv"]?.jsonPrimitive?.contentOrNull ?: throw BackupCryptoException("missing iv"))
        val ct = dec.decode(o["ct"]?.jsonPrimitive?.contentOrNull ?: throw BackupCryptoException("missing ct"))
        val key = when (kdf) {
            "pbkdf2-sha256" -> {
                if (iter == null || iter < 100_000 || salt == null) throw BackupCryptoException("bad kdf params")
                deriveKey(passphrase ?: throw BackupCryptoException("passphrase required"), dec.decode(salt), iter)
            }
            "android-keystore" -> keystoreKey ?: throw BackupCryptoException("this backup can only be restored on the device that made it")
            else -> throw BackupCryptoException("unknown kdf")
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad(kdf, salt, iter))
        return try { String(c.doFinal(ct)) } catch (e: AEADBadTagException) { throw BackupCryptoException("wrong passphrase or corrupted backup") }
    }

    private fun parse(s: String): JsonObject = runCatching { Json.parseToJsonElement(s.trim()) as JsonObject }.getOrElse { throw BackupCryptoException("not JSON") }

    /** Android Keystore-backed AES-256-GCM key (created on first use, never leaves secure hardware where available). */
    object Keystore {
        private const val ALIAS = "victorspoof-backup-v1"
        fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            return g.generateKey()
        }
    }
}

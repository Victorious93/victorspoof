package com.victorious.spoof.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.victorious.spoof.calling.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the signed-in session. The short-lived access JWT stays in memory only; the rotating refresh token is
 * stored encrypted with a non-exportable Android Keystore AES-GCM key. Passwords are never stored.
 */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = ctx.getSharedPreferences("victorspoof_session", Context.MODE_PRIVATE)
    @Volatile var accessToken: String? = null; private set
    private val _accountId = MutableStateFlow(prefs.getString("account_id", null))
    val accountId: StateFlow<String?> = _accountId

    @Synchronized fun save(s: Session) {
        accessToken = s.accessToken
        prefs.edit().putString("account_id", s.accountId).putString("refresh", seal(s.refreshToken)).apply()
        _accountId.value = s.accountId
    }

    @Synchronized fun refreshToken(): String? = prefs.getString("refresh", null)?.let { runCatching { open(it) }.getOrNull() }

    @Synchronized fun clear() {
        accessToken = null; prefs.edit().clear().apply(); _accountId.value = null
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun seal(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val e = Base64.getEncoder()
        return e.encodeToString(c.iv) + "." + e.encodeToString(c.doFinal(s.toByteArray()))
    }
    private fun open(s: String): String {
        val (iv, ct) = s.split('.').map { Base64.getDecoder().decode(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(c.doFinal(ct))
    }
    private companion object { const val ALIAS = "victorspoof-session-v1" }
}

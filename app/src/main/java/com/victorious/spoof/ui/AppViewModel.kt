package com.victorious.spoof.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.spoof.audio.AudioEngine
import com.victorious.spoof.audio.Clip
import com.victorious.spoof.audio.VoicePreset
import com.victorious.spoof.auth.SessionStore
import com.victorious.spoof.bridge.BridgeException
import com.victorious.spoof.calling.BackendClient
import com.victorious.spoof.calling.BackendException
import com.victorious.spoof.calling.CallController
import com.victorious.spoof.calling.CallerIdMode
import com.victorious.spoof.calling.Me
import com.victorious.spoof.config.ConfigBackup
import com.victorious.spoof.config.EncryptedBackup
import com.victorious.spoof.data.AppDatabase
import com.victorious.spoof.data.AppSettings
import com.victorious.spoof.data.ConsentRecord
import com.victorious.spoof.data.PairedNode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject

const val AUP_VERSION = "2026-10-v1"

@HiltViewModel
class AppViewModel @Inject constructor(
    private val db: AppDatabase,
    private val backend: BackendClient,
    private val session: SessionStore,
    val settings: AppSettings,
    val calls: CallController,
    val audio: AudioEngine,
) : ViewModel() {
    val consent = db.consent().observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pairings = db.pairing().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val history = db.pranks().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val accountId = session.accountId
    val me = MutableStateFlow<Me?>(null)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val externalControl = MutableStateFlow(settings.externalControlEnabled)

    private fun explain(e: Exception): String = when (e) {
        is BackendException -> when (e.code) {
            "invalid_credentials" -> "Wrong email or password."
            "too_many_attempts" -> "Too many attempts. Wait a few minutes and try again."
            "email_taken" -> "That email already has an account. Log in instead."
            "weak_password" -> "Use at least 10 characters."
            "invalid_email" -> "That doesn't look like an email address."
            "verify_not_configured" -> "Number verification isn't set up on the server yet (TWILIO_VERIFY_SID missing)."
            "too_many_codes" -> "Too many codes requested. Try again later."
            "wrong_code" -> "That code is not right."
            "no_pending_verification" -> "Request a new code first."
            else -> "Server said: ${e.message}"
        }
        else -> "Could not reach the VictorSpoof server (${e.message})."
    }

    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true; message.value = null
        try { block() } catch (e: Exception) { message.value = explain(e) } finally { busy.value = false }
    }

    fun acceptDisclaimer() = viewModelScope.launch {
        db.consent().upsert((db.consent().get() ?: ConsentRecord()).copy(disclaimerAcceptedAt = System.currentTimeMillis()))
    }
    fun confirmAge() = viewModelScope.launch {
        db.consent().upsert((db.consent().get() ?: ConsentRecord()).copy(ageConfirmedAt = System.currentTimeMillis()))
    }

    // ------------------------------------------------------------ auth
    fun signup(email: String, pw: String) = work { backend.signup(email.trim(), pw); refreshMe() }
    fun login(email: String, pw: String) = work { backend.login(email.trim(), pw); refreshMe() }
    fun logout() = work {
        backend.logout(); me.value = null
        db.consent().get()?.let { db.consent().upsert(it.copy(aupLoggedServerSide = false)) }
    }
    fun refreshMe() = viewModelScope.launch {
        runCatching { backend.me() }.onSuccess { m ->
            me.value = m
            if (m.aupAccepted) db.consent().get()?.let { db.consent().upsert(it.copy(aupVersion = AUP_VERSION, aupLoggedServerSide = true)) }
        }
    }

    /** AUP acceptance is only "done" once the backend has logged it. */
    fun acceptAup() = work {
        db.consent().upsert((db.consent().get() ?: ConsentRecord()).copy(aupVersion = AUP_VERSION, aupAcceptedAt = System.currentTimeMillis()))
        try { backend.acceptAup(AUP_VERSION) } catch (e: Exception) {
            throw IllegalStateException("could not log your acceptance; calls stay locked until it succeeds")
        }
        db.consent().upsert(db.consent().get()!!.copy(aupLoggedServerSide = true))
        refreshMe()
    }

    // ------------------------------------------------------------ own-number verification
    val verifyStep = MutableStateFlow("enter") // enter | code | done
    fun verifyStart(phone: String) = work { backend.verifyStart(phone); verifyStep.value = "code" }
    fun verifyCheck(phone: String, code: String) = work {
        val masked = backend.verifyCheck(phone, code); verifyStep.value = "done"
        message.value = "Verified $masked. You can now use it as your caller ID."; refreshMe()
    }

    // ------------------------------------------------------------ calls, voice, soundboard
    fun startCall(target: String, mode: CallerIdMode, recording: Boolean) = calls.start(calls.confirm(target), mode, recording)
    fun hangUp() = viewModelScope.launch { calls.hangUp() }
    fun setPreset(p: VoicePreset) = audio.setPreset(p)
    fun play(c: Clip) = audio.play(c)

    fun setExternalControl(on: Boolean) { settings.externalControlEnabled = on; externalControl.value = on }
    fun pair(pkg: String, cert: String) = viewModelScope.launch {
        db.pairing().upsert(PairedNode(pkg.trim(), cert.replace(":", "").trim().lowercase(), pkg.trim(), System.currentTimeMillis()))
    }
    fun unpair(n: PairedNode) = viewModelScope.launch { db.pairing().delete(n) }

    // ------------------------------------------------------------ encrypted backup
    val backupText = MutableStateFlow<String?>(null)

    private fun livePrefs(): Map<String, JsonElement> = settings.uiPrefs() + ("ui.voicePreset" to JsonPrimitive(audio.preset.value.id))

    private val writer = object : ConfigBackup.PrefsWriter {
        override fun writeUiPref(key: String, value: JsonPrimitive) {
            if (key == "ui.voicePreset") audio.setPreset(VoicePreset.fromId(value.content)) else settings.backupWriter.writeUiPref(key, value)
        }
        override fun forceExternalControlOff() = settings.backupWriter.forceExternalControlOff()
    }

    private fun clearBundle(): String {
        val entry = ConfigBackup.exportEntry(livePrefs(), "local", key = null, clearPayload = true)
        return buildJsonObject {
            put("version", ConfigBackup.BUNDLE_VERSION); put("createdAtMs", System.currentTimeMillis())
            put("sourceNodeRef", "local"); put("wrapping", "none"); putJsonArray("entries") { add(entry) }
        }.toString()
    }

    /** [passphrase] null = Android Keystore key (this device only). The inner bundle is allowlisted + refusal-scanned. */
    fun exportEncrypted(passphrase: CharArray?) = viewModelScope.launch {
        backupText.value = try {
            val plain = clearBundle()
            withContext(Dispatchers.Default) {
                if (passphrase != null) EncryptedBackup.encryptWithPassphrase(plain, passphrase) else EncryptedBackup.encryptWithKey(plain, EncryptedBackup.Keystore.key())
            }
        } catch (e: BridgeException) { "Export refused: ${e.message}" }
        catch (e: EncryptedBackup.BackupCryptoException) { "Export failed: ${e.message}" }
        finally { passphrase?.fill('\u0000') }
    }

    private fun decrypt(envelope: String, passphrase: CharArray?): String {
        val kdf = EncryptedBackup.kdfOf(envelope)
        return EncryptedBackup.decrypt(envelope, passphrase, if (kdf == "android-keystore") EncryptedBackup.Keystore.key() else null)
    }

    /** Dry run: decrypts and returns the diff text; writes nothing. */
    suspend fun previewImport(envelope: String, passphrase: CharArray?): String = withContext(Dispatchers.Default) {
        try {
            ConfigBackup.import(ConfigBackup.payloadFromBundle(decrypt(envelope, passphrase), null), livePrefs(), dryRun = true, confirmation = null, writer = writer).toString()
        } catch (e: BridgeException) { "Import refused (${e.error.wire}): ${e.message}" }
        catch (e: EncryptedBackup.BackupCryptoException) { "Can't decrypt: ${e.message}" }
    }

    /** Called ONLY from the on-device confirmation dialog. */
    suspend fun applyImportConfirmed(envelope: String, passphrase: CharArray?): String = withContext(Dispatchers.Default) {
        try {
            val r = ConfigBackup.import(ConfigBackup.payloadFromBundle(decrypt(envelope, passphrase), null), livePrefs(), dryRun = false,
                confirmation = ConfigBackup.confirmLocally(), writer = writer, conflictPolicy = "overwrite")
            externalControl.value = settings.externalControlEnabled
            r.toString()
        } catch (e: BridgeException) { "Import refused (${e.error.wire}): ${e.message}" }
        catch (e: EncryptedBackup.BackupCryptoException) { "Can't decrypt: ${e.message}" }
    }
}

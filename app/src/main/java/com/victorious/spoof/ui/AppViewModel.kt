package com.victorious.spoof.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victorious.spoof.calling.BackendClient
import com.victorious.spoof.calling.CallController
import com.victorious.spoof.calling.CallerIdMode
import com.victorious.spoof.data.AppDatabase
import com.victorious.spoof.data.AppSettings
import com.victorious.spoof.data.ConsentRecord
import com.victorious.spoof.data.PairedNode
import com.victorious.spoof.config.ConfigBackup
import com.victorious.spoof.bridge.BridgeException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val AUP_VERSION = "2026-10-v1"

@HiltViewModel
class AppViewModel @Inject constructor(
    private val db: AppDatabase,
    private val backend: BackendClient,
    val settings: AppSettings,
    val calls: CallController,
) : ViewModel() {
    val consent = db.consent().observe().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pairings = db.pairing().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val history = db.pranks().observe().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val message = MutableStateFlow<String?>(null)
    val externalControl = MutableStateFlow(settings.externalControlEnabled)

    fun acceptDisclaimer() = viewModelScope.launch {
        db.consent().upsert((db.consent().get() ?: ConsentRecord()).copy(disclaimerAcceptedAt = System.currentTimeMillis()))
    }
    fun confirmAge() = viewModelScope.launch {
        db.consent().upsert((db.consent().get() ?: ConsentRecord()).copy(ageConfirmedAt = System.currentTimeMillis()))
    }
    /** AUP acceptance is only "done" once the backend has logged it. */
    fun acceptAup() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        db.consent().upsert((db.consent().get() ?: ConsentRecord()).copy(aupVersion = AUP_VERSION, aupAcceptedAt = now))
        try {
            val id = settings.accountId ?: backend.createAccount().also { settings.accountId = it }
            backend.acceptAup(id, AUP_VERSION)
            db.consent().upsert(db.consent().get()!!.copy(aupLoggedServerSide = true))
        } catch (e: Exception) {
            message.value = "Could not reach the VictorSpoof server to log your acceptance. Calls stay locked until it succeeds."
        }
    }

    fun startCall(target: String, mode: CallerIdMode, recording: Boolean, voice: String) =
        calls.start(calls.confirm(target), mode, recording, voice)

    fun setExternalControl(on: Boolean) { settings.externalControlEnabled = on; externalControl.value = on }
    fun pair(pkg: String, cert: String) = viewModelScope.launch {
        db.pairing().upsert(PairedNode(pkg.trim(), cert.replace(":", "").trim().lowercase(), pkg.trim(), System.currentTimeMillis()))
    }
    fun unpair(n: PairedNode) = viewModelScope.launch { db.pairing().delete(n) }

    val backupText = MutableStateFlow<String?>(null)

    /** Local export (Settings button). Dev bundle with wrapping=none until Keystore wrapping lands. */
    fun exportConfig() {
        backupText.value = try {
            val entry = ConfigBackup.exportEntry(settings.uiPrefs(), "local", key = null, clearPayload = true)
            buildJsonObject {
                put("version", ConfigBackup.BUNDLE_VERSION); put("createdAtMs", System.currentTimeMillis())
                put("sourceNodeRef", "local"); put("wrapping", "none"); putJsonArray("entries") { add(entry) }
            }.toString()
        } catch (e: BridgeException) { "Export refused: ${e.message}" }
    }

    /** Dry run: returns the diff text; writes nothing. */
    fun previewImport(bundle: String): String = try {
        ConfigBackup.import(ConfigBackup.payloadFromBundle(bundle, null), settings.uiPrefs(), dryRun = true, confirmation = null, writer = settings.backupWriter).toString()
    } catch (e: BridgeException) { "Import refused (${e.error.wire}): ${e.message}" }

    /** Called ONLY from the on-device confirmation dialog. */
    fun applyImportConfirmed(bundle: String): String = try {
        val r = ConfigBackup.import(ConfigBackup.payloadFromBundle(bundle, null), settings.uiPrefs(), dryRun = false,
            confirmation = ConfigBackup.confirmLocally(), writer = settings.backupWriter)
        externalControl.value = settings.externalControlEnabled
        r.toString()
    } catch (e: BridgeException) { "Import refused (${e.error.wire}): ${e.message}" }
}

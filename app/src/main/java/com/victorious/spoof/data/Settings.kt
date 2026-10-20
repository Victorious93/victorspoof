package com.victorious.spoof.data

import android.content.Context
import com.victorious.spoof.bridge.BridgeSettings
import com.victorious.spoof.bridge.NodeIds
import com.victorious.spoof.config.ConfigBackup
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Local, per-device settings. No Twilio credentials or pool numbers are ever stored here. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext ctx: Context) : BridgeSettings {
    private val prefs = ctx.getSharedPreferences("victorspoof", Context.MODE_PRIVATE)
    private val sessionPrefs = ctx.getSharedPreferences("victorspoof_session", Context.MODE_PRIVATE)

    val nodeId: String
        get() = prefs.getString("node_id", null)?.takeIf(NodeIds::isValid)
            ?: NodeIds.generate().also { prefs.edit().putString("node_id", it).apply() }

    /** OFF by default. */
    override var externalControlEnabled: Boolean
        get() = prefs.getBoolean("external_control", false)
        set(v) { prefs.edit().putBoolean("external_control", v).apply() }

    override fun isCapabilityEnabled(op: String) = prefs.getBoolean("cap_$op", true)
    fun setCapabilityEnabled(op: String, on: Boolean) = prefs.edit().putBoolean("cap_$op", on).apply()

    /** Signed-in account (owned by SessionStore); read-only here, used for the bridge "onboarded" flag. */
    val accountId: String? get() = sessionPrefs.getString("account_id", null)

    /** Current non-secret UI prefs (allowlisted keys only). */
    fun uiPrefs(): Map<String, JsonElement> = ConfigBackup.ALLOWLIST.keys.mapNotNull { k ->
        prefs.getString("uipref_$k", null)?.let { k to decodePref(it) }
    }.toMap()

    /** ConfigBackup writer: can only write allowlisted UI prefs and force external control OFF. */
    val backupWriter = object : ConfigBackup.PrefsWriter {
        override fun writeUiPref(key: String, value: JsonPrimitive) {
            require(key in ConfigBackup.ALLOWLIST) { "not an allowlisted pref" }
            prefs.edit().putString("uipref_$key", value.toString()).apply()
        }
        override fun forceExternalControlOff() { externalControlEnabled = false }
    }

    private fun decodePref(raw: String): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(raw)
}

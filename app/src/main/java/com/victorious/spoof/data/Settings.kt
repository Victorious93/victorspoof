package com.victorious.spoof.data

import android.content.Context
import com.victorious.spoof.bridge.BridgeSettings
import com.victorious.spoof.bridge.NodeIds
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Local, per-device settings. No Twilio credentials or pool numbers are ever stored here. */
@Singleton
class AppSettings @Inject constructor(@ApplicationContext ctx: Context) : BridgeSettings {
    private val prefs = ctx.getSharedPreferences("victorspoof", Context.MODE_PRIVATE)

    val nodeId: String
        get() = prefs.getString("node_id", null)?.takeIf(NodeIds::isValid)
            ?: NodeIds.generate().also { prefs.edit().putString("node_id", it).apply() }

    /** OFF by default. */
    override var externalControlEnabled: Boolean
        get() = prefs.getBoolean("external_control", false)
        set(v) { prefs.edit().putBoolean("external_control", v).apply() }

    override fun isCapabilityEnabled(op: String) = prefs.getBoolean("cap_$op", true)
    fun setCapabilityEnabled(op: String, on: Boolean) = prefs.edit().putBoolean("cap_$op", on).apply()

    var accountId: String?
        get() = prefs.getString("account_id", null)
        set(v) { prefs.edit().putString("account_id", v).apply() }
}

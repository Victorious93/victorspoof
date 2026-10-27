package com.victorious.spoof.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Platform seams so the auth/fallback logic is unit-testable. */
interface FleetThemeSource {
    /** Package that owns the provider authority, or null if not installed. */
    fun providerPackage(): String?
    /** SHA-256 of each signing cert of [pkg] (empty if unknown). */
    fun signingCerts(pkg: String): List<String>
    /** Value of the `json` column of the single row, or null. May throw SecurityException. */
    fun queryJson(): String?
}

/**
 * READ-ONLY client for VictorLaunch's FLEET_THEME (schemaVersion 1). Never inserts/updates/deletes.
 * Authenticates the provider by package + pinned signing cert before trusting any data.
 */
class FleetThemeResolver(private val source: FleetThemeSource, private val pins: Set<String> = FleetThemeContract.PINNED_CERTS) {
    fun resolve(): FleetThemeState {
        val pkg = runCatching { source.providerPackage() }.getOrNull()
            ?: return FleetThemeState.BuiltIn("provider missing")
        if (pkg != FleetThemeContract.PROVIDER_PACKAGE) return FleetThemeState.BuiltIn("provider package mismatch: $pkg")
        val certs = runCatching { source.signingCerts(pkg) }.getOrDefault(emptyList())
        if (!FleetThemeContract.isPinned(certs, pins)) return FleetThemeState.BuiltIn("provider cert not pinned")
        val raw = try {
            source.queryJson()
        } catch (e: SecurityException) {
            return FleetThemeState.BuiltIn("SecurityException: VictorSpoof not pinned in VictorLaunch")
        } catch (e: Exception) {
            return FleetThemeState.BuiltIn("query failed: ${e.javaClass.simpleName}")
        }
        val snap = FleetThemeCodec.decode(raw) ?: return FleetThemeState.BuiltIn("invalid or unsupported snapshot")
        return FleetThemeState.Fleet(snap)
    }
}

object FleetThemeClient {
    private const val TAG = "FleetTheme"
    private val _state = MutableStateFlow<FleetThemeState>(FleetThemeState.BuiltIn("not loaded"))
    val state: StateFlow<FleetThemeState> = _state.asStateFlow()
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var appContext: Context? = null

    fun start(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        runCatching {
            ctx.contentResolver.registerContentObserver(
                Uri.parse(FleetThemeContract.URI), false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = refresh(ctx)
                },
            )
        }.onFailure { Log.w(TAG, "observer registration failed", it) }
        refresh(ctx)
    }

    /** Always re-queries the provider; callers' payloads are never used. */
    fun refresh(context: Context) {
        val ctx = context.applicationContext
        io.execute {
            val s = FleetThemeResolver(AndroidFleetThemeSource(ctx)).resolve()
            if (s is FleetThemeState.BuiltIn) Log.i(TAG, "built-in theme: ${s.reason}")
            _state.value = s
        }
    }
}

/** Wake-up only: extras are ignored and the provider is re-queried. */
class FleetThemeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FleetThemeContract.ACTION_CHANGED) return
        FleetThemeClient.refresh(context)
    }
}

internal class AndroidFleetThemeSource(private val ctx: Context) : FleetThemeSource {
    private val uri = Uri.parse(FleetThemeContract.URI)

    override fun providerPackage(): String? =
        ctx.packageManager.resolveContentProvider(FleetThemeContract.AUTHORITY, 0)?.packageName

    @Suppress("DEPRECATION")
    override fun signingCerts(pkg: String): List<String> {
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
            info.apkContentsSigners
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        } ?: return emptyList()
        return sigs.map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    override fun queryJson(): String? =
        ctx.contentResolver.query(uri, arrayOf("json"), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return null
            val i = c.getColumnIndex("json")
            if (i < 0) null else c.getString(i)
        }
}

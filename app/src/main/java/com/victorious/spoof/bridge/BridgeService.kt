package com.victorious.spoof.bridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import com.victorious.spoof.MainActivity
import com.victorious.spoof.data.AppDatabase
import com.victorious.spoof.data.AppSettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.json.*
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Fleet bridge (EXTERNAL_NODE). Bound Messenger, action com.victorious.spoof.action.BRIDGE.
 * what=1 request / what=2 response, NDJSON in the "payload" string extra.
 * Caller identity comes from Message.sendingUid ONLY.
 */
@AndroidEntryPoint
class BridgeService : Service() {
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var db: AppDatabase

    private val replay = ReplayGuard()
    private lateinit var dispatcher: BridgeDispatcher
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        val pairing = object : PairingStore {
            override fun isPaired(caller: CallerIdentity) = db.pairing().allBlocking().any {
                it.packageName == caller.packageName &&
                    StaticPairingStore.normalizeCert(it.certSha256) == StaticPairingStore.normalizeCert(caller.certSha256)
            }
        }
        dispatcher = BridgeDispatcher(settings.nodeId, pairing, settings, replay, AndroidRemoteOpExecutor(this, settings, db))
        val worker = android.os.HandlerThread("bridge").apply { start() }
        messenger = Messenger(object : Handler(worker.looper) {
            override fun handleMessage(msg: Message) {
                if (msg.what != BridgeProtocol.WHAT_REQUEST) return
                val uid = msg.sendingUid
                val payload = msg.data?.getString(BridgeProtocol.KEY_PAYLOAD) ?: ""
                val out = dispatcher.handlePayload(payload, resolveCaller(uid))
                val reply = Message.obtain(null, BridgeProtocol.WHAT_RESPONSE).apply {
                    data = Bundle().apply { putString(BridgeProtocol.KEY_PAYLOAD, out) }
                }
                try { msg.replyTo?.send(reply) } catch (e: RemoteException) { Log.w("Bridge", "reply failed", e) }
            }
        })
    }

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == BridgeProtocol.ACTION_BRIDGE) messenger.binder else null

    /** Resolve (package, signing cert SHA-256) from the kernel-provided UID. Shared UIDs are rejected. */
    private fun resolveCaller(uid: Int): CallerIdentity? {
        if (uid < 0) return null
        val pkgs = packageManager.getPackagesForUid(uid) ?: return null
        if (pkgs.size != 1) return null
        val pkg = pkgs[0]
        val certs: List<ByteArray> = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val info = packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
                if (info.hasMultipleSigners()) return null
                info.signingCertificateHistory.map { it.toByteArray() }.takeLast(1)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.map { it.toByteArray() } ?: return null
            }
        } catch (e: PackageManager.NameNotFoundException) { return null }
        if (certs.size != 1) return null
        val sha = MessageDigest.getInstance("SHA-256").digest(certs[0]).joinToString("") { "%02x".format(it) }
        return CallerIdentity(pkg, sha)
    }
}

/** Read-only ops + opening the UI. There is no call-control capability here by design. */
class AndroidRemoteOpExecutor(private val ctx: Context, private val settings: AppSettings, private val db: AppDatabase) : RemoteOpExecutor {
    override fun status() = buildJsonObject {
        put("nodeId", settings.nodeId)
        put("fleetClass", BridgeProtocol.FLEET_CLASS)
        put("externalControlEnabled", settings.externalControlEnabled)
        put("onboarded", settings.accountId != null)
    }
    override fun nodeStatus(): NodeStatusSnapshot {
        val healthy = runCatching { db.openHelper.readableDatabase; true }.getOrDefault(false)
        return NodeStatusSnapshot(settings.nodeId, ctx.packageName, System.currentTimeMillis(),
            if (healthy) HealthState.OK else HealthState.DEGRADED,
            if (settings.externalControlEnabled) "external control on" else "external control off")
    }
    /** Labels only. Pool numbers stay on the backend. */
    override fun poolList() = buildJsonArray { add("VictorSpoof pool (labels are chosen by the server)") }
    override fun usage() = buildJsonObject {
        val dayStart = System.currentTimeMillis() - 24 * 3600_000L
        put("usedLast24h", db.pranks().countSinceBlocking(dayStart))
        put("limitPerDay", 10); put("limitPerTarget", 3); put("maxCallSeconds", 120)
    }
    override fun openUi(screen: String?): JsonObject {
        val allowed = setOf("home", "help", "soundboard", "voice", "settings")
        val s = screen?.takeIf { it in allowed } ?: "home"
        Handler(Looper.getMainLooper()).post {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, s).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return buildJsonObject { put("opened", s) }
    }
}

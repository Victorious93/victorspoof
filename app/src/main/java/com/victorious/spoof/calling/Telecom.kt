package com.victorious.spoof.calling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.victorious.spoof.MainActivity
import com.victorious.spoof.R

/**
 * Self-managed Telecom integration (MANAGE_OWN_CALLS). VictorSpoof is NEVER the default dialer and never places
 * carrier calls: the ConnectionService only lets Android know a VoIP call is in progress so audio focus, Bluetooth
 * headsets and "a real call comes in" are handled correctly.
 */
object Telecom {
    private const val TAG = "VsTelecom"
    private fun handle(ctx: Context) = PhoneAccountHandle(ComponentName(ctx, VsConnectionService::class.java), "victorspoof")

    fun register(ctx: Context): Boolean = runCatching {
        val tm = ctx.getSystemService(TelecomManager::class.java)
        tm.registerPhoneAccount(PhoneAccount.builder(handle(ctx), "VictorSpoof")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED).build())
        true
    }.getOrElse { Log.w(TAG, "phone account registration failed", it); false }

    /** Asks Telecom for a self-managed outgoing connection. [onReady] runs when Telecom grants it (or immediately on fallback). */
    fun placeCall(ctx: Context, maskedTarget: String, onReady: (VsConnection?, Boolean) -> Unit) {
        VsConnectionService.pending = onReady
        try {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(ctx)) }
            if (!register(ctx)) throw IllegalStateException("no phone account")
            tm.placeCall(Uri.fromParts("victorspoof", maskedTarget, null), extras)
            // Some OEM builds never call back for self-managed accounts: fall back after 4 s.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                VsConnectionService.pending?.let { VsConnectionService.pending = null; it(null, true) }
            }, 4_000)
        } catch (e: SecurityException) {
            Log.w(TAG, "MANAGE_OWN_CALLS not granted, continuing without ConnectionService", e)
            VsConnectionService.pending = null
            onReady(null, true)
        } catch (e: Exception) {
            Log.w(TAG, "Telecom unavailable, continuing without ConnectionService", e)
            VsConnectionService.pending = null
            onReady(null, true)
        }
    }
}

class VsConnection : Connection() {
    var onHangUpRequested: (() -> Unit)? = null
    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        connectionCapabilities = CAPABILITY_MUTE
    }
    override fun onDisconnect() { onHangUpRequested?.invoke() }
    override fun onAbort() { onHangUpRequested?.invoke() }
    fun ended() { setDisconnected(DisconnectCause(DisconnectCause.LOCAL)); destroy() }
}

class VsConnectionService : ConnectionService() {
    companion object { @Volatile var pending: ((VsConnection?, Boolean) -> Unit)? = null }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection {
        val c = VsConnection().apply { setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED); setDialing() }
        pending?.invoke(c, true); pending = null
        return c
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        // e.g. a carrier/emergency call is active. Don't start the prank call.
        pending?.invoke(null, false); pending = null
    }
}

/** Ongoing-call notification with the remaining time and a Hang up action (foreground service, type microphone). */
class CallForegroundService : Service() {
    companion object {
        const val CHANNEL = "calls"
        const val ACTION_HANGUP = "com.victorious.spoof.HANGUP"
        const val ACTION_UPDATE = "com.victorious.spoof.UPDATE"
        const val EXTRA_TEXT = "text"
        @Volatile var onHangUp: (() -> Unit)? = null

        fun start(ctx: Context, text: String) = runCatching {
            ctx.startForegroundService(Intent(ctx, CallForegroundService::class.java).putExtra(EXTRA_TEXT, text))
        }
        fun update(ctx: Context, text: String) = runCatching {
            ctx.startService(Intent(ctx, CallForegroundService::class.java).setAction(ACTION_UPDATE).putExtra(EXTRA_TEXT, text))
        }
        fun stop(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, CallForegroundService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) { onHangUp?.invoke(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Prank calls", NotificationManager.IMPORTANCE_LOW))
        val n = notification(intent?.getStringExtra(EXTRA_TEXT) ?: "Prank call in progress")
        if (intent?.action == ACTION_UPDATE) { nm.notify(1, n); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try { ServiceCompat.startForeground(this, 1, n, type) } catch (e: Exception) { Log.w("VsCallFgs", "startForeground failed", e); stopSelf() }
        return START_NOT_STICKY
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val hang = PendingIntent.getService(this, 1, Intent(this, CallForegroundService::class.java).setAction(ACTION_HANGUP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_call).setContentTitle("VictorSpoof call").setContentText(text)
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_CALL).setContentIntent(open)
            .addAction(0, "Hang up + reveal", hang).setOnlyAlertOnce(true).build()
    }
}

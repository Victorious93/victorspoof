package com.victorious.spoof.calling

import android.content.Context
import com.victorious.spoof.audio.AudioEngine
import com.victorious.spoof.audio.Clip
import com.victorious.spoof.data.AppDatabase
import com.victorious.spoof.data.PrankRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Hard client-side ceiling. The server's Dial timeLimit enforces the same cap independently. */
const val MAX_CALL_SECONDS = 120

/**
 * The ONLY path that starts a call. It requires a [UserConfirmation], which is created solely by the
 * in-app confirmation dialog. The fleet bridge has no reference to this class. There is no auto-dial and no retry.
 */
@Singleton
class CallController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val backend: BackendClient,
    private val provider: CallProvider,
    private val audio: AudioEngine,
    private val db: AppDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow("idle")
    val state: StateFlow<String> = _state
    private val _remaining = MutableStateFlow<Int?>(null)
    val remainingSeconds: StateFlow<Int?> = _remaining
    private var active: ActiveCall? = null
    private var connection: VsConnection? = null
    private var timer: Job? = null
    @Volatile private var ending = false

    fun confirm(target: String) = UserConfirmation(target, System.currentTimeMillis())

    fun start(confirmation: UserConfirmation, mode: CallerIdMode, recording: Boolean) {
        if (active != null || _state.value in setOf("authorizing", "ringing", "connected")) { _state.value = "a call is already in progress"; return }
        if (System.currentTimeMillis() - confirmation.confirmedAtMs > 30_000) { _state.value = "confirmation expired"; return }
        if (LocalPrecheck.obviouslyBlocked(confirmation.target)) { _state.value = "blocked number"; return }
        ending = false
        audio.prepareSpokenSting()
        scope.launch {
            try {
                _state.value = "authorizing"
                val auth = backend.authorize(confirmation.target, mode, recording)
                if (!auth.allowed || auth.ticket == null) { _state.value = "denied: ${auth.reason}"; return@launch }
                val token = try { backend.voiceToken() } catch (e: BackendException) {
                    _state.value = if (e.code == "twilio_not_configured") "error: calling is not set up on the server yet (Twilio env vars missing" +
                        (if (e.missing.isNotEmpty()) ": ${e.missing.joinToString()}" else "") + ")" else "error: ${e.message}"
                    return@launch
                }
                val max = (auth.maxCallSeconds ?: MAX_CALL_SECONDS).coerceIn(1, MAX_CALL_SECONDS)
                Telecom.placeCall(ctx, mask(confirmation.target)) { conn, allowed ->
                    scope.launch {
                        if (allowed) begin(conn, token, auth, max, confirmation.target, recording)
                        else _state.value = "error: Android refused the call (is another call active?)"
                    }
                }
            } catch (e: Exception) {
                _state.value = "error: ${e.message}"
            }
        }
    }

    private suspend fun begin(conn: VsConnection?, token: String, auth: CallAuthorization, max: Int, target: String, recording: Boolean) {
        connection = conn
        conn?.onHangUpRequested = { scope.launch { hangUp() } }
        CallForegroundService.onHangUp = { scope.launch { hangUp() } }
        CallForegroundService.start(ctx, "Prank call • ${max}s max")
        val started = System.currentTimeMillis()
        active = provider.connect(token, auth) { ev ->
            _state.value = ev
            if (ev == "connected") conn?.setActive()
            if (ev == "ended" || ev.startsWith("failed")) scope.launch { finish(reveal = ev == "ended") }
        }
        timer = scope.launch {
            for (left in max downTo 1) {
                _remaining.value = left
                if (left % 10 == 0) CallForegroundService.update(ctx, "Prank call • ${left}s left")
                // Leave room for the reveal sting so it always plays before the hard cap.
                if (left * 1000L <= audio.stingDurationMs() + 1500 && !ending) scope.launch { hangUp() }
                delay(1000)
            }
            finish(reveal = true)
        }
        db.pranks().insert(PrankRecord(targetMasked = mask(target), startedAt = started, durationSec = max, recorded = recording))
    }

    /** Plays the "You've been VictorSpoofed!" sting into the call, then disconnects. */
    suspend fun hangUp() {
        if (ending) return
        ending = true
        if (active != null && _state.value == "connected") {
            audio.mixer.stopAll(); audio.play(Clip.SPOOFED_STING)
            _state.value = "revealing"
            delay(audio.stingDurationMs() + 300)
        }
        finish(reveal = true)
    }

    private fun finish(reveal: Boolean) {
        timer?.cancel(); timer = null
        active?.hangUp(); active = null
        connection?.ended(); connection = null
        audio.mixer.stopAll()
        CallForegroundService.stop(ctx)
        _remaining.value = null
        if (reveal) _state.value = "reveal" else if (!_state.value.startsWith("failed")) _state.value = "idle"
    }

    fun reset() { if (active == null) _state.value = "idle" }

    private fun mask(t: String) = t.filter(Char::isDigit).let { if (it.length > 4) "•••" + it.takeLast(4) else "•••" }
}

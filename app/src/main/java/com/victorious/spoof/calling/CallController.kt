package com.victorious.spoof.calling

import com.victorious.spoof.data.AppDatabase
import com.victorious.spoof.data.AppSettings
import com.victorious.spoof.data.PrankRecord
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

/**
 * The ONLY path that starts a call. It requires a [UserConfirmation], which is created solely by the
 * in-app confirmation dialog. The fleet bridge has no reference to this class.
 */
@Singleton
class CallController @Inject constructor(
    private val backend: BackendClient,
    private val provider: CallProvider,
    private val settings: AppSettings,
    private val db: AppDatabase,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow("idle")
    val state: StateFlow<String> = _state
    private var active: ActiveCall? = null
    private var timer: Job? = null

    fun confirm(target: String) = UserConfirmation(target, System.currentTimeMillis())

    fun start(confirmation: UserConfirmation, mode: CallerIdMode, recording: Boolean, voicePreset: String) {
        if (System.currentTimeMillis() - confirmation.confirmedAtMs > 30_000) { _state.value = "confirmation expired"; return }
        if (LocalPrecheck.obviouslyBlocked(confirmation.target)) { _state.value = "blocked number"; return }
        scope.launch {
            try {
                val account = settings.accountId ?: run { _state.value = "finish onboarding first"; return@launch }
                _state.value = "authorizing"
                val auth = backend.authorize(account, confirmation.target, mode, recording)
                if (!auth.allowed) { _state.value = "denied: ${auth.reason}"; return@launch }
                val token = backend.voiceToken(account)
                val started = System.currentTimeMillis()
                active = provider.connect(token, auth, voicePreset) { _state.value = it }
                val max = (auth.maxCallSeconds ?: 120).coerceAtMost(120)
                timer = scope.launch { delay(max * 1000L); hangUp() }
                db.pranks().insert(PrankRecord(targetMasked = mask(confirmation.target), startedAt = started, durationSec = max, recorded = recording))
            } catch (e: Exception) {
                _state.value = "error: ${e.message}"
            }
        }
    }

    fun hangUp() { timer?.cancel(); active?.hangUp(); active = null; _state.value = "reveal" }

    private fun mask(t: String) = t.filter(Char::isDigit).let { if (it.length > 4) "•••" + it.takeLast(4) else "•••" }
}

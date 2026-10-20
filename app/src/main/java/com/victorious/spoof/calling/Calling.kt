package com.victorious.spoof.calling

import android.content.Context
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.ConnectOptions
import com.twilio.voice.Voice
import com.victorious.spoof.audio.AudioEngine
import com.victorious.spoof.auth.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** The app only ever chooses a MODE. The backend picks the actual caller-ID number. */
enum class CallerIdMode { POOL, OWN_VERIFIED }

@Serializable data class CallerIdChoice(val e164: String, val displayName: String? = null)
@Serializable data class CallAuthorization(
    val allowed: Boolean, val reason: String? = null, val callerId: CallerIdChoice? = null,
    val target: String? = null, val maxCallSeconds: Int? = null, val recordingAnnouncementRequired: Boolean = false,
    val ticket: String? = null,
)
@Serializable data class Session(val accountId: String, val accessToken: String, val refreshToken: String, val expiresIn: Long)
@Serializable data class Me(val accountId: String, val aupAccepted: Boolean, val verifiedNumberMasked: String? = null, val callerIdOptions: List<String> = listOf("POOL"))
@Serializable private data class AuthorizeReq(val target: String, val mode: CallerIdMode, val recording: Boolean)
@Serializable private data class TokenResp(val token: String, val ttlSeconds: Int)
@Serializable private data class SignupReq(val email: String, val password: String, val ageAttested18: Boolean, val disclaimerAccepted: Boolean)
@Serializable private data class LoginReq(val email: String, val password: String)
@Serializable private data class RefreshReq(val refreshToken: String)
@Serializable private data class AupReq(val version: String)
@Serializable private data class VerifyStartReq(val phone: String)
@Serializable private data class VerifyCheckReq(val phone: String, val code: String)
@Serializable private data class ErrorResp(val error: String = "unknown", val missing: List<String> = emptyList())

/** Server error with the backend's machine-readable code (e.g. twilio_not_configured, too_many_codes). */
class BackendException(val status: Int, val code: String, val missing: List<String> = emptyList()) :
    Exception(if (missing.isEmpty()) "$code ($status)" else "$code ($status): missing ${missing.joinToString()}")

interface BackendClient {
    suspend fun signup(email: String, password: String): Session
    suspend fun login(email: String, password: String): Session
    suspend fun logout()
    suspend fun me(): Me
    suspend fun acceptAup(version: String)
    suspend fun authorize(target: String, mode: CallerIdMode, recording: Boolean): CallAuthorization
    /** Short-lived Twilio access token. Twilio credentials never leave the backend. */
    suspend fun voiceToken(): String
    suspend fun verifyStart(phone: String)
    /** Returns the masked verified number. */
    suspend fun verifyCheck(phone: String, code: String): String
}

class HttpBackendClient(private val base: String, private val session: SessionStore) : BackendClient {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun raw(method: String, path: String, body: String?, bearer: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/json")
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (method == "POST") { c.doOutput = true; c.outputStream.use { it.write((body ?: "{}").toByteArray()) } }
            val code = c.responseCode
            code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
        } finally { c.disconnect() }
    }

    /** Authenticated call; on 401 refreshes once (refresh tokens rotate) and retries. */
    private suspend fun authed(method: String, path: String, body: String? = null): Pair<Int, String> {
        val access = session.accessToken ?: refresh() ?: throw BackendException(401, "not_signed_in")
        val r = raw(method, path, body, access)
        if (r.first != 401) return r
        val fresh = refresh() ?: throw BackendException(401, "session_expired")
        return raw(method, path, body, fresh)
    }

    private suspend fun refresh(): String? {
        val rt = session.refreshToken() ?: return null
        val (code, body) = raw("POST", "/v1/auth/refresh", json.encodeToString(RefreshReq(rt)), null)
        if (code != 200) { if (code == 401) session.clear(); return null }
        return json.decodeFromString<Session>(body).also(session::save).accessToken
    }

    private fun fail(code: Int, body: String): Nothing {
        val e = runCatching { json.decodeFromString<ErrorResp>(body) }.getOrDefault(ErrorResp())
        throw BackendException(code, e.error, e.missing)
    }
    private fun ok(r: Pair<Int, String>, vararg codes: Int = intArrayOf(200)): String = if (r.first in codes) r.second else fail(r.first, r.second)

    override suspend fun signup(email: String, password: String): Session =
        json.decodeFromString<Session>(ok(raw("POST", "/v1/auth/signup", json.encodeToString(SignupReq(email, password, true, true)), null), 201)).also(session::save)
    override suspend fun login(email: String, password: String): Session =
        json.decodeFromString<Session>(ok(raw("POST", "/v1/auth/login", json.encodeToString(LoginReq(email, password)), null))).also(session::save)
    override suspend fun logout() { runCatching { authed("POST", "/v1/auth/logout") }; session.clear() }
    override suspend fun me(): Me = json.decodeFromString(ok(authed("GET", "/v1/me")))
    override suspend fun acceptAup(version: String) { ok(authed("POST", "/v1/aup/accept", json.encodeToString(AupReq(version)))) }
    override suspend fun authorize(target: String, mode: CallerIdMode, recording: Boolean): CallAuthorization {
        val (_, body) = authed("POST", "/v1/calls/authorize", json.encodeToString(AuthorizeReq(target, mode, recording)))
        return runCatching { json.decodeFromString<CallAuthorization>(body) }.getOrElse { CallAuthorization(false, "backend_unreachable") }
    }
    override suspend fun voiceToken(): String = json.decodeFromString<TokenResp>(ok(authed("POST", "/v1/twilio/token"))).token
    override suspend fun verifyStart(phone: String) { ok(authed("POST", "/v1/verify/start", json.encodeToString(VerifyStartReq(phone)))) }
    override suspend fun verifyCheck(phone: String, code: String): String {
        val b = ok(authed("POST", "/v1/verify/check", json.encodeToString(VerifyCheckReq(phone, code))))
        return Regex("\"verifiedNumberMasked\"\\s*:\\s*\"([^\"]+)\"").find(b)?.groupValues?.get(1) ?: "verified"
    }
}

interface CallProvider {
    fun connect(token: String, auth: CallAuthorization, onEvent: (String) -> Unit): ActiveCall
}
interface ActiveCall { fun hangUp(); fun mute(on: Boolean) {} }

/**
 * Twilio Voice SDK (primary). Only the one-time ticket travels in the connect params; the TwiML webhook looks up
 * target, caller ID and time limit server-side. The custom [com.victorious.spoof.audio.VsAudioDevice] carries the
 * voice changer and soundboard into the outgoing audio.
 */
class TwilioCallProvider(private val ctx: Context, private val audio: AudioEngine) : CallProvider {
    override fun connect(token: String, auth: CallAuthorization, onEvent: (String) -> Unit): ActiveCall {
        // Must be set while no call is active; the SDK throws otherwise.
        runCatching { Voice.setAudioDevice(audio.device()) }.onFailure { onEvent("voice changer unavailable: ${it.message}") }
        val options = ConnectOptions.Builder(token).params(mapOf("ticket" to (auth.ticket ?: ""))).build()
        val call = Voice.connect(ctx, options, object : Call.Listener {
            override fun onConnectFailure(call: Call, e: CallException) = onEvent("failed: ${e.message}")
            override fun onRinging(call: Call) = onEvent("ringing")
            override fun onConnected(call: Call) = onEvent("connected")
            override fun onReconnecting(call: Call, e: CallException) = onEvent("reconnecting")
            override fun onReconnected(call: Call) = onEvent("connected")
            override fun onDisconnected(call: Call, e: CallException?) = onEvent("ended")
        })
        return object : ActiveCall {
            override fun hangUp() = call.disconnect()
            override fun mute(on: Boolean) = call.mute(on)
        }
    }
}

/** Proof that a human tapped "Call" AND confirmed the dialog. Only the UI layer can mint one. */
class UserConfirmation internal constructor(val target: String, val confirmedAtMs: Long)

object LocalPrecheck {
    private val n11 = Regex("^1?[2-9]11$")
    /** UX-only early warning. The backend is the real enforcement point. */
    fun obviouslyBlocked(raw: String): Boolean {
        val d = raw.filter(Char::isDigit)
        return d.isEmpty() || n11.matches(d) || d in setOf("988", "1988", "112", "999", "000", "933") || d.length <= 6
    }
}

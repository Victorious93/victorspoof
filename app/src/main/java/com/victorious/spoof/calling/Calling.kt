package com.victorious.spoof.calling

import android.content.Context
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.ConnectOptions
import com.twilio.voice.Voice
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
)
@Serializable private data class AuthorizeReq(val target: String, val mode: CallerIdMode, val recording: Boolean)
@Serializable private data class TokenResp(val token: String, val ttlSeconds: Int)
@Serializable private data class AccountReq(val ageAttested18: Boolean, val disclaimerAccepted: Boolean)
@Serializable private data class AccountResp(val accountId: String)
@Serializable private data class AupReq(val version: String)

interface BackendClient {
    suspend fun createAccount(): String
    suspend fun acceptAup(accountId: String, version: String)
    suspend fun authorize(accountId: String, target: String, mode: CallerIdMode, recording: Boolean): CallAuthorization
    /** Short-lived Twilio access token. Twilio credentials never leave the backend. */
    suspend fun voiceToken(accountId: String): String
}

class HttpBackendClient(private val base: String) : BackendClient {
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun post(path: String, accountId: String?, body: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
        val c = URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 10_000
            c.setRequestProperty("Content-Type", "application/json")
            accountId?.let { c.setRequestProperty("X-Account-Id", it) }
            c.doOutput = true
            c.outputStream.use { it.write((body ?: "{}").toByteArray()) }
            val code = c.responseCode
            code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
        } finally { c.disconnect() }
    }

    override suspend fun createAccount(): String {
        val (code, body) = post("/v1/accounts", null, json.encodeToString(AccountReq(true, true)))
        check(code == 201) { "account creation failed ($code)" }
        return json.decodeFromString<AccountResp>(body).accountId
    }
    override suspend fun acceptAup(accountId: String, version: String) {
        val (code, _) = post("/v1/aup/accept", accountId, json.encodeToString(AupReq(version)))
        check(code == 200) { "AUP logging failed ($code)" }
    }
    override suspend fun authorize(accountId: String, target: String, mode: CallerIdMode, recording: Boolean): CallAuthorization {
        val (_, body) = post("/v1/calls/authorize", accountId, json.encodeToString(AuthorizeReq(target, mode, recording)))
        return runCatching { json.decodeFromString<CallAuthorization>(body) }.getOrElse { CallAuthorization(false, "backend_unreachable") }
    }
    override suspend fun voiceToken(accountId: String): String {
        val (code, body) = post("/v1/twilio/token", accountId, null)
        check(code == 200) { "token unavailable ($code)" }
        return json.decodeFromString<TokenResp>(body).token
    }
}

interface CallProvider {
    fun connect(token: String, auth: CallAuthorization, voicePreset: String, onEvent: (String) -> Unit): ActiveCall
}
interface ActiveCall { fun hangUp() }

/** Twilio Voice SDK (primary). The TwiML app on the backend re-validates the authorization. Telnyx is the documented backup. */
class TwilioCallProvider(private val ctx: Context) : CallProvider {
    override fun connect(token: String, auth: CallAuthorization, voicePreset: String, onEvent: (String) -> Unit): ActiveCall {
        val options = ConnectOptions.Builder(token)
            .params(mapOf("to" to (auth.target ?: ""), "voice" to voicePreset, "announceRecording" to auth.recordingAnnouncementRequired.toString()))
            .build()
        val call = Voice.connect(ctx, options, object : Call.Listener {
            override fun onConnectFailure(call: Call, e: CallException) = onEvent("failed: ${e.message}")
            override fun onRinging(call: Call) = onEvent("ringing")
            override fun onConnected(call: Call) = onEvent("connected")
            override fun onReconnecting(call: Call, e: CallException) = onEvent("reconnecting")
            override fun onReconnected(call: Call) = onEvent("reconnected")
            override fun onDisconnected(call: Call, e: CallException?) = onEvent("ended")
        })
        return object : ActiveCall { override fun hangUp() = call.disconnect() }
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

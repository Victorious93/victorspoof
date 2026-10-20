package com.victorious.spoof.backend.twilio

import com.twilio.security.RequestValidator
import com.victorious.spoof.backend.db.Store
import com.victorious.spoof.backend.guardrails.Blocklist
import com.victorious.spoof.backend.guardrails.DisplayNameFilter
import com.victorious.spoof.backend.guardrails.PhoneNumbers
import java.time.Clock

/**
 * Webhook config. TWILIO_AUTH_TOKEN is needed ONLY to validate X-Twilio-Signature (Twilio signs webhooks with the
 * account auth token, not an API key). PUBLIC_BASE_URL is the externally visible https origin Twilio calls.
 */
data class WebhookConfig(val authToken: String, val publicBaseUrl: String, val accountSid: String?) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): WebhookConfig? {
            val t = env("TWILIO_AUTH_TOKEN")?.takeIf(String::isNotBlank) ?: return null
            val u = env("PUBLIC_BASE_URL")?.takeIf(String::isNotBlank) ?: return null
            return WebhookConfig(t, u.trimEnd('/'), env("TWILIO_ACCOUNT_SID"))
        }
    }
}

fun interface SignatureValidator { fun valid(url: String, params: Map<String, String>, signature: String?): Boolean }

/**
 * TwiML voice webhook (the TwiML App's Voice URL). Twilio calls it when the app's Voice SDK connects.
 * The caller ID is set HERE from the one-time ticket the server minted; nothing the client sends can change it.
 */
class VoiceWebhook(
    private val store: Store,
    private val config: WebhookConfig?,
    private val blocklist: Blocklist = Blocklist(),
    private val clock: Clock = Clock.systemUTC(),
    private val validator: SignatureValidator? = config?.let { c ->
        val v = RequestValidator(c.authToken); SignatureValidator { url, p, sig -> sig != null && v.validate(url, p, sig) }
    },
) {
    data class Result(val status: Int, val twiml: String)

    fun handle(pathAndQuery: String, params: Map<String, String>, signature: String?): Result {
        val c = config ?: return Result(200, reject("VictorSpoof calling is not configured on the server."))
        if (validator?.valid(c.publicBaseUrl + pathAndQuery, params, signature) != true) return Result(403, reject("Request not signed by Twilio."))
        if (c.accountSid != null && params["AccountSid"] != c.accountSid) return Result(403, reject("Wrong account."))
        val identity = params["From"]?.removePrefix("client:") ?: return Result(200, reject("Unknown caller."))
        val ticketId = params["ticket"] ?: return Result(200, reject("This call was not authorized."))
        val t = store.consumeTicket(ticketId) ?: return Result(200, reject("This call was not authorized or was already used."))
        if (t.accountId != identity) return Result(200, reject("This call was not authorized."))
        if (t.expiresAt.isBefore(clock.instant())) return Result(200, reject("This authorization expired. Please try again."))
        val acct = store.account(t.accountId)
        if (acct == null || acct.suspended || !acct.aupAccepted || !acct.ageAttested) return Result(200, reject("Account not allowed to call."))
        // Defence in depth: re-run guardrails on what we are about to dial and present.
        if (blocklist.check(t.target) != null || PhoneNumbers.normalize(t.target) != t.target) return Result(200, reject("That number cannot be called."))
        val allowedCallerIds = store.pool().map { it.e164 }.toSet() + listOfNotNull(acct.verifiedNumber)
        if (t.callerId !in allowedCallerIds) return Result(200, reject("Caller ID not allowed."))
        t.displayName?.let { if (!DisplayNameFilter.isAllowed(it)) return Result(200, reject("Caller name not allowed.")) }
        val seconds = t.maxSeconds.coerceIn(1, 120)
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response>")
        if (t.recording) sb.append("<Say>This call will be recorded.</Say>")
        sb.append("<Dial callerId=\"${esc(t.callerId)}\" timeLimit=\"$seconds\" answerOnBridge=\"true\"")
        if (t.recording) sb.append(" record=\"record-from-answer\"")
        sb.append(">")
        val whisper = if (t.recording) " url=\"${esc(c.publicBaseUrl)}/v1/twilio/whisper?rec=1\" method=\"POST\"" else ""
        sb.append("<Number$whisper>${esc(t.target)}</Number></Dial></Response>")
        return Result(200, sb.toString())
    }

    /** Played to the CALLED party on answer, before bridging: the all-party recording announcement. */
    fun whisper(pathAndQuery: String, params: Map<String, String>, signature: String?): Result {
        val c = config ?: return Result(403, reject("not configured"))
        if (validator?.valid(c.publicBaseUrl + pathAndQuery, params, signature) != true) return Result(403, reject("Request not signed by Twilio."))
        return Result(200, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say>This call is being recorded for a VictorSpoof prank.</Say></Response>")
    }

    companion object {
        fun reject(msg: String) = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Say>${esc(msg)}</Say><Hangup/></Response>"
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    }
}

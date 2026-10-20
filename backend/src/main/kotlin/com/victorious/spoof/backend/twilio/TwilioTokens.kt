package com.victorious.spoof.backend.twilio

import com.twilio.jwt.accesstoken.AccessToken
import com.twilio.jwt.accesstoken.VoiceGrant

/**
 * Twilio credentials come ONLY from environment variables (injected from backend secrets).
 * Nothing is hard-coded and nothing is shared with the app or fleet config.
 *
 * Canonical names: TWILIO_ACCOUNT_SID, TWILIO_API_KEY, TWILIO_API_SECRET, TWILIO_TWIML_APP_SID.
 * The scaffold's TWILIO_API_KEY_SID / TWILIO_API_KEY_SECRET are still accepted as fallbacks.
 */
data class TwilioConfig(val accountSid: String, val apiKeySid: String, val apiKeySecret: String, val twimlAppSid: String) {
    override fun toString() = "TwilioConfig(accountSid=${accountSid.take(4)}…, apiKey=${apiKeySid.take(4)}…, secret=<redacted>)"
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): TwilioConfig? {
            fun v(vararg names: String) = names.firstNotNullOfOrNull { env(it)?.takeIf(String::isNotBlank) }
            val sid = v("TWILIO_ACCOUNT_SID") ?: return null
            val key = v("TWILIO_API_KEY", "TWILIO_API_KEY_SID") ?: return null
            val secret = v("TWILIO_API_SECRET", "TWILIO_API_KEY_SECRET") ?: return null
            val app = v("TWILIO_TWIML_APP_SID") ?: return null
            return TwilioConfig(sid, key, secret, app)
        }
        /** Names of required env vars that are missing, for the 503 error body. Never echoes values. */
        fun missing(env: (String) -> String? = System::getenv): List<String> = listOf(
            listOf("TWILIO_ACCOUNT_SID"), listOf("TWILIO_API_KEY", "TWILIO_API_KEY_SID"),
            listOf("TWILIO_API_SECRET", "TWILIO_API_KEY_SECRET"), listOf("TWILIO_TWIML_APP_SID"),
        ).filter { names -> names.all { env(it).isNullOrBlank() } }.map { it.first() }
    }
}

class TwilioTokenIssuer(private val config: TwilioConfig?, val ttlSeconds: Int = 600) {
    val configured get() = config != null

    /** Short-lived Voice access token. Outgoing only; the TwiML webhook re-checks authorization server-side. */
    fun issue(identity: String): String? {
        val c = config ?: return null
        val grant = VoiceGrant().setOutgoingApplicationSid(c.twimlAppSid).setIncomingAllow(false)
        return AccessToken.Builder(c.accountSid, c.apiKeySid, c.apiKeySecret)
            .identity(identity).ttl(ttlSeconds).grant(grant).build().toJwt()
    }
}

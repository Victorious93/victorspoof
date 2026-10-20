package com.victorious.spoof.backend

import com.twilio.jwt.accesstoken.AccessToken
import com.twilio.jwt.accesstoken.VoiceGrant

/**
 * Twilio credentials come ONLY from environment variables (injected from backend/CI secrets).
 * Nothing is hard-coded and nothing is shared with the app or fleet config.
 */
data class TwilioConfig(val accountSid: String, val apiKeySid: String, val apiKeySecret: String, val twimlAppSid: String) {
    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): TwilioConfig? {
            val sid = env("TWILIO_ACCOUNT_SID"); val key = env("TWILIO_API_KEY_SID")
            val secret = env("TWILIO_API_KEY_SECRET"); val app = env("TWILIO_TWIML_APP_SID")
            if (listOf(sid, key, secret, app).any { it.isNullOrBlank() }) return null
            return TwilioConfig(sid!!, key!!, secret!!, app!!)
        }
    }
}

class TwilioTokenIssuer(private val config: TwilioConfig?, private val ttlSeconds: Int = 600) {
    val configured get() = config != null

    /** Short-lived Voice access token. Outgoing only; TwiML app re-checks authorization server-side. */
    fun issue(identity: String): String? {
        val c = config ?: return null
        val grant = VoiceGrant().setOutgoingApplicationSid(c.twimlAppSid).setIncomingAllow(false)
        return AccessToken.Builder(c.accountSid, c.apiKeySid, c.apiKeySecret)
            .identity(identity).ttl(ttlSeconds).grant(grant).build().toJwt()
    }
}

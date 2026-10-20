package com.victorious.spoof.backend.verify

import com.twilio.http.TwilioRestClient
import com.twilio.rest.verify.v2.service.Verification
import com.twilio.rest.verify.v2.service.VerificationCheck
import com.victorious.spoof.backend.db.Store
import com.victorious.spoof.backend.guardrails.Blocklist
import com.victorious.spoof.backend.guardrails.PhoneNumbers
import com.victorious.spoof.backend.guardrails.WindowLimiter
import com.victorious.spoof.backend.twilio.TwilioConfig
import java.time.Clock
import java.time.Duration

/** Sends and checks one-time codes. Twilio Verify in production, a fake in tests. */
interface OtpProvider {
    fun send(e164: String)
    /** True only if Twilio Verify says the code is "approved". */
    fun check(e164: String, code: String): Boolean
}

class TwilioVerifyProvider(private val client: TwilioRestClient, private val serviceSid: String) : OtpProvider {
    override fun send(e164: String) { Verification.creator(serviceSid, e164, "sms").create(client) }
    override fun check(e164: String, code: String): Boolean =
        VerificationCheck.creator(serviceSid).setTo(e164).setCode(code).create(client).status == "approved"

    companion object {
        /** Needs TWILIO_ACCOUNT_SID + TWILIO_API_KEY + TWILIO_API_SECRET + TWILIO_VERIFY_SID. */
        fun fromEnv(cfg: TwilioConfig?, env: (String) -> String? = System::getenv): TwilioVerifyProvider? {
            val sid = env("TWILIO_VERIFY_SID")?.takeIf { it.isNotBlank() } ?: return null
            val c = cfg ?: return null
            return TwilioVerifyProvider(TwilioRestClient.Builder(c.apiKeySid, c.apiKeySecret).accountSid(c.accountSid).build(), sid)
        }
    }
}

sealed interface VerifyResult {
    data object Sent : VerifyResult
    data class Verified(val e164: String) : VerifyResult
    data class Fail(val status: Int, val error: String) : VerifyResult
}

/**
 * Own-number verification. Limits: 3 code sends per account per hour and 5 per day, 3 sends per phone number per day
 * (stops SMS-pumping a stranger's phone), and 5 code checks per account per hour.
 */
class VerificationService(private val store: Store, private val provider: OtpProvider?, private val blocklist: Blocklist = Blocklist(),
                          clock: Clock = Clock.systemUTC()) {
    private val sendHour = WindowLimiter(3, Duration.ofHours(1), clock)
    private val sendDay = WindowLimiter(5, Duration.ofDays(1), clock)
    private val sendPerNumber = WindowLimiter(3, Duration.ofDays(1), clock)
    private val checks = WindowLimiter(5, Duration.ofHours(1), clock)
    private val pending = mutableMapOf<String, String>() // accountId -> e164 awaiting a code

    val configured get() = provider != null

    fun start(accountId: String, raw: String): VerifyResult {
        val p = provider ?: return VerifyResult.Fail(503, "verify_not_configured")
        blocklist.check(raw)?.let { return VerifyResult.Fail(400, "blocked_number:${it.name.lowercase()}") }
        val e164 = PhoneNumbers.normalize(raw)?.takeIf { it.startsWith("+") } ?: return VerifyResult.Fail(400, "invalid_number")
        if (store.pool().any { it.e164 == e164 }) return VerifyResult.Fail(400, "pool_number")
        if (!sendHour.tryAcquire(accountId) || !sendDay.tryAcquire(accountId) || !sendPerNumber.tryAcquire(e164))
            return VerifyResult.Fail(429, "too_many_codes")
        try { p.send(e164) } catch (e: Exception) { return VerifyResult.Fail(502, "verify_send_failed") }
        synchronized(pending) { pending[accountId] = e164 }
        return VerifyResult.Sent
    }

    fun check(accountId: String, raw: String, code: String): VerifyResult {
        val p = provider ?: return VerifyResult.Fail(503, "verify_not_configured")
        val e164 = PhoneNumbers.normalize(raw) ?: return VerifyResult.Fail(400, "invalid_number")
        if (synchronized(pending) { pending[accountId] } != e164) return VerifyResult.Fail(409, "no_pending_verification")
        if (!code.matches(Regex("^\\d{4,10}$"))) return VerifyResult.Fail(400, "invalid_code")
        if (!checks.tryAcquire(accountId)) return VerifyResult.Fail(429, "too_many_attempts")
        val ok = try { p.check(e164, code) } catch (e: Exception) { return VerifyResult.Fail(502, "verify_check_failed") }
        if (!ok) return VerifyResult.Fail(400, "wrong_code")
        synchronized(pending) { pending.remove(accountId) }
        store.setVerifiedNumber(accountId, e164)
        return VerifyResult.Verified(e164)
    }
}

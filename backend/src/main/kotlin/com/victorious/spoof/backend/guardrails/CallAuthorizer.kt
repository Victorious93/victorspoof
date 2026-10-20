package com.victorious.spoof.backend.guardrails

import kotlinx.serialization.Serializable

@Serializable
data class CallAuthorization(
    val allowed: Boolean,
    val reason: String? = null,
    val callerId: CallerIdChoice? = null,
    val target: String? = null,
    val maxCallSeconds: Int? = null,
    val recordingAnnouncementRequired: Boolean = false,
)

data class AccountState(val id: String, val ageAttested: Boolean, val aupAccepted: Boolean, val verifiedNumber: String?, val suspended: Boolean = false)

/** Single choke point every outbound call goes through. */
class CallAuthorizer(
    private val blocklist: Blocklist,
    private val rateLimiter: RateLimiter,
    private val callerIdPolicy: CallerIdPolicy,
) {
    fun authorize(account: AccountState, rawTarget: String, mode: CallerIdMode, recording: Boolean): CallAuthorization {
        if (account.suspended) return deny("account_suspended")
        if (!account.ageAttested) return deny("age_gate_required")
        if (!account.aupAccepted) return deny("aup_not_accepted")
        blocklist.check(rawTarget)?.let { return deny("blocked_target:${it.name.lowercase()}") }
        val target = PhoneNumbers.normalize(rawTarget) ?: return deny("invalid_target")
        if (target == account.verifiedNumber) return deny("cannot_call_self")
        val callerId = callerIdPolicy.choose(mode, account.id, account.verifiedNumber)
            ?: return deny(if (mode == CallerIdMode.OWN_VERIFIED) "own_number_not_verified" else "pool_empty")
        callerId.displayName?.let { if (!DisplayNameFilter.isAllowed(it)) return deny("display_name_blocked") }
        return when (val r = rateLimiter.consume(account.id, target)) {
            is RateDecision.Denied -> deny(r.reason)
            is RateDecision.Allowed -> CallAuthorization(true, null, callerId, target, r.maxCallSeconds, recording)
        }
    }

    private fun deny(reason: String) = CallAuthorization(allowed = false, reason = reason)
}

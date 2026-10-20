package com.victorious.spoof.backend.guardrails

import kotlinx.serialization.Serializable

/** The client may only pick a MODE. It can never send a caller-ID number or name. */
enum class CallerIdMode { POOL, OWN_VERIFIED }

@Serializable
data class PoolNumber(val e164: String, val cnamLabel: String?, val cnamSupported: Boolean)

@Serializable
data class CallerIdChoice(val e164: String, val displayName: String?)

class CallerIdPolicy(private val poolProvider: () -> List<PoolNumber>) {
    /** Backend-side selection. Returns null if no compliant caller ID is available. */
    fun choose(mode: CallerIdMode, accountId: String, verifiedOwnNumber: String?): CallerIdChoice? = when (mode) {
        CallerIdMode.OWN_VERIFIED -> verifiedOwnNumber?.let { CallerIdChoice(it, null) }
        CallerIdMode.POOL -> {
            val pool = poolProvider().filter { it.cnamLabel == null || DisplayNameFilter.isAllowed(it.cnamLabel) }
            if (pool.isEmpty()) null else {
                val pick = pool[Math.floorMod(accountId.hashCode() + System.nanoTime().toInt(), pool.size)]
                CallerIdChoice(pick.e164, pick.cnamLabel.takeIf { pick.cnamSupported })
            }
        }
    }
}

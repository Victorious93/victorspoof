package com.victorious.spoof.backend.guardrails

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

data class RateLimitConfig(
    val maxCallsPerDay: Int = 10,
    val maxCallsPerTargetPerDay: Int = 3,
    val maxCallSeconds: Int = 120,
)

sealed interface RateDecision {
    data class Allowed(val maxCallSeconds: Int, val remainingToday: Int, val remainingForTarget: Int) : RateDecision
    data class Denied(val reason: String) : RateDecision
}

/** Storage for placed-call records. Postgres-backed in prod (rate_limits table), in-memory in tests. */
interface CallLedger {
    fun countForDay(accountId: String, day: LocalDate): Int
    fun countForTarget(accountId: String, target: String, day: LocalDate): Int
    fun record(accountId: String, target: String, day: LocalDate)
}

class InMemoryCallLedger : CallLedger {
    private val rows = mutableListOf<Triple<String, String, LocalDate>>()
    @Synchronized override fun countForDay(accountId: String, day: LocalDate) = rows.count { it.first == accountId && it.third == day }
    @Synchronized override fun countForTarget(accountId: String, target: String, day: LocalDate) =
        rows.count { it.first == accountId && it.second == target && it.third == day }
    @Synchronized override fun record(accountId: String, target: String, day: LocalDate) { rows += Triple(accountId, target, day) }
}

class RateLimiter(private val ledger: CallLedger, private val clock: Clock = Clock.systemUTC(), val config: RateLimitConfig = RateLimitConfig()) {
    private fun today() = LocalDate.now(clock.withZone(ZoneOffset.UTC))

    fun check(accountId: String, targetE164: String): RateDecision {
        val day = today()
        val total = ledger.countForDay(accountId, day)
        if (total >= config.maxCallsPerDay) return RateDecision.Denied("daily_limit")
        val perTarget = ledger.countForTarget(accountId, targetE164, day)
        if (perTarget >= config.maxCallsPerTargetPerDay) return RateDecision.Denied("target_daily_limit")
        return RateDecision.Allowed(config.maxCallSeconds, config.maxCallsPerDay - total - 1, config.maxCallsPerTargetPerDay - perTarget - 1)
    }

    /** Atomically check and consume one call slot. */
    @Synchronized
    fun consume(accountId: String, targetE164: String): RateDecision {
        val d = check(accountId, targetE164)
        if (d is RateDecision.Allowed) ledger.record(accountId, targetE164, today())
        return d
    }

    fun usage(accountId: String): Map<String, Int> {
        val used = ledger.countForDay(accountId, today())
        return mapOf("usedToday" to used, "limitPerDay" to config.maxCallsPerDay,
            "limitPerTarget" to config.maxCallsPerTargetPerDay, "maxCallSeconds" to config.maxCallSeconds)
    }
}

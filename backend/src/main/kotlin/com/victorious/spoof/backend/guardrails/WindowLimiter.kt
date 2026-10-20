package com.victorious.spoof.backend.guardrails

import java.time.Clock
import java.time.Duration

/**
 * Sliding-window counter used for token minting, OTP sends/checks and login attempts.
 * In-memory (per backend instance). With several instances, put it behind sticky routing or move it to Redis/Postgres.
 */
class WindowLimiter(private val max: Int, private val window: Duration, private val clock: Clock = Clock.systemUTC()) {
    private val hits = mutableMapOf<String, ArrayDeque<Long>>()

    /** Records a hit and returns true if it is within the limit. Denied hits are not recorded. */
    @Synchronized fun tryAcquire(key: String): Boolean {
        val now = clock.millis()
        val q = hits.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= window.toMillis()) q.removeFirst()
        if (q.size >= max) return false
        q.addLast(now); return true
    }

    @Synchronized fun reset(key: String) { hits.remove(key) }
}

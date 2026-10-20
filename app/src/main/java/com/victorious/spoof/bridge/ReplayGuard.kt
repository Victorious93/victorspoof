package com.victorious.spoof.bridge

/**
 * Replay protection: expiry, clock skew and duplicate nonces keyed on (sourceNodeRef, nonce).
 * sourceNodeRef is derived from the authenticated caller (package + cert), never from the envelope.
 */
class ReplayGuard(private val now: () -> Long = System::currentTimeMillis) {
    private val seen = LinkedHashMap<Pair<String, String>, Long>() // key -> expiry of cache entry
    private val retentionMs = maxOf(BridgeProtocol.MIN_NONCE_RETENTION_MS, BridgeProtocol.MAX_TTL_MS + BridgeProtocol.MAX_SKEW_MS)

    @Synchronized
    fun check(sourceNodeRef: String, nonce: String, timestampMs: Long, ttlMs: Long) {
        if (ttlMs > BridgeProtocol.MAX_TTL_MS || ttlMs <= 0) throw BridgeException(BridgeError.INVALID_PARAMETERS, "ttl out of range")
        val t = now()
        if (t > timestampMs + ttlMs) throw BridgeException(BridgeError.EXPIRED, "envelope expired")
        if (kotlin.math.abs(t - timestampMs) > BridgeProtocol.MAX_SKEW_MS) throw BridgeException(BridgeError.EXPIRED, "clock skew too large")
        purge(t)
        val key = sourceNodeRef to nonce.lowercase()
        if (seen.containsKey(key)) throw BridgeException(BridgeError.REPLAY_DETECTED, "duplicate nonce")
        seen[key] = t + retentionMs
    }

    private fun purge(t: Long) {
        val it = seen.entries.iterator()
        while (it.hasNext()) if (it.next().value < t) it.remove()
    }

    @Synchronized fun size() = seen.size
}

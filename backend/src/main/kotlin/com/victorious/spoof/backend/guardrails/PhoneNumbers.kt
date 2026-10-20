package com.victorious.spoof.backend.guardrails

/** Minimal NANP-focused normaliser. Returns E.164 or null when not a dialable number. */
object PhoneNumbers {
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        val plus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return when {
            plus -> if (digits.length in 8..15) "+$digits" else null
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            digits.length <= 6 -> digits // short codes / service codes stay raw so they can be blocked
            else -> null
        }
    }
}

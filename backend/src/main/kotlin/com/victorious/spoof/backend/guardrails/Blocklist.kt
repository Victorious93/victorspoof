package com.victorious.spoof.backend.guardrails

enum class BlockReason { EMERGENCY, CRISIS_LINE, N11_SERVICE, SHORT_CODE, GOVERNMENT, POLICE, HOSPITAL, BANK, INVALID }

/**
 * Target-number blocklist. Emergency/crisis/N11 are blocked structurally; government,
 * police, hospital and bank numbers come from a seeded list plus the blocked_numbers table.
 */
class Blocklist(extra: Map<String, BlockReason> = emptyMap()) {
    private val listed: Map<String, BlockReason> = SEED + extra

    fun check(rawTarget: String): BlockReason? {
        val stripped = rawTarget.filter { it.isDigit() }
        if (stripped in EMERGENCY) return BlockReason.EMERGENCY
        if (stripped == "988" || stripped == "1988") return BlockReason.CRISIS_LINE
        if (N11.matches(stripped) || (stripped.length == 4 && stripped[0] == '1' && N11.matches(stripped.drop(1)))) {
            return BlockReason.N11_SERVICE
        }
        val e164 = PhoneNumbers.normalize(rawTarget) ?: return BlockReason.INVALID
        if (!e164.startsWith("+")) return BlockReason.SHORT_CODE
        // NANP: area/exchange 911 / 988 style vanity tricks, e.g. +1 911 xxx xxxx is not a valid NPA anyway
        if (e164.startsWith("+1") && e164.length == 12) {
            val npa = e164.substring(2, 5)
            val nxx = e164.substring(5, 8)
            if (N11.matches(npa) || npa == "988") return BlockReason.INVALID
            if (nxx == "911" && e164.substring(8) == "0000") return BlockReason.EMERGENCY
        }
        return listed[e164]
    }

    companion object {
        private val N11 = Regex("^[2-9]11$")
        private val EMERGENCY = setOf("911", "1911", "112", "999", "000", "933")
        /** Seed list; extend in the blocked_numbers table. Well-known public numbers. */
        val SEED: Map<String, BlockReason> = mapOf(
            "+18008291040" to BlockReason.GOVERNMENT, // IRS
            "+18007721213" to BlockReason.GOVERNMENT, // SSA
            "+18006334227" to BlockReason.GOVERNMENT, // Medicare
            "+12023243000" to BlockReason.POLICE,     // FBI HQ
            "+18002255324" to BlockReason.POLICE,     // FBI tips
            "+12024561111" to BlockReason.GOVERNMENT, // White House
            "+18009359935" to BlockReason.BANK,       // Chase
            "+18004321000" to BlockReason.BANK,       // Bank of America
            "+18008693557" to BlockReason.BANK,       // Wells Fargo
            "+18009505114" to BlockReason.BANK,       // Citi
            "+18772274566" to BlockReason.BANK,       // Capital One
            "+18008722657" to BlockReason.BANK,       // US Bank
        )
    }
}

package com.victorious.spoof.backend.guardrails

/** Rejects display names (CNAM labels) that impersonate authorities, banks or emergency services. */
object DisplayNameFilter {
    private val LEET = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's', '!' to 'i', '|' to 'i')

    /** Substring terms (long enough to avoid false positives). */
    private val SUBSTRINGS = listOf(
        "police", "sheriff", "trooper", "marshal", "detective", "officer", "precinct", "swat",
        "irs", "fbi", "cia", "nsa", "dhs", "atf", "usps", "fema", "interpol",
        "government", "federal", "treasury", "socialsecurity", "medicare", "medicaid", "immigration",
        "court", "judge", "attorney", "warrant", "lawenforcement",
        "bank", "chase", "wellsfargo", "citibank", "bofa", "bankofamerica", "capitalone", "paypal", "visa", "mastercard", "amex",
        "hospital", "clinic", "emergency", "ambulance", "paramedic", "firedept", "firedepartment", "poison",
        "911", "988", "suicide", "crisis",
    )
    /** Whole-word terms (too short for substring matching: e.g. "ice" inside "alice"). */
    private val WORDS = setOf("ice", "dea", "doj", "ssa", "gov", "cop", "cops", "pd", "er", "va", "dmv", "irs", "fbi", "usa")

    fun isAllowed(name: String): Boolean = violation(name) == null

    fun violation(name: String): String? {
        val lower = name.lowercase().map { LEET[it] ?: it }.joinToString("")
        val squashed = lower.filter { it.isLetterOrDigit() }
        val digitsKept = name.lowercase().filter { it.isLetterOrDigit() }
        SUBSTRINGS.firstOrNull { squashed.contains(it) || digitsKept.contains(it) }?.let { return it }
        val words = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        return words.firstOrNull { it in WORDS }
    }
}

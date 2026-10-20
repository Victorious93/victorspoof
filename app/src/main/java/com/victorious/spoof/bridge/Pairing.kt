package com.victorious.spoof.bridge

/** Who is calling, resolved from Message.sendingUid via PackageManager. Never taken from the envelope. */
data class CallerIdentity(val packageName: String, val certSha256: String) {
    val sourceNodeRef: String get() = "$packageName#${certSha256.lowercase()}"
}

interface PairingStore {
    fun isPaired(caller: CallerIdentity): Boolean
}

class StaticPairingStore(private val paired: Set<Pair<String, String>>) : PairingStore {
    override fun isPaired(caller: CallerIdentity) =
        paired.any { (p, c) -> p == caller.packageName && normalizeCert(c) == normalizeCert(caller.certSha256) }
    companion object { fun normalizeCert(c: String) = c.replace(":", "").lowercase() }
}

/** Settings that gate the bridge. External control is OFF by default. */
interface BridgeSettings {
    val externalControlEnabled: Boolean
    fun isCapabilityEnabled(op: String): Boolean = true
}

object NodeIds {
    fun generate(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val b = ByteArray(16).also(random::nextBytes)
        return "victorspoof-" + b.joinToString("") { "%02x".format(it) }
    }
    fun isValid(id: String) = BridgeProtocol.NODE_ID_REGEX.matches(id)
}

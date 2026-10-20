package com.victorious.spoof.bridge

/** Fleet bridge, frozen envelope v1. Transport: bound Messenger, newline-delimited JSON in the "payload" extra. */
object BridgeProtocol {
    const val ACTION_BRIDGE = "com.victorious.spoof.action.BRIDGE"
    const val BRIDGE_PROTOCOL_MAJOR = 1
    const val BRIDGE_PROTOCOL_MINOR = 1
    const val WHAT_REQUEST = 1
    const val WHAT_RESPONSE = 2
    const val KEY_PAYLOAD = "payload"
    const val FLEET_CLASS = "EXTERNAL_NODE"

    const val DEFAULT_TTL_MS = 120_000L
    const val MAX_TTL_MS = 300_000L
    const val MAX_SKEW_MS = 120_000L
    const val MIN_NONCE_RETENTION_MS = 10 * 60_000L

    val NODE_ID_REGEX = Regex("^victorspoof-[0-9a-f]{32}$")
    val ENVELOPE_OPS = setOf("capabilities.get", "remote.command", "macro.step", "macro.run", "node.status.get")
}

enum class BridgeError(val wire: String) {
    INVALID_PARAMETERS("invalid_parameters"),
    NOT_PAIRED("not_paired"),
    NOT_FOUND("not_found"),
    INTERNAL("internal"),
    UNAUTHORIZED("unauthorized"),
    REPLAY_DETECTED("replay_detected"),
    EXPIRED("expired"),
    EXTERNAL_CONTROL_DISABLED("external_control_disabled"),
    CONFIRMATION_REQUIRED("confirmation_required"),
    UNSUPPORTED_OP("unsupported_op"),
    CAPABILITY_OFF("capability_off"),
}

class BridgeException(val error: BridgeError, message: String) : Exception(message)

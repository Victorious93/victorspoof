package com.victorious.spoof.bridge

import kotlinx.serialization.json.*

/**
 * node.status.get, per Victorious93/droidcommand-AI docs/specs/STATUS_TRAY.md (FROZEN).
 * VictorSpoof reports ONLY the node_health domain. The snapshot never carries Twilio credentials,
 * phone numbers, pool data, account ids or signing material.
 */
enum class HealthState(val wire: String) { OK("ok"), DEGRADED("degraded"), DOWN("down"), UNKNOWN("unknown"), OFF("off") }

data class NodeStatusSnapshot(
    val nodeId: String,
    val packageName: String?,
    val updatedAtMs: Long,
    val health: HealthState,
    val detail: String? = null,
) {
    val connectionClass get() = BridgeProtocol.FLEET_CLASS

    fun toJson(): JsonObject = buildJsonObject {
        put("nodeId", nodeId)
        put("packageName", packageName)
        put("connectionClass", connectionClass)
        put("updatedAtMs", updatedAtMs)
        putJsonArray("domains") {
            add(buildJsonObject {
                put("name", "node_health")
                put("state", health.wire)
                sanitizeDetail(detail)?.let { put("detail", it) }
            })
        }
    }

    companion object {
        private val DIGIT_RUN = Regex("\\+?\\d[\\d\\s().-]{5,}\\d")
        private val SECRETY = Regex("(?i)(AC|SK|AP)[0-9a-f]{32}|secret|token|password")
        /** Max 200 chars, and strip anything that looks like a phone number or a credential. */
        fun sanitizeDetail(d: String?): String? {
            if (d.isNullOrBlank()) return null
            if (SECRETY.containsMatchIn(d)) return null
            return d.replace(DIGIT_RUN, "[redacted]").take(200)
        }
    }
}

package com.victorious.spoof.bridge

import kotlinx.serialization.json.*

data class MacroStepSpec(val actionRef: String, val params: JsonObject, val onError: String)

/** Parsed frozen-v1 envelope. Unknown extra fields are ignored. */
data class Envelope(
    val op: String,
    val requestId: String,
    val targetNodeRef: String,
    val actionRef: String?,
    val params: JsonObject,
    val nonce: String,
    val timestampMs: Long,
    val ttlMs: Long,
    val destructiveHint: Boolean?, // advisory only, NEVER used for authorization
    val trigger: JsonObject?,      // informational only, NEVER used for authorization
    val macroId: String?,
    val stepIndex: Int?,
    val onError: String?,
    val steps: List<MacroStepSpec>?,
    val dryRun: Boolean,
) {
    companion object {
        private val NONCE = Regex("^[0-9a-fA-F]{32}$")
        private fun bad(msg: String): Nothing = throw BridgeException(BridgeError.INVALID_PARAMETERS, msg)

        fun requestIdOf(line: String): String? = runCatching {
            (Json.parseToJsonElement(line) as? JsonObject)?.get("requestId")?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        fun parse(line: String): Envelope {
            val o = runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: bad("not a JSON object")
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun obj(k: String) = when (val v = o[k]) { null, JsonNull -> null; is JsonObject -> v; else -> bad("$k must be an object") }

            val op = str("op") ?: bad("op required")
            if (op !in BridgeProtocol.ENVELOPE_OPS) throw BridgeException(BridgeError.UNSUPPORTED_OP, "unsupported op $op")
            val requestId = str("requestId")?.takeIf { it.isNotBlank() } ?: bad("requestId required")
            val target = str("targetNodeRef") ?: bad("targetNodeRef required")
            if (!BridgeProtocol.NODE_ID_REGEX.matches(target)) bad("targetNodeRef malformed")
            val nonceRaw = str("nonce") ?: bad("nonce required")
            val nonce = nonceRaw.replace("-", "")
            if (!NONCE.matches(nonce)) bad("nonce must be 128-bit hex")
            val ts = (o["timestampMs"] as? JsonPrimitive)?.longOrNull ?: bad("timestampMs required")
            val ttlEl = o["ttlMs"]
            val ttl = if (ttlEl == null || ttlEl is JsonNull) BridgeProtocol.DEFAULT_TTL_MS
                else (ttlEl as? JsonPrimitive)?.longOrNull ?: bad("ttlMs must be a number")
            if (ttl <= 0) bad("ttlMs must be positive")
            if (ttl > BridgeProtocol.MAX_TTL_MS) bad("ttlMs exceeds ${BridgeProtocol.MAX_TTL_MS}")
            val params = obj("params") ?: JsonObject(emptyMap())
            val onError = str("onError")
            if (onError != null && onError !in setOf("abort", "continue")) bad("onError must be abort|continue")

            var steps: List<MacroStepSpec>? = null
            when (op) {
                "remote.command" -> if (str("actionRef") == null) bad("actionRef required")
                "macro.step" -> {
                    if (str("macroId") == null) bad("macroId required")
                    val idx = (o["stepIndex"] as? JsonPrimitive)?.intOrNull ?: bad("stepIndex required")
                    if (idx < 0) bad("stepIndex must be >= 0")
                    if (str("actionRef") == null) bad("actionRef required")
                }
                "macro.run" -> {
                    if (str("macroId") == null) bad("macroId required")
                    val arr = o["steps"] as? JsonArray ?: bad("steps[] required")
                    if (arr.isEmpty() || arr.size > 32) bad("steps must have 1..32 entries")
                    steps = arr.map { s ->
                        val so = s as? JsonObject ?: bad("step must be an object")
                        val ref = (so["actionRef"] as? JsonPrimitive)?.contentOrNull ?: bad("step.actionRef required")
                        val sp = so["params"] as? JsonObject ?: JsonObject(emptyMap())
                        val se = (so["onError"] as? JsonPrimitive)?.contentOrNull ?: onError ?: "abort"
                        if (se !in setOf("abort", "continue")) bad("step.onError must be abort|continue")
                        MacroStepSpec(ref, sp, se)
                    }
                }
            }
            return Envelope(
                op, requestId, target, str("actionRef"), params, nonce.lowercase(), ts, ttl,
                (o["destructive"] as? JsonPrimitive)?.booleanOrNull, obj("trigger"),
                str("macroId"), (o["stepIndex"] as? JsonPrimitive)?.intOrNull, onError, steps,
                (o["dryRun"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }
}

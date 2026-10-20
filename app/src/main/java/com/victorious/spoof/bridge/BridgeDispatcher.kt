package com.victorious.spoof.bridge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * The ONLY things a remote fleet node may make this app do. There is intentionally no method that
 * dials, answers, ends or records a call: remote ops cannot place a call (enforced by tests).
 */
interface RemoteOpExecutor {
    fun status(): JsonObject
    fun poolList(): JsonArray
    fun usage(): JsonObject
    fun openUi(screen: String?): JsonObject
    /** node_health only. */
    fun nodeStatus(): NodeStatusSnapshot
}

class BridgeDispatcher(
    private val nodeId: String,
    private val pairing: PairingStore,
    private val settings: BridgeSettings,
    private val replay: ReplayGuard,
    private val executor: RemoteOpExecutor,
) {
    init { require(NodeIds.isValid(nodeId)) { "invalid node id" } }

    private val json = Json { encodeDefaults = true }

    /** Handles one NDJSON payload; returns one response line per request line. */
    fun handlePayload(payload: String, caller: CallerIdentity?): String =
        payload.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { handleLine(it, caller).toString() }

    fun handleLine(line: String, caller: CallerIdentity?): JsonObject {
        val reqId = Envelope.requestIdOf(line)
        return try {
            // 1. Authenticate the caller by package + signing cert ONLY.
            if (caller == null) throw BridgeException(BridgeError.UNAUTHORIZED, "caller identity unavailable")
            if (!pairing.isPaired(caller)) throw BridgeException(BridgeError.NOT_PAIRED, "caller not paired")
            // 2. Parse and validate the frozen v1 envelope.
            val env = Envelope.parse(line)
            if (env.targetNodeRef != nodeId) throw BridgeException(BridgeError.NOT_FOUND, "unknown targetNodeRef")
            // 3. Replay protection (expiry, skew, duplicate nonce per source).
            replay.check(caller.sourceNodeRef, env.nonce, env.timestampMs, env.ttlMs)
            // 4. Dispatch.
            ok(env, when (env.op) {
                "capabilities.get" -> capabilities()
                "remote.command" -> { requireExternal(); runOne(env.actionRef!!, env.params) }
                "macro.step" -> { requireExternal(); buildJsonObject {
                    put("macroId", env.macroId); put("stepIndex", env.stepIndex)
                    put("output", runOne(env.actionRef!!, env.params))
                } }
                "macro.run" -> { requireExternal(); runMacro(env) }
                // STATUS-TRAY spec also allows node.status.get as the top-level operation.
                "node.status.get" -> { requireExternal(); runOne("node.status.get", env.params) }
                else -> throw BridgeException(BridgeError.UNSUPPORTED_OP, env.op)
            })
        } catch (e: BridgeException) {
            err(reqId, e.error, e.message ?: e.error.wire)
        } catch (e: Exception) {
            err(reqId, BridgeError.INTERNAL, "internal error")
        }
    }

    fun capabilities(): JsonObject = buildJsonObject {
        put("nodeId", nodeId)
        put("fleetClass", BridgeProtocol.FLEET_CLASS)
        put("protocolMajor", BridgeProtocol.BRIDGE_PROTOCOL_MAJOR)
        put("protocolMinor", BridgeProtocol.BRIDGE_PROTOCOL_MINOR)
        put("externalControlEnabled", settings.externalControlEnabled)
        put("ops", json.encodeToJsonElement(OpRegistry.ops))
    }

    private fun requireExternal() {
        if (!settings.externalControlEnabled) throw BridgeException(BridgeError.EXTERNAL_CONTROL_DISABLED, "external control is off")
    }

    /** Gate based purely on the receiver's own registry. */
    private fun gate(actionRef: String): OpDescriptor {
        val d = OpRegistry.find(actionRef) ?: throw BridgeException(BridgeError.NOT_FOUND, "unknown actionRef $actionRef")
        // remoteAllowed=false is a hard deny (e.g. every spoof.call* op). It can only run from in-app UI.
        // UNIFIED_BACKUP §6: remoteAllowed=false config ops answer capability_off to remote callers.
        if (!d.remoteAllowed && d.name.startsWith("config.")) throw BridgeException(BridgeError.CAPABILITY_OFF, "${d.name} is local-only (remoteAllowed=false)")
        if (!d.remoteAllowed) throw BridgeException(BridgeError.UNAUTHORIZED, "${d.name} is not remote-allowed; local in-app use only")
        // Remote-allowed but confirm-required ops would need an on-device prompt; none are approved yet.
        if (d.requiresConfirm) throw BridgeException(BridgeError.CONFIRMATION_REQUIRED, "${d.name} requires on-device confirmation")
        if (!settings.isCapabilityEnabled(d.name)) throw BridgeException(BridgeError.CAPABILITY_OFF, "${d.name} disabled on this node")
        return d
    }

    private fun runOne(actionRef: String, params: JsonObject): JsonElement {
        val d = gate(actionRef)
        return when (d.name) {
            "node.status.get" -> executor.nodeStatus().toJson()
            "spoof.status.get" -> executor.status()
            "spoof.pool.list" -> executor.poolList()
            "spoof.usage.get" -> executor.usage()
            "spoof.ui.open" -> executor.openUi((params["screen"] as? JsonPrimitive)?.contentOrNull)
            else -> throw BridgeException(BridgeError.UNSUPPORTED_OP, "no remote handler for ${d.name}")
        }
    }

    private fun runMacro(env: Envelope): JsonObject {
        val steps = env.steps!!
        // Gate EVERY step before running ANY step: a macro can't smuggle a call op past the gate.
        steps.forEach { gate(it.actionRef) }
        return buildJsonObject {
            put("macroId", env.macroId)
            put("dryRun", env.dryRun)
            putJsonArray("steps") {
                for ((i, s) in steps.withIndex()) {
                    if (env.dryRun) { add(buildJsonObject { put("stepIndex", i); put("actionRef", s.actionRef); put("wouldRun", true) }); continue }
                    try {
                        add(buildJsonObject { put("stepIndex", i); put("ok", true); put("output", runOne(s.actionRef, s.params)) })
                    } catch (e: BridgeException) {
                        add(buildJsonObject { put("stepIndex", i); put("ok", false); put("error", e.error.wire) })
                        if (s.onError == "abort") break
                    }
                }
            }
        }
    }

    private fun base(requestId: String?) = mutableMapOf<String, JsonElement>(
        "requestId" to JsonPrimitive(requestId),
        "nodeId" to JsonPrimitive(nodeId),
        "protocolMajor" to JsonPrimitive(BridgeProtocol.BRIDGE_PROTOCOL_MAJOR),
        "protocolMinor" to JsonPrimitive(BridgeProtocol.BRIDGE_PROTOCOL_MINOR),
    )

    private fun ok(env: Envelope, result: JsonElement) = JsonObject(base(env.requestId).apply {
        put("op", JsonPrimitive(env.op)); put("ok", JsonPrimitive(true)); put("result", result)
    })

    private fun err(requestId: String?, e: BridgeError, msg: String) = JsonObject(base(requestId).apply {
        put("ok", JsonPrimitive(false))
        put("error", buildJsonObject { put("category", e.wire); put("message", msg) })
    })

    @Suppress("unused") private fun encode(o: JsonObject) = json.encodeToString(o)
}

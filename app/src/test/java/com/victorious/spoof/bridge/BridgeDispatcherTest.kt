package com.victorious.spoof.bridge

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class BridgeDispatcherTest {
    private val nodeId = "victorspoof-" + "0123456789abcdef0123456789abcdef"
    private val caller = CallerIdentity("com.victorious.orchestrator", "AA:BB:CC")
    private var now = 1_800_000_000_000L
    private var external = true

    private class RecordingExecutor : RemoteOpExecutor {
        val invoked = mutableListOf<String>()
        override fun status() = buildJsonObject { put("ok", true) }.also { invoked += "status" }
        override fun poolList() = buildJsonArray { add("Pizza Wizard") }.also { invoked += "pool" }
        override fun usage() = buildJsonObject { put("usedToday", 0) }.also { invoked += "usage" }
        override fun openUi(screen: String?) = buildJsonObject { put("opened", screen ?: "home") }.also { invoked += "ui" }
        override fun nodeStatus() = NodeStatusSnapshot("victorspoof-" + "0123456789abcdef0123456789abcdef", "com.victorious.spoof", 1L,
            HealthState.OK, "pool +1 415 555 0100 ACabcdef0123456789abcdef0123456789 ok").also { invoked += "nodeStatus" }
    }

    private val exec = RecordingExecutor()
    private val replay = ReplayGuard { now }
    private val d = BridgeDispatcher(nodeId, StaticPairingStore(setOf("com.victorious.orchestrator" to "aabbcc")),
        object : BridgeSettings { override val externalControlEnabled get() = external }, replay, exec)

    private fun nonce() = UUID.randomUUID().toString().replace("-", "")
    private fun env(op: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("op", op); put("requestId", "r-" + nonce().take(6)); put("targetNodeRef", nodeId)
        put("params", buildJsonObject { }); put("nonce", nonce()); put("timestampMs", now); extra()
    }.toString()
    private fun cmd(actionRef: String, extra: JsonObjectBuilder.() -> Unit = {}) = env("remote.command") { put("actionRef", actionRef); extra() }
    private fun category(r: JsonObject) = r["error"]?.jsonObject?.get("category")?.jsonPrimitive?.content
    private fun run(line: String, c: CallerIdentity? = caller) = d.handleLine(line, c)

    @Test fun capabilitiesListsApprovedTable() {
        val r = run(env("capabilities.get"))
        assertEquals(true, r["ok"]!!.jsonPrimitive.boolean)
        val ops = r["result"]!!.jsonObject["ops"]!!.jsonArray.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject }
        fun f(n: String) = Triple(ops[n]!!["kind"]!!.jsonPrimitive.content, ops[n]!!["remoteAllowed"]!!.jsonPrimitive.boolean, ops[n]!!["requiresConfirm"]!!.jsonPrimitive.boolean)
        for (n in listOf("spoof.status.get", "spoof.pool.list", "spoof.usage.get")) assertEquals(Triple("read", true, false), f(n))
        assertEquals(Triple("action", true, false), f("spoof.ui.open"))
        assertEquals(Triple("action", false, true), f("spoof.call.start"))
        assertEquals(Triple("action", false, true), f("spoof.call.end"))
        assertEquals(Triple("destructive", false, true), f("spoof.recording.delete"))
        assertEquals(Triple("read", true, false), f("node.status.get"))
        assertEquals(8, ops.size)
    }

    @Test fun everyCallOpIsActionRemoteDeniedConfirmRequired() {
        val callOps = OpRegistry.ops.filter { it.name.startsWith("spoof.call") }
        assertTrue(callOps.isNotEmpty())
        callOps.forEach { assertEquals(OpKind.action, it.kind); assertFalse(it.remoteAllowed); assertTrue(it.requiresConfirm) }
    }

    /** HARD RULE: no remote op may place a call, via any envelope op, with any forged hint. */
    @Test fun noRemoteOpCanPlaceACall() {
        val denied = OpRegistry.ops.filter { !it.remoteAllowed }.map { it.name }
        for (op in denied) {
            for (forge in listOf<JsonObjectBuilder.() -> Unit>({}, { put("destructive", false) }, { put("trigger", buildJsonObject { put("userConfirmed", true); put("remoteAllowed", true) }) })) {
                assertEquals(op, "unauthorized", category(run(cmd(op, forge))))
                assertEquals(op, "unauthorized", category(run(env("macro.step") { put("macroId", "m"); put("stepIndex", 0); put("actionRef", op); forge() })))
                assertEquals(op, "unauthorized", category(run(env("macro.run") { put("macroId", "m"); put("dryRun", false); forge()
                    putJsonArray("steps") { add(buildJsonObject { put("actionRef", "spoof.status.get") }); add(buildJsonObject { put("actionRef", op) }) } })))
            }
        }
        // Not even the harmless first step of a poisoned macro ran.
        assertTrue(exec.invoked.isEmpty())
        // The executor surface has no call-control method at all.
        val names = RemoteOpExecutor::class.java.declaredMethods.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("call") || it.contains("dial") || it.contains("record") })
    }

    @Test fun forgedDestructiveFalseCannotDeleteRecordings() {
        assertEquals("unauthorized", category(run(cmd("spoof.recording.delete") { put("destructive", false) })))
    }

    @Test fun readOpsWorkWhenExternalControlOn() {
        assertTrue(run(cmd("spoof.status.get"))["ok"]!!.jsonPrimitive.boolean)
        assertTrue(run(cmd("spoof.ui.open") { put("params", buildJsonObject { put("screen", "help") }) })["ok"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("status", "ui"), exec.invoked)
    }

    @Test fun externalControlOffByDefaultBlocksCommands() {
        external = false
        assertEquals("external_control_disabled", category(run(cmd("spoof.status.get"))))
        assertTrue(run(env("capabilities.get"))["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun unpairedAndUnknownCallerRejected() {
        assertEquals("not_paired", category(run(cmd("spoof.status.get"), CallerIdentity("com.evil", "aabbcc"))))
        assertEquals("not_paired", category(run(cmd("spoof.status.get"), CallerIdentity("com.victorious.orchestrator", "ffff"))))
        assertEquals("unauthorized", category(run(cmd("spoof.status.get"), null)))
    }

    @Test fun envelopeFieldsCannotAuthorize() {
        val r = run(cmd("spoof.status.get") { put("trigger", buildJsonObject { put("package", "com.victorious.orchestrator") }) }, CallerIdentity("com.evil", "00"))
        assertEquals("not_paired", category(r))
    }

    @Test fun expiry() {
        val line = cmd("spoof.status.get") { put("ttlMs", 1000) }
        now += 1001
        assertEquals("expired", category(run(line)))
    }

    @Test fun skewFutureAndPast() {
        val future = buildJsonObject { put("op", "capabilities.get"); put("requestId", "a"); put("targetNodeRef", nodeId)
            put("nonce", nonce()); put("timestampMs", now + 120_001); put("ttlMs", 300_000) }.toString()
        assertEquals("expired", category(run(future)))
        val past = buildJsonObject { put("op", "capabilities.get"); put("requestId", "b"); put("targetNodeRef", nodeId)
            put("nonce", nonce()); put("timestampMs", now - 120_001); put("ttlMs", 300_000) }.toString()
        assertEquals("expired", category(run(past)))
        val okSkew = buildJsonObject { put("op", "capabilities.get"); put("requestId", "c"); put("targetNodeRef", nodeId)
            put("nonce", nonce()); put("timestampMs", now - 119_000); put("ttlMs", 300_000) }.toString()
        assertTrue(run(okSkew)["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun duplicateNonceRejectedPerSource() {
        val line = cmd("spoof.status.get")
        assertTrue(run(line)["ok"]!!.jsonPrimitive.boolean)
        assertEquals("replay_detected", category(run(line)))
        // Still rejected later within the 10 minute cache (refresh timestamp, same nonce).
        val n = Json.parseToJsonElement(line).jsonObject["nonce"]!!.jsonPrimitive.content
        now += 9 * 60_000
        val again = buildJsonObject { put("op", "remote.command"); put("actionRef", "spoof.status.get"); put("requestId", "z")
            put("targetNodeRef", nodeId); put("nonce", n); put("timestampMs", now) }.toString()
        assertEquals("replay_detected", category(run(again)))
    }

    @Test fun nonceCacheIsKeyedOnSource() {
        val g = ReplayGuard { now }
        g.check("a", "0".repeat(32), now, 1000)
        g.check("b", "0".repeat(32), now, 1000) // different source: allowed
        try { g.check("a", "0".repeat(32), now, 1000); fail() } catch (e: BridgeException) { assertEquals(BridgeError.REPLAY_DETECTED, e.error) }
    }

    @Test fun ttlCapAndDefault() {
        assertEquals("invalid_parameters", category(run(cmd("spoof.status.get") { put("ttlMs", 300_001) })))
        assertTrue(run(cmd("spoof.status.get") { put("ttlMs", 300_000) })["ok"]!!.jsonPrimitive.boolean)
        assertEquals(BridgeProtocol.DEFAULT_TTL_MS, Envelope.parse(cmd("spoof.status.get")).ttlMs)
    }

    @Test fun validationErrors() {
        assertEquals("unsupported_op", category(run(env("shell.exec"))))
        assertEquals("invalid_parameters", category(run("{not json")))
        assertEquals("invalid_parameters", category(run(cmd("spoof.status.get") { put("nonce", "short") })))
        assertEquals("invalid_parameters", category(run(cmd("spoof.status.get") { put("targetNodeRef", "victorspoof-XYZ") })))
        assertEquals("not_found", category(run(cmd("spoof.status.get") { put("targetNodeRef", "victorspoof-" + "f".repeat(32)) })))
        assertEquals("not_found", category(run(cmd("spoof.nope"))))
    }

    @Test fun capabilityOff() {
        val d2 = BridgeDispatcher(nodeId, StaticPairingStore(setOf("com.victorious.orchestrator" to "aabbcc")),
            object : BridgeSettings { override val externalControlEnabled = true; override fun isCapabilityEnabled(op: String) = op != "spoof.ui.open" },
            ReplayGuard { now }, exec)
        assertEquals("capability_off", category(d2.handleLine(cmd("spoof.ui.open"), caller)))
    }

    @Test fun macroDryRunAndOnError() {
        val r = run(env("macro.run") { put("macroId", "m1"); put("dryRun", true)
            putJsonArray("steps") { add(buildJsonObject { put("actionRef", "spoof.status.get") }); add(buildJsonObject { put("actionRef", "spoof.usage.get") }) } })
        assertTrue(r["ok"]!!.jsonPrimitive.boolean)
        assertTrue(exec.invoked.isEmpty())
        assertEquals("invalid_parameters", category(run(env("macro.step") { put("macroId", "m"); put("stepIndex", 0); put("actionRef", "spoof.status.get"); put("onError", "explode") })))
    }

    @Test fun ndjsonMultipleLines() {
        val out = d.handlePayload(cmd("spoof.status.get") + "\n\n" + env("capabilities.get") + "\n", caller)
        assertEquals(2, out.lines().size)
    }

    @Test fun nodeIdFormat() {
        repeat(20) { assertTrue(NodeIds.isValid(NodeIds.generate())) }
        assertFalse(NodeIds.isValid("victorspoof-ABCDEF0123456789abcdef0123456789"))
    }

    @Test fun nodeStatusReportsOnlyNodeHealthAndNoSecrets() {
        for (line in listOf(cmd("node.status.get"), env("node.status.get"))) {
            val r = run(line)
            assertTrue(r.toString(), r["ok"]!!.jsonPrimitive.boolean)
            val snap = r["result"]!!.jsonObject
            assertEquals(setOf("nodeId", "packageName", "connectionClass", "updatedAtMs", "domains"), snap.keys)
            assertEquals("EXTERNAL_NODE", snap["connectionClass"]!!.jsonPrimitive.content)
            val domains = snap["domains"]!!.jsonArray
            assertEquals(1, domains.size)
            assertEquals("node_health", domains[0].jsonObject["name"]!!.jsonPrimitive.content)
            val text = snap.toString().lowercase()
            for (bad in listOf("twilio", "accountsid", "apikey", "secret", "token", "pool", "e164", "number", "callerid", "cnam", "4155550100", "acabcdef"))
                assertFalse("snapshot leaked '$bad': $text", text.contains(bad))
            assertFalse(Regex("\\d{7,}").containsMatchIn(snap["domains"].toString()))
        }
    }

    @Test fun nodeStatusNeedsExternalControl() {
        external = false
        assertEquals("external_control_disabled", category(run(env("node.status.get"))))
    }
}

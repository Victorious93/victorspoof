package com.victorious.spoof.config

import com.victorious.spoof.bridge.BridgeError
import com.victorious.spoof.bridge.BridgeException
import com.victorious.spoof.bridge.OpKind
import com.victorious.spoof.bridge.OpRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class ConfigBackupTest {
    private class FakeWriter(var external: Boolean = true) : ConfigBackup.PrefsWriter {
        val writes = mutableMapOf<String, JsonPrimitive>()
        override fun writeUiPref(key: String, value: JsonPrimitive) { writes[key] = value }
        override fun forceExternalControlOff() { external = false }
    }

    private fun err(block: () -> Unit): BridgeError = try { block(); fail("expected refusal"); throw IllegalStateException() } catch (e: BridgeException) { e.error }
    private fun payload(prefs: JsonObjectBuilder.() -> Unit) = buildJsonObject { put("schemaVersion", 1); putJsonObject("prefs", prefs) }

    @Test fun opsAdvertisedLocalOnly() {
        val ex = OpRegistry.find("config.export")!!; val im = OpRegistry.find("config.import")!!
        assertEquals(OpKind.action, ex.kind); assertFalse(ex.remoteAllowed); assertFalse(ex.requiresConfirm)
        assertEquals(OpKind.action, im.kind); assertFalse(im.remoteAllowed); assertTrue(im.requiresConfirm)
    }

    @Test fun exportContainsOnlyAllowlistedUiPrefs() {
        val live = mapOf(
            "ui.theme" to JsonPrimitive("dark"), "ui.soundboardVolume" to JsonPrimitive(70),
            "twilio.authToken" to JsonPrimitive("abc"), "numberPool" to JsonArray(listOf(JsonPrimitive("+14155550100"))),
            "accountId" to JsonPrimitive("8c1f0a1e-1111-2222-3333-444455556666"), "externalControl" to JsonPrimitive(true),
            "ui.unknown" to JsonPrimitive("x"),
        )
        val out = ConfigBackup.exportPayload(live)
        assertEquals(setOf("ui.theme", "ui.soundboardVolume"), out["prefs"]!!.jsonObject.keys)
        val text = out.toString().lowercase()
        for (bad in listOf("twilio", "authtoken", "numberpool", "4155550100", "accountid", "8c1f0a1e", "token", "external"))
            assertFalse(bad, text.contains(bad))
    }

    @Test fun exportEntryEncryptsAndRoundTrips() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val entry = ConfigBackup.exportEntry(mapOf("ui.theme" to JsonPrimitive("dark")), "controller", key, clearPayload = false)
        assertNull(entry["clearPayload"]); assertTrue(entry["ciphertext"]!!.jsonPrimitive.content.startsWith("v1."))
        val bundle = buildJsonObject { put("version", 1); put("createdAtMs", 1); put("sourceNodeRef", "controller")
            put("wrapping", "raw-aes-256-gcm"); putJsonArray("entries") { add(entry) } }.toString()
        assertEquals("dark", ConfigBackup.payloadFromBundle(bundle, key)["prefs"]!!.jsonObject["ui.theme"]!!.jsonPrimitive.content)
        // Wrong exporter in AAD fails closed.
        val tampered = bundle.replace("\"sourceNodeRef\":\"controller\"", "\"sourceNodeRef\":\"evil\"")
        assertEquals(BridgeError.INTERNAL, err { ConfigBackup.payloadFromBundle(tampered, key) })
    }

    @Test fun refusalScanRejectsForbiddenKeysAndValues() {
        val forbiddenKeys = listOf("twilio", "TWILIO_ACCOUNT_SID", "authToken", "numberPool", "api_key", "apiKey", "accountId",
            "phoneNumber", "accessToken", "password", "externalControlEnabled", "remote.command", "autoDial", "callerId")
        for (k in forbiddenKeys) assertNotNull(k, ConfigBackup.scan(buildJsonObject { put(k, "x") }))
        val forbiddenValues = listOf("+1 (415) 555-0100", "AC0123456789abcdef0123456789abcdef", "eyJhbGciOi.eyJzdWIiOiJ4",
            "-----BEGIN PRIVATE KEY-----", "Bearer abc", "8c1f0a1e-1111-2222-3333-444455556666")
        for (v in forbiddenValues) assertNotNull(v, ConfigBackup.scan(buildJsonObject { put("ui.theme", v) }))
        assertNull(ConfigBackup.scan(payload { put("ui.theme", "dark"); put("ui.soundboardVolume", 40) }))
    }

    @Test fun exportRefusesRatherThanStrips() {
        // A value smuggled into an allowlisted key that fails validation is dropped by the allowlist;
        // anything that slips into the output is refused by the scan (defense in depth).
        assertEquals(BridgeError.INVALID_PARAMETERS, err {
            ConfigBackup.import(payload { put("ui.theme", "dark"); put("twilioAuthToken", "s3cret") }, emptyMap(), true, null, FakeWriter())
        })
    }

    @Test fun dryRunReturnsDiffWithoutWrites() {
        val w = FakeWriter(external = true)
        val r = ConfigBackup.import(payload { put("ui.theme", "system"); put("ui.showTips", false); put("ui.someFutureThing", 1) },
            mapOf("ui.theme" to JsonPrimitive("dark")), dryRun = true, confirmation = null, writer = w, conflictPolicy = "overwrite")
        assertTrue(r["dryRun"]!!.jsonPrimitive.boolean)
        val diff = r["diff"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }
        assertEquals(listOf("ui.theme", "ui.showTips"), diff)
        assertEquals(listOf("ui.someFutureThing"), r["ignored"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(w.writes.isEmpty()); assertTrue("dry run must not touch external control", w.external)
    }

    @Test fun importRequiresConfirmation() {
        assertEquals(BridgeError.CONFIRMATION_REQUIRED, err {
            ConfigBackup.import(payload { put("ui.theme", "dark") }, emptyMap(), dryRun = false, confirmation = null, writer = FakeWriter())
        })
    }

    @Test fun importNeverEnablesExternalControlOrCalls() {
        for (k in listOf("externalControlEnabled", "remote.command", "remoteCommandEnabled", "autoDial", "call.autoStart", "dialOnLaunch")) {
            val w = FakeWriter(external = false)
            assertEquals(k, BridgeError.INVALID_PARAMETERS, err {
                ConfigBackup.import(payload { put("ui.theme", "dark"); put(k, true) }, emptyMap(), false, ConfigBackup.confirmLocally(), w)
            })
            assertTrue(w.writes.isEmpty()); assertFalse(w.external)
        }
        // A clean, confirmed import writes only UI prefs and forces external control OFF.
        val w = FakeWriter(external = true)
        ConfigBackup.import(payload { put("ui.theme", "dark") }, emptyMap(), false, ConfigBackup.confirmLocally(), w)
        assertEquals(setOf("ui.theme"), w.writes.keys); assertFalse(w.external)
    }

    @Test fun conflictPolicySkipKeepsLive() {
        val r = ConfigBackup.plan(payload { put("ui.theme", "system") }, mapOf("ui.theme" to JsonPrimitive("dark")))
        assertTrue(r.changes.isEmpty())
    }

    @Test fun invalidValuesAndBundleErrors() {
        assertEquals(BridgeError.INVALID_PARAMETERS, err { ConfigBackup.plan(payload { put("ui.soundboardVolume", 900) }, emptyMap()) })
        fun bundle(wrapping: String, entries: JsonArrayBuilder.() -> Unit, version: Int = 1) = buildJsonObject {
            put("version", version); put("createdAtMs", 1); put("sourceNodeRef", "c"); put("wrapping", wrapping); putJsonArray("entries", entries) }.toString()
        val clear = buildJsonObject { put("packageName", ConfigBackup.PACKAGE); put("schemaVersion", 1); put("clearPayload", payload { put("ui.theme", "dark") }.toString()) }
        assertEquals("dark", ConfigBackup.payloadFromBundle(bundle("none", { add(clear) }), null)["prefs"]!!.jsonObject["ui.theme"]!!.jsonPrimitive.content)
        assertEquals(BridgeError.INVALID_PARAMETERS, err { ConfigBackup.payloadFromBundle(bundle("none", { add(clear) }, version = 2), null) })
        assertEquals(BridgeError.INVALID_PARAMETERS, err { ConfigBackup.payloadFromBundle(bundle("none", { add(clear); add(clear) }), null) })
        assertEquals(BridgeError.INVALID_PARAMETERS, err { ConfigBackup.payloadFromBundle(bundle("raw-aes-256-gcm", { add(clear) }), null) })
        assertEquals(BridgeError.INVALID_PARAMETERS, err { ConfigBackup.payloadFromBundle(bundle("rot13", { add(clear) }), null) })
        assertEquals(BridgeError.NOT_FOUND, err { ConfigBackup.payloadFromBundle(bundle("none", { }), null) })
    }
}

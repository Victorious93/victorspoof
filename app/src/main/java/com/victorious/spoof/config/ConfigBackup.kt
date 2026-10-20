package com.victorious.spoof.config

import com.victorious.spoof.bridge.BridgeError
import com.victorious.spoof.bridge.BridgeException
import kotlinx.serialization.json.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

/**
 * config.export / config.import, per Victorious93/droidcommand-AI docs/specs/UNIFIED_BACKUP.md (FROZEN, DCA #236).
 *
 * VictorSpoof exports ONLY non-secret UI prefs from [ALLOWLIST]. On top of the allowlist, a refusal scan
 * rejects (never silently strips) any payload that contains a forbidden key or a value that looks like a
 * credential, phone number, account id or token. Import can never enable external control,
 * remote.command, or any call/auto-dial behaviour.
 */
object ConfigBackup {
    const val PACKAGE = "com.victorious.spoof"
    const val SCHEMA_VERSION = 1
    const val BUNDLE_VERSION = 1

    /** Non-secret UI prefs only, each with a value validator. */
    val ALLOWLIST: Map<String, (JsonPrimitive) -> Boolean> = mapOf(
        "ui.theme" to { p -> p.isString && p.content in setOf("dark", "system") },
        "ui.voicePreset" to { p -> p.isString && p.content in com.victorious.spoof.audio.VoicePreset.ids },
        "ui.soundboardVolume" to { p -> p.intOrNull?.let { it in 0..100 } == true },
        "ui.hapticsEnabled" to { p -> p.booleanOrNull != null },
        "ui.showTips" to { p -> p.booleanOrNull != null },
        "ui.textScale" to { p -> p.isString && p.content in setOf("small", "default", "large") },
    )

    // ---------------------------------------------------------------- refusal scan
    private val FORBIDDEN_KEY_PARTS = listOf(
        "twilio", "telnyx", "authtoken", "numberpool", "pool", "token", "secret", "password", "passwd", "passphrase",
        "apikey", "apisecret", "privatekey", "keystore", "signing", "cert", "pem", "bearer", "cookie", "session",
        "accountid", "accountsid", "account", "sid", "phone", "number", "msisdn", "e164", "callerid", "cnam",
        "externalcontrol", "remotecommand", "remote", "autodial", "dial", "call", "pairing", "paired", "nodeid",
        "codeword", "whitelist", "credential",
    )
    private val FORBIDDEN_VALUE_PATTERNS = listOf(
        Regex("(?i)\\b(AC|SK|AP|PN|CA|RE)[0-9a-f]{32}\\b"),                  // Twilio SIDs
        Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"),                   // JWT / access tokens
        Regex("-----BEGIN"),                                                 // PEM
        Regex("(?i)bearer\\s+\\S+"),
        Regex("\\+?\\d[\\d\\s().-]{5,}\\d"),                                 // phone-number-like digit runs
        Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"), // UUID account ids
        Regex("(?i)\\b[0-9a-f]{32,}\\b"),                                    // long hex secrets / node ids
        Regex("[A-Za-z0-9+/_-]{40,}={0,2}"),                                 // long base64 blobs
    )

    private fun normKey(k: String) = k.lowercase().filter { it.isLetterOrDigit() }

    /** Returns a description of the first violation, or null when the element is clean. */
    fun scan(e: JsonElement, path: String = "$"): String? = when (e) {
        is JsonObject -> e.entries.firstNotNullOfOrNull { (k, v) ->
            val nk = normKey(k)
            FORBIDDEN_KEY_PARTS.firstOrNull { nk.contains(it) }?.let { "forbidden key '$k' at $path" } ?: scan(v, "$path.$k")
        }
        is JsonArray -> e.withIndex().firstNotNullOfOrNull { (i, v) -> scan(v, "$path[$i]") }
        is JsonPrimitive -> if (!e.isString) null else
            FORBIDDEN_VALUE_PATTERNS.firstOrNull { it.containsMatchIn(e.content) }?.let { "forbidden value pattern at $path" }
        JsonNull -> null
    }

    private fun refuse(msg: String): Nothing = throw BridgeException(BridgeError.INVALID_PARAMETERS, msg)
    private fun requireClean(e: JsonElement) { scan(e)?.let { refuse("refused: $it") } }

    // ---------------------------------------------------------------- export
    /** Builds the node payload from current prefs: allowlist first, then the refusal scan over the result. */
    fun exportPayload(prefs: Map<String, JsonElement>): JsonObject {
        val out = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            putJsonObject("prefs") {
                for ((k, v) in prefs.toSortedMap()) {
                    val validator = ALLOWLIST[k] ?: continue
                    if (v is JsonPrimitive && validator(v)) put(k, v)
                }
            }
        }
        requireClean(out)
        return out
    }

    /** One UnifiedBackupEntry. Clear payload is local-dev only; otherwise AES-256-GCM with the caller's key. */
    fun exportEntry(prefs: Map<String, JsonElement>, sourceNodeRef: String, key: SecretKey?, clearPayload: Boolean): JsonObject {
        val payload = exportPayload(prefs).toString()
        return buildJsonObject {
            put("packageName", PACKAGE)
            put("schemaVersion", SCHEMA_VERSION)
            if (clearPayload) put("clearPayload", payload)
            else put("ciphertext", encrypt(payload, key ?: refuse("encryption key required"), sourceNodeRef))
        }
    }

    // ---------------------------------------------------------------- import
    data class Change(val key: String, val from: JsonElement?, val to: JsonElement)
    data class ImportPlan(val changes: List<Change>, val ignoredKeys: List<String>) {
        fun toJson(dryRun: Boolean) = buildJsonObject {
            put("dryRun", dryRun)
            putJsonArray("diff") { changes.forEach { c -> add(buildJsonObject { put("key", c.key); put("from", c.from ?: JsonNull); put("to", c.to) }) } }
            putJsonArray("ignored") { ignoredKeys.forEach { add(it) } }
            put("externalControlEnabled", false)
        }
    }

    /** Validates a node payload and computes the diff. Never writes. */
    fun plan(payload: JsonObject, current: Map<String, JsonElement>, conflictPolicy: String = "skip"): ImportPlan {
        requireClean(payload)
        val sv = payload["schemaVersion"]?.jsonPrimitive?.intOrNull ?: refuse("schemaVersion required")
        if (sv != SCHEMA_VERSION) refuse("unsupported schemaVersion $sv")
        if (conflictPolicy !in setOf("skip", "overwrite", "merge-by-id")) refuse("bad conflictPolicy")
        val prefs = payload["prefs"] as? JsonObject ?: refuse("prefs object required")
        val changes = mutableListOf<Change>(); val ignored = mutableListOf<String>()
        for ((k, v) in prefs) {
            val validator = ALLOWLIST[k]
            if (validator == null) { ignored += k; continue }
            if (v !is JsonPrimitive || !validator(v)) refuse("invalid value for $k")
            val live = current[k]
            // Prefs are scalars: skip/merge-by-id keep an existing live value, overwrite replaces it.
            if (live != null && conflictPolicy != "overwrite") continue
            if (live != v) changes += Change(k, live, v)
        }
        return ImportPlan(changes, ignored)
    }

    /** Holder for the result of the on-device confirmation dialog. */
    class LocalConfirmation internal constructor()
    fun confirmLocally() = LocalConfirmation()

    /** Writes prefs only through [PrefsWriter]; it has no way to touch external control or calls. */
    interface PrefsWriter {
        fun writeUiPref(key: String, value: JsonPrimitive)
        /** Called after every non-dry-run import: external control is forced OFF. */
        fun forceExternalControlOff()
    }

    fun import(payload: JsonObject, current: Map<String, JsonElement>, dryRun: Boolean, confirmation: LocalConfirmation?,
               writer: PrefsWriter, conflictPolicy: String = "skip"): JsonObject {
        val p = plan(payload, current, conflictPolicy)
        if (dryRun) return p.toJson(true)
        if (confirmation == null) throw BridgeException(BridgeError.CONFIRMATION_REQUIRED, "config.import needs on-device confirmation")
        p.changes.forEach { c -> require(c.key in ALLOWLIST); writer.writeUiPref(c.key, c.to as JsonPrimitive) }
        writer.forceExternalControlOff()
        return p.toJson(false)
    }

    /** Extracts this node's payload from a UnifiedFleetBackup v1 bundle. */
    fun payloadFromBundle(bundleJson: String, key: SecretKey?): JsonObject {
        val b = runCatching { Json.parseToJsonElement(bundleJson) }.getOrNull() as? JsonObject ?: refuse("bundle is not JSON")
        if (b["version"]?.jsonPrimitive?.intOrNull != BUNDLE_VERSION) refuse("unknown bundle version")
        val wrapping = b["wrapping"]?.jsonPrimitive?.contentOrNull
        if (wrapping !in setOf("none", "raw-aes-256-gcm", "android-keystore")) refuse("unknown wrapping")
        val source = b["sourceNodeRef"]?.jsonPrimitive?.contentOrNull ?: refuse("sourceNodeRef required")
        val entries = b["entries"] as? JsonArray ?: refuse("entries required")
        val names = entries.map { (it as? JsonObject)?.get("packageName")?.jsonPrimitive?.contentOrNull ?: refuse("entry.packageName required") }
        if (names.size != names.toSet().size) refuse("duplicate packageName")
        val entry = entries.map { it.jsonObject }.firstOrNull { it["packageName"]!!.jsonPrimitive.content == PACKAGE }
            ?: throw BridgeException(BridgeError.NOT_FOUND, "no entry for $PACKAGE")
        val clear = entry["clearPayload"]?.jsonPrimitive?.contentOrNull
        val cipher = entry["ciphertext"]?.jsonPrimitive?.contentOrNull
        if ((clear == null) == (cipher == null)) refuse("exactly one of ciphertext/clearPayload")
        if (wrapping == "none" && clear == null) refuse("wrapping=none requires clearPayload")
        if (wrapping != "none" && cipher == null) refuse("wrapping $wrapping requires ciphertext")
        val sv = entry["schemaVersion"]?.jsonPrimitive?.intOrNull ?: refuse("schemaVersion required")
        val text = clear ?: try { decrypt(cipher!!, key ?: refuse("key required"), PACKAGE, sv, source) }
            catch (e: BridgeException) { throw e } catch (e: Exception) { throw BridgeException(BridgeError.INTERNAL, "decrypt failed") }
        return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: refuse("payload not JSON")
    }

    // ---------------------------------------------------------------- crypto (AES-256-GCM, v1.<iv>.<ct>)
    private val b64e = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()
    private fun aad(pkg: String, sv: Int, src: String) = "$pkg|$sv|$src".toByteArray()

    fun encrypt(plain: String, key: SecretKey, sourceNodeRef: String): String {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad(PACKAGE, SCHEMA_VERSION, sourceNodeRef))
        return "v1." + b64e.encodeToString(iv) + "." + b64e.encodeToString(c.doFinal(plain.toByteArray()))
    }

    fun decrypt(record: String, key: SecretKey, pkg: String, sv: Int, src: String): String {
        val parts = record.split('.')
        if (parts.size != 3 || parts[0] != "v1") refuse("bad ciphertext format")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b64d.decode(parts[1])))
        c.updateAAD(aad(pkg, sv, src))
        return String(c.doFinal(b64d.decode(parts[2])))
    }
}

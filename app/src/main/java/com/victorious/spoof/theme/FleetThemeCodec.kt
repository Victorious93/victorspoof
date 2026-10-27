package com.victorious.spoof.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** FLEET_THEME schemaVersion 1 (victorlaunch docs/specs/FLEET_THEME.md). Read-only contract. */
object FleetThemeContract {
    const val SCHEMA_VERSION = 1
    const val AUTHORITY = "com.victorious.launch.theme"
    const val URI = "content://$AUTHORITY/current"
    const val PROVIDER_PACKAGE = "com.victorious.launch"
    const val ACTION_CHANGED = "com.victorious.launch.action.FLEET_THEME_CHANGED"

    /** VictorLaunch signing cert SHA-256, from victorlaunch FleetPins.VICTORLAUNCH_DEBUG (referenced by the spec). */
    const val VICTORLAUNCH_CERT =
        "B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69"
    val PINNED_CERTS: Set<String> = setOf(VICTORLAUNCH_CERT)

    fun normalizeCert(c: String) = c.replace(":", "").replace(" ", "").lowercase()
    fun isPinned(certs: List<String>, pins: Set<String> = PINNED_CERTS): Boolean {
        val p = pins.map(::normalizeCert).toSet()
        return certs.isNotEmpty() && certs.any { normalizeCert(it) in p }
    }
}

/** Colors as ARGB longs. */
data class FleetPalette(
    val background: Long, val surface: Long, val primary: Long, val secondary: Long,
    val onBackground: Long, val muted: Long, val danger: Long, val stale: Long, val tile: Long,
)

data class FleetThemeSnapshot(
    val mode: String, val iconStyle: String, val updatedAtMs: Long, val palette: FleetPalette,
)

sealed interface FleetThemeState {
    /** VictorSpoof's built-in theme. */
    data class BuiltIn(val reason: String) : FleetThemeState
    data class Fleet(val snapshot: FleetThemeSnapshot) : FleetThemeState
}

object FleetThemeCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val HEX = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    /** `#RRGGBB` or `#AARRGGBB` -> ARGB, or null. */
    fun parseHex(s: String?): Long? {
        if (s == null || !HEX.matches(s)) return null
        val v = s.substring(1).toLong(16)
        return if (s.length == 7) 0xFF000000L or v else v
    }

    /** Returns null on malformed JSON, schemaVersion != 1, or any missing/invalid palette color. */
    fun decode(raw: String?): FleetThemeSnapshot? {
        if (raw.isNullOrBlank()) return null
        val o: JsonObject = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val version = runCatching { o["schemaVersion"]?.jsonPrimitive?.intOrNull }.getOrNull()
        if (version != FleetThemeContract.SCHEMA_VERSION) return null
        val p = runCatching { o["palette"]?.jsonObject }.getOrNull() ?: return null
        fun c(k: String) = parseHex(runCatching { p[k]?.jsonPrimitive?.contentOrNull }.getOrNull())
        fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
        val palette = FleetPalette(
            background = c("background") ?: return null, surface = c("surface") ?: return null,
            primary = c("primary") ?: return null, secondary = c("secondary") ?: return null,
            onBackground = c("onBackground") ?: return null, muted = c("muted") ?: return null,
            danger = c("danger") ?: return null, stale = c("stale") ?: return null, tile = c("tile") ?: return null,
        )
        val updated = runCatching { o["updatedAtMs"]?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L
        return FleetThemeSnapshot(s("mode"), s("iconStyle"), updated, palette)
    }
}

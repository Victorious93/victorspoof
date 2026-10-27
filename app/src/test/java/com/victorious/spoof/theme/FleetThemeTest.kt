package com.victorious.spoof.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetThemeTest {
    private fun json(version: Int = 1, tile: String = "#CC1A1D2B") = """
        {"schemaVersion":$version,"mode":"NEON_FLEET","iconStyle":"SYSTEM","iconPackPackage":"","updatedAtMs":42,
         "future":"x","palette":{"background":"#FF0B0C12","surface":"#161821","primary":"#FF00E5FF",
         "secondary":"#FFE840FF","onBackground":"#FFF5F7FF","muted":"#FF9AA3B5","danger":"#FFFF6B8A",
         "stale":"#FFFFC14D","tile":"$tile"}}
    """.trimIndent()

    private val launchCert = FleetThemeContract.normalizeCert(FleetThemeContract.VICTORLAUNCH_CERT)

    private class Fake(
        val pkg: String? = FleetThemeContract.PROVIDER_PACKAGE,
        val certs: List<String> = emptyList(),
        val body: () -> String? = { null },
    ) : FleetThemeSource {
        var queried = false
        override fun providerPackage() = pkg
        override fun signingCerts(pkg: String) = certs
        override fun queryJson(): String? { queried = true; return body() }
    }

    @Test fun parsesV1Snapshot() {
        val s = FleetThemeCodec.decode(json())!!
        assertEquals("NEON_FLEET", s.mode)
        assertEquals(42L, s.updatedAtMs)
        assertEquals(0xFF00E5FFL, s.palette.primary)
        assertEquals(0xFF161821L, s.palette.surface) // #RRGGBB gets opaque alpha
        assertEquals(0xCC1A1D2BL, s.palette.tile)
    }

    @Test fun rejectsOtherSchemaVersions() {
        assertNull(FleetThemeCodec.decode(json(version = 2)))
        assertNull(FleetThemeCodec.decode(json(version = 0)))
        assertNull(FleetThemeCodec.decode(json().replace("\"schemaVersion\":1,", "")))
    }

    @Test fun rejectsMalformed() {
        assertNull(FleetThemeCodec.decode(null))
        assertNull(FleetThemeCodec.decode("not json"))
        assertNull(FleetThemeCodec.decode(json(tile = "red")))
        assertNull(FleetThemeCodec.decode(json(tile = "#12345")))
    }

    @Test fun pinnedProviderYieldsFleetTheme() {
        val s = FleetThemeResolver(Fake(certs = listOf(launchCert), body = { json() })).resolve()
        assertTrue(s is FleetThemeState.Fleet)
    }

    @Test fun certPinMismatchFallsBackWithoutQuerying() {
        val f = Fake(certs = listOf("00".repeat(32)), body = { json() })
        assertTrue(FleetThemeResolver(f).resolve() is FleetThemeState.BuiltIn)
        assertTrue(!f.queried)
    }

    @Test fun wrongPackageOwningAuthorityFallsBack() {
        val f = Fake(pkg = "evil.app", certs = listOf(launchCert), body = { json() })
        assertTrue(FleetThemeResolver(f).resolve() is FleetThemeState.BuiltIn)
        assertTrue(!f.queried)
    }

    @Test fun missingProviderFallsBack() {
        assertTrue(FleetThemeResolver(Fake(pkg = null)).resolve() is FleetThemeState.BuiltIn)
    }

    @Test fun securityExceptionFallsBack() {
        val f = Fake(certs = listOf(launchCert), body = { throw SecurityException("not pinned") })
        val s = FleetThemeResolver(f).resolve()
        assertTrue(s is FleetThemeState.BuiltIn && s.reason.startsWith("SecurityException"))
    }

    @Test fun unsupportedSchemaFromPinnedProviderFallsBack() {
        val f = Fake(certs = listOf(launchCert), body = { json(version = 2) })
        assertTrue(FleetThemeResolver(f).resolve() is FleetThemeState.BuiltIn)
    }

    @Test fun pinComparisonIgnoresColonsAndCase() {
        assertTrue(FleetThemeContract.isPinned(listOf(FleetThemeContract.VICTORLAUNCH_CERT.lowercase())))
        assertTrue(!FleetThemeContract.isPinned(emptyList()))
    }
}

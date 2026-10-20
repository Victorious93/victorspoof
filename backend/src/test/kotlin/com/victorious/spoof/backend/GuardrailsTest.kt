package com.victorious.spoof.backend

import com.victorious.spoof.backend.guardrails.*
import com.victorious.spoof.backend.twilio.TwilioConfig
import com.victorious.spoof.backend.twilio.TwilioTokenIssuer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*

class BlocklistTest {
    private val b = Blocklist()
    @Test fun emergencyAndCrisisBlocked() {
        assertEquals(BlockReason.EMERGENCY, b.check("911"))
        assertEquals(BlockReason.EMERGENCY, b.check("1-911"))
        assertEquals(BlockReason.CRISIS_LINE, b.check("988"))
    }
    @Test fun allN11Blocked() {
        for (n in listOf("211", "311", "411", "511", "611", "711", "811")) assertEquals(BlockReason.N11_SERVICE, b.check(n), n)
    }
    @Test fun shortCodesBlocked() { assertEquals(BlockReason.SHORT_CODE, b.check("55555")) }
    @Test fun seededInstitutionsBlocked() {
        assertEquals(BlockReason.GOVERNMENT, b.check("(800) 829-1040"))
        assertEquals(BlockReason.POLICE, b.check("+1 202 324 3000"))
        assertEquals(BlockReason.BANK, b.check("1-800-935-9935"))
    }
    @Test fun extraHospitalEntry() {
        assertEquals(BlockReason.HOSPITAL, Blocklist(mapOf("+12125551234" to BlockReason.HOSPITAL)).check("212-555-1234"))
    }
    @Test fun ordinaryNumberAllowed() { assertNull(b.check("+1 (415) 555-0134")) }
    @Test fun garbageRejected() { assertEquals(BlockReason.INVALID, b.check("12345678")) }
}

class DisplayNameFilterTest {
    @Test fun blocksAuthorities() {
        for (n in listOf("Police Dept", "IRS Agent", "F.B.I.", "Chase Bank", "P0L1CE", "City Hospital", "ICE", "Social Security", "W e l l s Fargo"))
            assertFalse(DisplayNameFilter.isAllowed(n), n)
    }
    @Test fun allowsFunNames() {
        for (n in listOf("Pizza Wizard", "Alice", "Nice Llama", "Captain Prank", "Uncle Bob"))
            assertTrue(DisplayNameFilter.isAllowed(n), n)
    }
}

class RateLimiterTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC)
    @Test fun perTargetLimitIsThree() {
        val r = RateLimiter(InMemoryCallLedger(), clock)
        repeat(3) { assertIs<RateDecision.Allowed>(r.consume("a", "+14155550100")) }
        assertEquals(RateDecision.Denied("target_daily_limit"), r.consume("a", "+14155550100"))
        assertIs<RateDecision.Allowed>(r.consume("a", "+14155550101"))
    }
    @Test fun dailyLimitIsTen() {
        val r = RateLimiter(InMemoryCallLedger(), clock)
        repeat(10) { assertIs<RateDecision.Allowed>(r.consume("a", "+1415555%04d".format(it))) }
        assertEquals(RateDecision.Denied("daily_limit"), r.consume("a", "+14155559999"))
        assertIs<RateDecision.Allowed>(r.consume("b", "+14155559999"))
    }
    @Test fun maxDurationTwoMinutes() {
        val d = RateLimiter(InMemoryCallLedger(), clock).check("a", "+14155550100")
        assertEquals(120, (d as RateDecision.Allowed).maxCallSeconds)
    }
    @Test fun resetsNextDay() {
        val ledger = InMemoryCallLedger()
        val r1 = RateLimiter(ledger, clock)
        repeat(10) { r1.consume("a", "+1415555%04d".format(it)) }
        val r2 = RateLimiter(ledger, Clock.offset(clock, java.time.Duration.ofDays(1)))
        assertIs<RateDecision.Allowed>(r2.consume("a", "+14155550000"))
    }
}

class CallAuthorizerTest {
    private val pool = listOf(PoolNumber("+15005550006", "Pizza Wizard", true), PoolNumber("+15005550007", "IRS Refunds", true))
    private fun auth() = CallAuthorizer(Blocklist(), RateLimiter(InMemoryCallLedger()), CallerIdPolicy { pool })
    private val ok = AccountState("acct", ageAttested = true, aupAccepted = true, verifiedNumber = "+14155550199")

    @Test fun requiresAgeAndAup() {
        assertEquals("age_gate_required", auth().authorize(ok.copy(ageAttested = false), "+14155550100", CallerIdMode.POOL, false).reason)
        assertEquals("aup_not_accepted", auth().authorize(ok.copy(aupAccepted = false), "+14155550100", CallerIdMode.POOL, false).reason)
    }
    @Test fun blocks911() { assertEquals("blocked_target:emergency", auth().authorize(ok, "911", CallerIdMode.POOL, false).reason) }
    @Test fun poolChoiceNeverUsesFilteredName() {
        repeat(50) {
            val r = auth().authorize(ok, "+14155550100", CallerIdMode.POOL, false)
            assertTrue(r.allowed); assertEquals("+15005550006", r.callerId!!.e164)
        }
    }
    @Test fun ownVerifiedNumberUsed() {
        val r = auth().authorize(ok, "+14155550100", CallerIdMode.OWN_VERIFIED, true)
        assertEquals("+14155550199", r.callerId!!.e164); assertTrue(r.recordingAnnouncementRequired); assertEquals(120, r.maxCallSeconds)
    }
    @Test fun ownModeWithoutVerificationDenied() {
        assertEquals("own_number_not_verified", auth().authorize(ok.copy(verifiedNumber = null), "+14155550100", CallerIdMode.OWN_VERIFIED, false).reason)
    }
}

class TwilioConfigTest {
    @Test fun missingEnvMeansNotConfigured() {
        assertNull(TwilioConfig.fromEnv { null })
        assertNull(TwilioConfig.fromEnv { if (it == "TWILIO_ACCOUNT_SID") "AC" else null })
        assertFalse(TwilioTokenIssuer(null).configured)
        assertNull(TwilioTokenIssuer(null).issue("x"))
    }
}

class TwilioEnvNamesTest {
    @Test fun canonicalNamesAccepted() {
        val env = mapOf("TWILIO_ACCOUNT_SID" to "ACx", "TWILIO_API_KEY" to "SKx", "TWILIO_API_SECRET" to "s", "TWILIO_TWIML_APP_SID" to "APx")
        assertNotNull(TwilioConfig.fromEnv { env[it] })
        assertEquals(emptyList(), TwilioConfig.missing { env[it] })
        assertEquals(listOf("TWILIO_API_SECRET"), TwilioConfig.missing { (env - "TWILIO_API_SECRET")[it] })
        assertFalse(TwilioConfig.fromEnv { env[it] }.toString().contains("\"s\""))
    }
    @Test fun issuedTokenIsShortLivedVoiceJwt() {
        val cfg = TwilioConfig("AC" + "0".repeat(32), "SK" + "0".repeat(32), "test-secret-not-real-0123456789abcdef", "AP" + "0".repeat(32))
        val jwt = TwilioTokenIssuer(cfg, 600).issue("acct-1")!!
        val body = String(java.util.Base64.getUrlDecoder().decode(jwt.split('.')[1]))
        assertTrue(body.contains("\"identity\":\"acct-1\""), body)
        assertTrue(body.contains("outgoing"), body)
        val exp = Regex("\"exp\":(\\d+)").find(body)!!.groupValues[1].toLong()
        val nbfOrNow = System.currentTimeMillis() / 1000
        assertTrue(exp - nbfOrNow in 1..600, "ttl ${exp - nbfOrNow}")
    }
}

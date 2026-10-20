package com.victorious.spoof.backend

import com.victorious.spoof.backend.db.InMemoryStore
import com.victorious.spoof.backend.guardrails.PoolNumber
import com.victorious.spoof.backend.verify.TwilioVerifyProvider
import com.victorious.spoof.backend.verify.VerificationService
import com.victorious.spoof.backend.verify.VerifyResult
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlin.test.*

class VerifyTest {
    private val store = InMemoryStore(listOf(PoolNumber(POOL_E164, "Pizza Wizard", true)))
    private fun acct() = store.createAccount("u${System.nanoTime()}@example.com", "x", true)!!

    @Test fun notConfiguredIs503() {
        assertEquals(VerifyResult.Fail(503, "verify_not_configured"), VerificationService(store, null).start(acct(), "+14155550123"))
        assertNull(TwilioVerifyProvider.fromEnv(FAKE_TWILIO) { null })
        assertNull(TwilioVerifyProvider.fromEnv(null) { "VA123" })
    }

    @Test fun happyPathSetsVerifiedNumber() {
        val otp = FakeOtp(); val v = VerificationService(store, otp); val a = acct()
        assertEquals(VerifyResult.Sent, v.start(a, "(415) 555-0123"))
        assertEquals(listOf("+14155550123"), otp.sent)
        assertEquals(VerifyResult.Fail(400, "wrong_code"), v.check(a, "+14155550123", "000000"))
        assertNull(store.account(a)!!.verifiedNumber, "wrong code must not verify")
        assertEquals(VerifyResult.Verified("+14155550123"), v.check(a, "+14155550123", "123456"))
        assertEquals("+14155550123", store.account(a)!!.verifiedNumber)
    }

    @Test fun cannotCheckANumberYouDidNotStart() {
        val v = VerificationService(store, FakeOtp()); val a = acct()
        v.start(a, "+14155550123")
        assertEquals(VerifyResult.Fail(409, "no_pending_verification"), v.check(a, "+14155550999", "123456"))
        assertEquals(VerifyResult.Fail(409, "no_pending_verification"), v.check(acct(), "+14155550123", "123456"))
    }

    @Test fun blockedAndPoolNumbersCannotBeVerified() {
        val v = VerificationService(store, FakeOtp()); val a = acct()
        assertEquals(VerifyResult.Fail(400, "blocked_number:emergency"), v.start(a, "911"))
        assertEquals(VerifyResult.Fail(400, "blocked_number:bank"), v.start(a, "1-800-935-9935"))
        assertEquals(VerifyResult.Fail(400, "pool_number"), v.start(a, POOL_E164))
    }

    @Test fun sendRateLimits() {
        val otp = FakeOtp(); val v = VerificationService(store, otp); val a = acct()
        repeat(3) { assertEquals(VerifyResult.Sent, v.start(a, "+1415555010$it")) }
        assertEquals(VerifyResult.Fail(429, "too_many_codes"), v.start(a, "+14155550109"))
        assertEquals(3, otp.sent.size)
    }

    @Test fun perNumberSendLimitAcrossAccounts() {
        val v = VerificationService(store, FakeOtp())
        repeat(3) { assertEquals(VerifyResult.Sent, v.start(acct(), "+14155550177")) }
        assertEquals(VerifyResult.Fail(429, "too_many_codes"), v.start(acct(), "+14155550177"))
    }

    @Test fun checkAttemptsLimited() {
        val otp = FakeOtp(); val v = VerificationService(store, otp); val a = acct()
        v.start(a, "+14155550123")
        repeat(5) { assertEquals(VerifyResult.Fail(400, "wrong_code"), v.check(a, "+14155550123", "00000$it")) }
        assertEquals(VerifyResult.Fail(429, "too_many_attempts"), v.check(a, "+14155550123", "123456"))
        assertEquals(5, otp.checks)
    }

    @Test fun onlyVerifiedNumberBecomesCallerIdOption() = testApplication {
        application { module(testServices()) }
        val t = client.onboard()
        assertFalse(client.get("/v1/me") { bearerAuth(t) }.bodyAsText().contains("OWN_VERIFIED"))
        val denied = client.json("/v1/calls/authorize", """{"target":"+14155550100","mode":"OWN_VERIFIED"}""", t)
        assertTrue(denied.bodyAsText().contains("own_number_not_verified"))
        assertEquals(HttpStatusCode.OK, client.json("/v1/verify/start", """{"phone":"+14155550123"}""", t).status)
        assertEquals(HttpStatusCode.OK, client.json("/v1/verify/check", """{"phone":"+14155550123","code":"123456"}""", t).status)
        val me = client.get("/v1/me") { bearerAuth(t) }.bodyAsText()
        assertTrue(me.contains("OWN_VERIFIED")); assertFalse(me.contains("4155550123"))
        assertEquals(HttpStatusCode.OK, client.json("/v1/calls/authorize", """{"target":"+14155550100","mode":"OWN_VERIFIED"}""", t).status)
    }
}

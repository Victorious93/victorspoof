package com.victorious.spoof.backend

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.victorious.spoof.backend.auth.AuthResult
import com.victorious.spoof.backend.auth.AuthService
import com.victorious.spoof.backend.auth.JwtConfig
import com.victorious.spoof.backend.auth.Passwords
import com.victorious.spoof.backend.db.InMemoryStore
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import kotlin.test.*

class AuthTest {
    private val ip = "10.0.0.1"
    private fun svc(clock: Clock = Clock.systemUTC()) = AuthService(InMemoryStore(), TEST_JWT, clock)

    @Test fun bcryptHashesAndVerifies() {
        val h = Passwords.hash("correct horse battery")
        assertTrue(h.startsWith("\$2a\$12\$"), h)
        assertTrue(Passwords.verify("correct horse battery", h))
        assertFalse(Passwords.verify("wrong", h))
    }

    @Test fun signupLoginAndDuplicate() {
        val s = svc()
        assertIs<AuthResult.Ok>(s.signup("Don@Example.com", "correct horse battery", true, true, ip))
        assertEquals(AuthResult.Fail(409, "email_taken"), s.signup("don@example.com", "another long pass", true, true, ip))
        assertIs<AuthResult.Ok>(s.login("DON@example.com", "correct horse battery", ip))
        assertEquals(AuthResult.Fail(401, "invalid_credentials"), s.login("don@example.com", "nope nope nope", ip))
        assertEquals(AuthResult.Fail(401, "invalid_credentials"), s.login("ghost@example.com", "nope nope nope", ip))
    }

    @Test fun weakPasswordAndBadEmailRejected() {
        assertEquals(AuthResult.Fail(400, "weak_password"), svc().signup("a@b.co", "short", true, true, ip))
        assertEquals(AuthResult.Fail(400, "invalid_email"), svc().signup("not-an-email", "correct horse battery", true, true, ip))
    }

    @Test fun loginBruteForceLimited() {
        val s = svc()
        s.signup("don@example.com", "correct horse battery", true, true, ip)
        repeat(5) { assertEquals(AuthResult.Fail(401, "invalid_credentials"), s.login("don@example.com", "bad guess $it", ip)) }
        assertEquals(AuthResult.Fail(429, "too_many_attempts"), s.login("don@example.com", "bad guess 6", ip))
    }

    @Test fun refreshRotatesAndIsSingleUse() {
        val s = svc()
        val a = s.signup("don@example.com", "correct horse battery", true, true, ip) as AuthResult.Ok
        val b = s.refresh(a.refreshToken) as AuthResult.Ok
        assertNotEquals(a.refreshToken, b.refreshToken)
        assertEquals(AuthResult.Fail(401, "invalid_refresh"), s.refresh(a.refreshToken))
        s.logout(b.accountId)
        assertEquals(AuthResult.Fail(401, "invalid_refresh"), s.refresh(b.refreshToken))
    }

    @Test fun accessTokenIsShortLived() {
        val ok = svc().issue("acct") as AuthResult.Ok
        assertEquals(900, ok.expiresIn)
        val d = JWT.decode(ok.accessToken)
        assertTrue(Duration.between(d.issuedAtAsInstant, d.expiresAtAsInstant) <= Duration.ofMinutes(15))
    }

    @Test fun expiredRefreshRejected() {
        val t0 = Instant.parse("2026-10-20T00:00:00Z")
        val store = InMemoryStore()
        val a = AuthService(store, TEST_JWT, Clock.fixed(t0, ZoneOffset.UTC)).issue("acct")
        val later = AuthService(store, TEST_JWT, Clock.fixed(t0.plus(Duration.ofDays(31)), ZoneOffset.UTC))
        assertEquals(AuthResult.Fail(401, "invalid_refresh"), later.refresh(a.refreshToken))
    }

    @Test fun apiRejectsForgedExpiredAndRefreshTokensAsBearer() = testApplication {
        application { module(testServices()) }
        val forged = JWT.create().withIssuer("victorspoof").withSubject("x").withClaim("typ", "access")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000)).sign(Algorithm.HMAC256("some-other-secret-some-other-secret"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(forged) }.status)
        val expired = JWT.create().withIssuer("victorspoof").withSubject("x").withClaim("typ", "access")
            .withExpiresAt(Date(System.currentTimeMillis() - 120_000)).sign(Algorithm.HMAC256(TEST_JWT.secret))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(expired) }.status)
        val r = client.json("/v1/auth/signup", """{"email":"don@example.com","password":"correct horse battery","ageAttested18":true,"disclaimerAccepted":true}""")
        val refresh = field(r.bodyAsText(), "refreshToken")!!
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/me") { bearerAuth(refresh) }.status)
        val access = field(r.bodyAsText(), "accessToken")!!
        assertEquals(HttpStatusCode.OK, client.get("/v1/me") { bearerAuth(access) }.status)
        val again = client.json("/v1/auth/refresh", """{"refreshToken":"$refresh"}""")
        assertEquals(HttpStatusCode.OK, again.status)
    }

    @Test fun aupAcceptanceLoggedServerSide() = testApplication {
        val s = testServices()
        application { module(s) }
        client.onboard()
        assertEquals(1, (s.store as InMemoryStore).aupLog.size)
    }

    @Test fun jwtSecretFromEnv() {
        assertTrue(JwtConfig.fromEnv { null }.ephemeral)
        assertTrue(JwtConfig.fromEnv { "too-short" }.ephemeral)
        assertFalse(JwtConfig.fromEnv { "x".repeat(40) }.ephemeral)
    }
}

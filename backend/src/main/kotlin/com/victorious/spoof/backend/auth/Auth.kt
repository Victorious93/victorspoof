package com.victorious.spoof.backend.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.victorious.spoof.backend.db.Store
import com.victorious.spoof.backend.guardrails.WindowLimiter
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.Date

object Passwords {
    private const val COST = 12
    fun hash(pw: String): String = BCrypt.withDefaults().hashToString(COST, pw.toCharArray())
    fun verify(pw: String, hash: String): Boolean = BCrypt.verifyer().verify(pw.toCharArray(), hash).verified
    /** Precomputed hash so unknown emails cost the same as wrong passwords (no user enumeration by timing). */
    val DUMMY: String by lazy { hash("victorspoof-dummy-password") }
    fun acceptable(pw: String) = pw.length in 10..128
}

data class JwtConfig(val secret: String, val issuer: String = "victorspoof", val accessTtl: Duration = Duration.ofMinutes(15),
                     val refreshTtl: Duration = Duration.ofDays(30), val ephemeral: Boolean = false) {
    companion object {
        /** VS_JWT_SECRET (>= 32 chars). Without it a random per-process secret is used and every restart logs everyone out. */
        fun fromEnv(env: (String) -> String? = System::getenv): JwtConfig {
            val s = env("VS_JWT_SECRET")
            if (s != null && s.length >= 32) return JwtConfig(s)
            val rnd = ByteArray(48).also(SecureRandom()::nextBytes)
            return JwtConfig(Base64.getEncoder().encodeToString(rnd), ephemeral = true)
        }
    }
}

sealed interface AuthResult {
    data class Ok(val accountId: String, val accessToken: String, val refreshToken: String, val expiresIn: Long) : AuthResult
    data class Fail(val status: Int, val error: String) : AuthResult
}

class AuthService(private val store: Store, val jwt: JwtConfig, private val clock: Clock = Clock.systemUTC()) {
    private val algorithm = Algorithm.HMAC256(jwt.secret)
    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(jwt.issuer).withClaim("typ", "access").build()
    /** 5 failed logins per email per 15 min, 20 per IP per 15 min. */
    private val loginByEmail = WindowLimiter(5, Duration.ofMinutes(15), clock)
    private val loginByIp = WindowLimiter(20, Duration.ofMinutes(15), clock)
    private val signupByIp = WindowLimiter(5, Duration.ofHours(1), clock)
    private val rnd = SecureRandom()
    private val emailRe = Regex("^[^@\\s]{1,64}@[^@\\s]{1,190}\\.[^@\\s]{2,}$")

    fun signup(email: String, password: String, ageAttested18: Boolean, disclaimerAccepted: Boolean, ip: String): AuthResult {
        if (!ageAttested18) return AuthResult.Fail(403, "must_be_18")
        if (!disclaimerAccepted) return AuthResult.Fail(400, "disclaimer_required")
        if (!emailRe.matches(email.trim())) return AuthResult.Fail(400, "invalid_email")
        if (!Passwords.acceptable(password)) return AuthResult.Fail(400, "weak_password")
        if (!signupByIp.tryAcquire(ip)) return AuthResult.Fail(429, "too_many_signups")
        val id = store.createAccount(email.trim(), Passwords.hash(password), true) ?: return AuthResult.Fail(409, "email_taken")
        return issue(id)
    }

    fun login(email: String, password: String, ip: String): AuthResult {
        val key = email.trim().lowercase()
        val creds = store.credentials(key)
        val ok = Passwords.verify(password, creds?.passwordHash ?: Passwords.DUMMY) && creds != null
        if (ok) { loginByEmail.reset(key); return issue(creds!!.accountId) }
        if (!loginByEmail.tryAcquire(key) || !loginByIp.tryAcquire(ip)) return AuthResult.Fail(429, "too_many_attempts")
        return AuthResult.Fail(401, "invalid_credentials")
    }

    /** Refresh tokens are single-use and rotated on every refresh. */
    fun refresh(refreshToken: String): AuthResult {
        val acct = store.consumeRefresh(sha256(refreshToken), clock.instant()) ?: return AuthResult.Fail(401, "invalid_refresh")
        return issue(acct)
    }

    fun logout(accountId: String) = store.revokeAllRefresh(accountId)

    fun issue(accountId: String): AuthResult.Ok {
        val now = clock.instant()
        val access = JWT.create().withIssuer(jwt.issuer).withSubject(accountId).withClaim("typ", "access")
            .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plus(jwt.accessTtl))).sign(algorithm)
        val refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(rnd::nextBytes))
        store.saveRefresh(sha256(refresh), accountId, now.plus(jwt.refreshTtl))
        return AuthResult.Ok(accountId, access, refresh, jwt.accessTtl.seconds)
    }

    companion object {
        fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

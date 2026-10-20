package com.victorious.spoof.backend

import com.victorious.spoof.backend.auth.AuthResult
import com.victorious.spoof.backend.auth.AuthService
import com.victorious.spoof.backend.auth.JwtConfig
import com.victorious.spoof.backend.db.CallTicket
import com.victorious.spoof.backend.db.InMemoryStore
import com.victorious.spoof.backend.db.PostgresStore
import com.victorious.spoof.backend.db.Store
import com.victorious.spoof.backend.guardrails.*
import com.victorious.spoof.backend.twilio.TwilioConfig
import com.victorious.spoof.backend.twilio.TwilioTokenIssuer
import com.victorious.spoof.backend.twilio.VoiceWebhook
import com.victorious.spoof.backend.twilio.WebhookConfig
import com.victorious.spoof.backend.verify.OtpProvider
import com.victorious.spoof.backend.verify.TwilioVerifyProvider
import com.victorious.spoof.backend.verify.VerificationService
import com.victorious.spoof.backend.verify.VerifyResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.util.toMap
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64

const val AUP_VERSION = "2026-10-v1"

@Serializable data class SignupRequest(val email: String, val password: String, val ageAttested18: Boolean, val disclaimerAccepted: Boolean)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class SessionResponse(val accountId: String, val accessToken: String, val refreshToken: String, val expiresIn: Long)
@Serializable data class AupRequest(val version: String)
/** Note: there is deliberately NO caller-ID number or name field. The backend chooses it. */
@Serializable data class AuthorizeCallRequest(val target: String, val mode: CallerIdMode, val recording: Boolean = false)
@Serializable data class TokenResponse(val token: String, val ttlSeconds: Int)
@Serializable data class ErrorResponse(val error: String, val missing: List<String> = emptyList())
@Serializable data class VerifyStartRequest(val phone: String)
@Serializable data class VerifyCheckRequest(val phone: String, val code: String)
@Serializable data class MeResponse(val accountId: String, val aupAccepted: Boolean, val verifiedNumberMasked: String?, val callerIdOptions: List<String>)

class Services(
    val store: Store,
    val tokens: TwilioTokenIssuer,
    jwt: JwtConfig = JwtConfig.fromEnv { null },
    otp: OtpProvider? = null,
    webhookConfig: WebhookConfig? = null,
    val webhook: VoiceWebhook = VoiceWebhook(store, webhookConfig),
    val clock: Clock = Clock.systemUTC(),
    val missingTwilioEnv: List<String> = emptyList(),
) {
    val rateLimiter = RateLimiter(store.ledger, clock)
    val authorizer = CallAuthorizer(Blocklist(), rateLimiter, CallerIdPolicy { store.pool() })
    val auth = AuthService(store, jwt, clock)
    val verification = VerificationService(store, otp, clock = clock)
    /** Max 10 Voice tokens per account per hour; each token is good for one 10-minute window. */
    val tokenLimiter = WindowLimiter(10, Duration.ofHours(1), clock)
}

fun main() {
    val store = PostgresStore.fromEnv() ?: InMemoryStore().also { println("WARN: DATABASE_URL not set, using in-memory store") }
    val twilio = TwilioConfig.fromEnv()
    val missing = TwilioConfig.missing()
    if (twilio == null) println("WARN: Twilio not configured; /v1/twilio/token returns 503. Missing: $missing")
    val jwt = JwtConfig.fromEnv()
    if (jwt.ephemeral) println("WARN: VS_JWT_SECRET unset or < 32 chars; using a random per-process secret")
    val otp = TwilioVerifyProvider.fromEnv(twilio)
    if (otp == null) println("WARN: TWILIO_VERIFY_SID (or Twilio API key) unset; own-number verification returns 503")
    val hook = WebhookConfig.fromEnv()
    if (hook == null) println("WARN: TWILIO_AUTH_TOKEN / PUBLIC_BASE_URL unset; the voice webhook rejects every call")
    val services = Services(store, TwilioTokenIssuer(twilio), jwt, otp, hook, missingTwilioEnv = missing)
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080) { module(services) }.start(wait = true)
}

private fun ApplicationCall.accountId(): String? = principal<JWTPrincipal>()?.subject
private fun ApplicationCall.ip(): String = request.local.remoteHost
private val rnd = SecureRandom()
private fun newTicketId() = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(rnd::nextBytes))
private fun mask(e164: String) = "•••" + e164.takeLast(4)

private suspend fun ApplicationCall.respondAuth(r: AuthResult, okStatus: HttpStatusCode = HttpStatusCode.OK) = when (r) {
    is AuthResult.Ok -> respond(okStatus, SessionResponse(r.accountId, r.accessToken, r.refreshToken, r.expiresIn))
    is AuthResult.Fail -> respond(HttpStatusCode.fromValue(r.status), ErrorResponse(r.error))
}

private suspend fun ApplicationCall.respondVerify(r: VerifyResult) = when (r) {
    VerifyResult.Sent -> respond(mapOf("status" to "pending"))
    is VerifyResult.Verified -> respond(mapOf("status" to "approved", "verifiedNumberMasked" to mask(r.e164)))
    is VerifyResult.Fail -> respond(HttpStatusCode.fromValue(r.status), ErrorResponse(r.error))
}

fun Application.module(services: Services) {
    install(ContentNegotiation) { json() }
    install(Authentication) {
        jwt("access") {
            verifier(services.auth.verifier)
            validate { cred -> cred.payload.subject?.let { JWTPrincipal(cred.payload) } }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized")) }
        }
    }
    routing {
        get("/health") { call.respond(mapOf("ok" to "true")) }

        // ------------------------------------------------------------ auth
        post("/v1/auth/signup") {
            val r = call.receive<SignupRequest>()
            call.respondAuth(services.auth.signup(r.email, r.password, r.ageAttested18, r.disclaimerAccepted, call.ip()), HttpStatusCode.Created)
        }
        post("/v1/auth/login") {
            val r = call.receive<LoginRequest>()
            call.respondAuth(services.auth.login(r.email, r.password, call.ip()))
        }
        post("/v1/auth/refresh") { call.respondAuth(services.auth.refresh(call.receive<RefreshRequest>().refreshToken)) }

        get("/v1/pool") {
            call.respond(services.store.pool().map { mapOf("label" to (it.cnamLabel ?: "VictorSpoof"), "cnam" to it.cnamSupported.toString()) })
        }

        // ------------------------------------------------------------ Twilio webhooks (signature-validated, no JWT)
        post("/v1/twilio/voice") {
            val params = call.receiveParameters().toMap().mapValues { it.value.firstOrNull() ?: "" }
            val r = services.webhook.handle(call.request.uri, params, call.request.headers["X-Twilio-Signature"])
            call.respondText(r.twiml, ContentType.parse("application/xml"), HttpStatusCode.fromValue(r.status))
        }
        post("/v1/twilio/whisper") {
            val params = call.receiveParameters().toMap().mapValues { it.value.firstOrNull() ?: "" }
            val r = services.webhook.whisper(call.request.uri, params, call.request.headers["X-Twilio-Signature"])
            call.respondText(r.twiml, ContentType.parse("application/xml"), HttpStatusCode.fromValue(r.status))
        }

        authenticate("access") {
            post("/v1/auth/logout") { services.auth.logout(call.accountId()!!); call.respond(mapOf("ok" to "true")) }

            get("/v1/me") {
                val a = services.store.account(call.accountId()!!) ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("no_account"))
                call.respond(MeResponse(a.id, a.aupAccepted, a.verifiedNumber?.let(::mask),
                    listOf("POOL") + listOfNotNull("OWN_VERIFIED".takeIf { a.verifiedNumber != null })))
            }

            post("/v1/aup/accept") {
                val id = call.accountId()!!
                services.store.account(id) ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("no_account"))
                val req = call.receive<AupRequest>()
                if (req.version != AUP_VERSION) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("stale_aup_version"))
                services.store.logAup(id, req.version, call.ip(), call.request.userAgent())
                call.respond(mapOf("accepted" to AUP_VERSION))
            }

            get("/v1/usage") { call.respond(services.rateLimiter.usage(call.accountId()!!)) }

            post("/v1/verify/start") { call.respondVerify(services.verification.start(call.accountId()!!, call.receive<VerifyStartRequest>().phone)) }
            post("/v1/verify/check") {
                val r = call.receive<VerifyCheckRequest>()
                call.respondVerify(services.verification.check(call.accountId()!!, r.phone, r.code))
            }

            post("/v1/calls/authorize") {
                val id = call.accountId()!!
                val acct = services.store.account(id) ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("no_account"))
                val req = call.receive<AuthorizeCallRequest>()
                val result = services.authorizer.authorize(acct, req.target, req.mode, req.recording)
                if (!result.allowed) return@post call.respond(HttpStatusCode.Forbidden, result)
                val ticket = CallTicket(newTicketId(), id, result.target!!, result.callerId!!.e164, result.callerId.displayName,
                    result.maxCallSeconds ?: 120, req.recording, services.clock.instant().plusSeconds(120))
                services.store.saveTicket(ticket)
                // The caller-ID number itself is not needed by the app; it only sees the masked form.
                call.respond(result.copy(ticket = ticket.id, callerId = result.callerId.copy(e164 = mask(result.callerId.e164))))
            }

            post("/v1/twilio/token") {
                val id = call.accountId()!!
                val acct = services.store.account(id)
                if (acct == null || !acct.aupAccepted || !acct.ageAttested) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("onboarding_incomplete"))
                if (!services.tokens.configured) return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("twilio_not_configured", services.missingTwilioEnv))
                if (!services.tokenLimiter.tryAcquire(id)) return@post call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("token_rate_limited"))
                call.respond(TokenResponse(services.tokens.issue(id)!!, services.tokens.ttlSeconds))
            }
        }
    }
}

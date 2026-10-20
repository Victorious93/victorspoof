package com.victorious.spoof.backend

import com.victorious.spoof.backend.db.InMemoryStore
import com.victorious.spoof.backend.db.PostgresStore
import com.victorious.spoof.backend.db.Store
import com.victorious.spoof.backend.guardrails.*
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

const val AUP_VERSION = "2026-10-v1"

@Serializable data class CreateAccountRequest(val ageAttested18: Boolean, val disclaimerAccepted: Boolean)
@Serializable data class AupRequest(val version: String)
/** Note: there is deliberately NO caller-ID number or name field. The backend chooses it. */
@Serializable data class AuthorizeCallRequest(val target: String, val mode: CallerIdMode, val recording: Boolean = false)
@Serializable data class TokenResponse(val token: String, val ttlSeconds: Int)
@Serializable data class ErrorResponse(val error: String)

class Services(val store: Store, val tokens: TwilioTokenIssuer) {
    val rateLimiter = RateLimiter(store.ledger)
    val authorizer = CallAuthorizer(Blocklist(), rateLimiter, CallerIdPolicy { store.pool() })
}

fun main() {
    val store = PostgresStore.fromEnv() ?: InMemoryStore().also { println("WARN: DATABASE_URL not set, using in-memory store") }
    val services = Services(store, TwilioTokenIssuer(TwilioConfig.fromEnv()))
    embeddedServer(Netty, port = System.getenv("PORT")?.toIntOrNull() ?: 8080) { module(services) }.start(wait = true)
}

/** Stub auth: account id in X-Account-Id. Replace with signed device tokens before production. */
private fun ApplicationCall.accountId(): String? = request.headers["X-Account-Id"]

fun Application.module(services: Services) {
    install(ContentNegotiation) { json() }
    routing {
        get("/health") { call.respond(mapOf("ok" to "true")) }

        post("/v1/accounts") {
            val req = call.receive<CreateAccountRequest>()
            if (!req.ageAttested18) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("must_be_18"))
            if (!req.disclaimerAccepted) return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("disclaimer_required"))
            call.respond(HttpStatusCode.Created, mapOf("accountId" to services.store.createAccount(true)))
        }

        post("/v1/aup/accept") {
            val id = call.accountId() ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("no_account"))
            services.store.account(id) ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("no_account"))
            val req = call.receive<AupRequest>()
            if (req.version != AUP_VERSION) return@post call.respond(HttpStatusCode.Conflict, ErrorResponse("stale_aup_version"))
            services.store.logAup(id, req.version, call.request.local.remoteHost, call.request.userAgent())
            call.respond(mapOf("accepted" to AUP_VERSION))
        }

        get("/v1/pool") {
            call.respond(services.store.pool().map { mapOf("label" to (it.cnamLabel ?: "VictorSpoof"), "cnam" to it.cnamSupported.toString()) })
        }

        get("/v1/usage") {
            val id = call.accountId() ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("no_account"))
            call.respond(services.rateLimiter.usage(id))
        }

        post("/v1/calls/authorize") {
            val id = call.accountId() ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("no_account"))
            val acct = services.store.account(id) ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("no_account"))
            val req = call.receive<AuthorizeCallRequest>()
            val result = services.authorizer.authorize(acct, req.target, req.mode, req.recording)
            call.respond(if (result.allowed) HttpStatusCode.OK else HttpStatusCode.Forbidden, result)
        }

        post("/v1/twilio/token") {
            val id = call.accountId() ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("no_account"))
            val acct = services.store.account(id)
            if (acct == null || !acct.aupAccepted || !acct.ageAttested) return@post call.respond(HttpStatusCode.Forbidden, ErrorResponse("onboarding_incomplete"))
            val token = services.tokens.issue(id) ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("twilio_not_configured"))
            call.respond(TokenResponse(token, 600))
        }
    }
}

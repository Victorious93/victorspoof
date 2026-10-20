package com.victorious.spoof.backend

import com.victorious.spoof.backend.db.InMemoryStore
import com.victorious.spoof.backend.guardrails.PoolNumber
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ApiTest {
    private fun services() = Services(InMemoryStore(listOf(PoolNumber("+15005550006", "Pizza Wizard", true))), TwilioTokenIssuer(null))

    @Test fun under18Rejected() = testApplication {
        application { module(services()) }
        val r = client.post("/v1/accounts") { contentType(ContentType.Application.Json); setBody("""{"ageAttested18":false,"disclaimerAccepted":true}""") }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test fun fullFlowAndTokenWithoutCredsIs503() = testApplication {
        application { module(services()) }
        val created = client.post("/v1/accounts") { contentType(ContentType.Application.Json); setBody("""{"ageAttested18":true,"disclaimerAccepted":true}""") }
        assertEquals(HttpStatusCode.Created, created.status)
        val id = Regex("\"accountId\"\\s*:\\s*\"([^\"]+)\"").find(created.bodyAsText())!!.groupValues[1]
        val noAup = client.post("/v1/calls/authorize") { header("X-Account-Id", id); contentType(ContentType.Application.Json); setBody("""{"target":"+14155550100","mode":"POOL"}""") }
        assertEquals(HttpStatusCode.Forbidden, noAup.status)
        assertEquals(HttpStatusCode.OK, client.post("/v1/aup/accept") { header("X-Account-Id", id); contentType(ContentType.Application.Json); setBody("""{"version":"$AUP_VERSION"}""") }.status)
        val ok = client.post("/v1/calls/authorize") { header("X-Account-Id", id); contentType(ContentType.Application.Json); setBody("""{"target":"+14155550100","mode":"POOL"}""") }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val e = client.post("/v1/calls/authorize") { header("X-Account-Id", id); contentType(ContentType.Application.Json); setBody("""{"target":"911","mode":"POOL"}""") }
        assertEquals(HttpStatusCode.Forbidden, e.status)
        val tok = client.post("/v1/twilio/token") { header("X-Account-Id", id) }
        assertEquals(HttpStatusCode.ServiceUnavailable, tok.status)
    }
}

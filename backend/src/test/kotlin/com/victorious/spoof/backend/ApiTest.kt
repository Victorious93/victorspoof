package com.victorious.spoof.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ApiTest {
    @Test fun under18Rejected() = testApplication {
        application { module(testServices()) }
        val r = client.json("/v1/auth/signup", """{"email":"a@b.co","password":"correct horse battery","ageAttested18":false,"disclaimerAccepted":true}""")
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test fun oldAccountIdHeaderNoLongerWorks() = testApplication {
        application { module(testServices()) }
        val r = client.post("/v1/calls/authorize") { header("X-Account-Id", "anything"); contentType(ContentType.Application.Json); setBody("""{"target":"+14155550100","mode":"POOL"}""") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test fun fullFlowAndTokenWithoutCredsIs503() = testApplication {
        application { module(testServices()) }
        val r = client.json("/v1/auth/signup", """{"email":"don@example.com","password":"correct horse battery","ageAttested18":true,"disclaimerAccepted":true}""")
        val token = field(r.bodyAsText(), "accessToken")!!
        val noAup = client.json("/v1/calls/authorize", """{"target":"+14155550100","mode":"POOL"}""", token)
        assertEquals(HttpStatusCode.Forbidden, noAup.status)
        assertEquals(HttpStatusCode.OK, client.json("/v1/aup/accept", """{"version":"$AUP_VERSION"}""", token).status)
        val ok = client.json("/v1/calls/authorize", """{"target":"+14155550100","mode":"POOL"}""", token)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        assertNotNull(field(ok.bodyAsText(), "ticket"))
        assertFalse(ok.bodyAsText().contains("5005550006"), "real pool number must not be sent to the app")
        assertEquals(HttpStatusCode.Forbidden, client.json("/v1/calls/authorize", """{"target":"911","mode":"POOL"}""", token).status)
        val tok = client.post("/v1/twilio/token") { bearerAuth(token) }
        assertEquals(HttpStatusCode.ServiceUnavailable, tok.status)
        assertTrue(tok.bodyAsText().contains("TWILIO_API_SECRET"), "503 names the missing env vars")
    }

    @Test fun clientCannotSmuggleCallerId() = testApplication {
        application { module(testServices()) }
        val token = client.onboard()
        val r = client.json("/v1/calls/authorize", """{"target":"+14155550100","mode":"POOL","callerId":"+12025551234","displayName":"FBI"}""", token)
        // Unknown fields are rejected by the strict JSON decoder, so no client-chosen caller ID can get in.
        assertNotEquals(HttpStatusCode.OK, r.status)
    }

    @Test fun tokenRateLimited() = testApplication {
        application { module(testServices(twilio = true)) }
        val token = client.onboard()
        repeat(10) { assertEquals(HttpStatusCode.OK, client.post("/v1/twilio/token") { bearerAuth(token) }.status, "token #$it") }
        assertEquals(HttpStatusCode.TooManyRequests, client.post("/v1/twilio/token") { bearerAuth(token) }.status)
    }

    @Test fun tokenNeedsOnboarding() = testApplication {
        application { module(testServices(twilio = true)) }
        val r = client.json("/v1/auth/signup", """{"email":"x@example.com","password":"correct horse battery","ageAttested18":true,"disclaimerAccepted":true}""")
        val token = field(r.bodyAsText(), "accessToken")!!
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/twilio/token") { bearerAuth(token) }.status)
    }
}

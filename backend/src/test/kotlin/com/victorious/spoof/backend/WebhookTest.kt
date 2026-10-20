package com.victorious.spoof.backend

import com.victorious.spoof.backend.db.CallTicket
import com.victorious.spoof.backend.db.InMemoryStore
import com.victorious.spoof.backend.guardrails.PoolNumber
import com.victorious.spoof.backend.twilio.SignatureValidator
import com.victorious.spoof.backend.twilio.VoiceWebhook
import com.victorious.spoof.backend.twilio.WebhookConfig
import io.ktor.client.request.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlin.test.*

class WebhookTest {
    private val store = InMemoryStore(listOf(PoolNumber(POOL_E164, "Pizza Wizard", true)))
    private val cfg = WebhookConfig("tok", "https://api.example.test", "AC1")
    private val hook = VoiceWebhook(store, cfg, validator = SignatureValidator { _, _, s -> s == "good" })
    private val acct = store.createAccount("w@example.com", "x", true)!!.also { store.logAup(it, AUP_VERSION, null, null) }
    private fun ticket(id: String = "t1", target: String = "+14155550100", callerId: String = POOL_E164, rec: Boolean = false,
                       exp: Instant = Instant.now().plusSeconds(60)) =
        CallTicket(id, acct, target, callerId, "Pizza Wizard", 120, rec, exp).also(store::saveTicket)
    private fun params(t: String = "t1", from: String = "client:$acct") = mapOf("AccountSid" to "AC1", "From" to from, "ticket" to t,
        "callerId" to "+12025551234", "To" to "+19115550000")

    @Test fun unconfiguredRejectsGracefully() {
        val r = VoiceWebhook(store, null).handle("/v1/twilio/voice", params(), "good")
        assertTrue(r.twiml.contains("<Hangup/>")); assertTrue(r.twiml.contains("not configured"))
    }
    @Test fun badSignatureRejected() { ticket(); assertEquals(403, hook.handle("/v1/twilio/voice", params(), "bad").status) }
    @Test fun wrongAccountSidRejected() { ticket(); assertEquals(403, hook.handle("/v1/twilio/voice", params() + ("AccountSid" to "ACevil"), "good").status) }

    @Test fun setsServerCallerIdAndTimeLimitIgnoringClientParams() {
        ticket()
        val x = hook.handle("/v1/twilio/voice", params(), "good").twiml
        assertTrue(x.contains("callerId=\"$POOL_E164\""), x)
        assertTrue(x.contains("timeLimit=\"120\""), x)
        assertTrue(x.contains("<Number>+14155550100</Number>"), x)
        assertFalse(x.contains("2025551234")); assertFalse(x.contains("9115550000"))
        assertFalse(x.contains("record="))
    }
    @Test fun ticketIsSingleUse() {
        ticket()
        assertTrue(hook.handle("/v1/twilio/voice", params(), "good").twiml.contains("<Dial"))
        assertTrue(hook.handle("/v1/twilio/voice", params(), "good").twiml.contains("<Hangup/>"))
    }
    @Test fun ticketBoundToIdentity() {
        ticket(); assertTrue(hook.handle("/v1/twilio/voice", params(from = "client:someone-else"), "good").twiml.contains("<Hangup/>"))
    }
    @Test fun expiredTicketRejected() {
        ticket(exp = Instant.now().minusSeconds(1)); assertTrue(hook.handle("/v1/twilio/voice", params(), "good").twiml.contains("expired"))
    }
    @Test fun blockedTargetRejectedEvenWithTicket() {
        ticket(target = "+18009359935"); assertFalse(hook.handle("/v1/twilio/voice", params(), "good").twiml.contains("<Dial"))
        ticket("t2", target = "911"); assertFalse(hook.handle("/v1/twilio/voice", params("t2"), "good").twiml.contains("<Dial"))
    }
    @Test fun callerIdMustBePoolOrVerified() {
        ticket(callerId = "+12025551234"); assertTrue(hook.handle("/v1/twilio/voice", params(), "good").twiml.contains("Caller ID not allowed"))
    }
    @Test fun recordingAnnouncedToBothParties() {
        ticket(rec = true)
        val x = hook.handle("/v1/twilio/voice", params(), "good").twiml
        assertTrue(x.contains("<Say>This call will be recorded.</Say>"), x)
        assertTrue(x.contains("record=\"record-from-answer\""))
        assertTrue(x.contains("/v1/twilio/whisper"), "callee hears announcement via <Number url>")
        assertTrue(hook.whisper("/v1/twilio/whisper?rec=1", emptyMap(), "good").twiml.contains("being recorded"))
        assertEquals(403, hook.whisper("/v1/twilio/whisper?rec=1", emptyMap(), "bad").status)
    }
    @Test fun suspendedAccountRejected() {
        val s2 = object : com.victorious.spoof.backend.db.Store by store {
            override fun account(id: String) = store.account(id)?.copy(suspended = true)
        }
        ticket()
        assertTrue(VoiceWebhook(s2, cfg, validator = { _, _, _ -> true }).handle("/v1/twilio/voice", params(), "good").twiml.contains("<Hangup/>"))
    }

    @Test fun endToEndAuthorizeThenWebhook() = testApplication {
        val s = testServices()
        application { module(s) }
        val t = client.onboard()
        val auth = client.json("/v1/calls/authorize", """{"target":"(415) 555-0100","mode":"POOL","recording":true}""", t).bodyAsText()
        val ticket = field(auth, "ticket")!!
        val me = client.get("/v1/me") { bearerAuth(t) }.bodyAsText()
        val id = field(me, "accountId")!!
        val r = client.post("/v1/twilio/voice") {
            header("X-Twilio-Signature", "good")
            setBody(FormDataContent(Parameters.build { append("From", "client:$id"); append("ticket", ticket); append("AccountSid", "AC1") }))
        }
        val x = r.bodyAsText()
        assertTrue(x.contains("callerId=\"$POOL_E164\""), x)
        assertTrue(x.contains("<Number url=\"https://api.example.test/v1/twilio/whisper?rec=1\" method=\"POST\">+14155550100</Number>"), x)
    }
}

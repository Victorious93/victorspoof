package com.victorious.spoof.backend

import com.victorious.spoof.backend.auth.JwtConfig
import com.victorious.spoof.backend.db.InMemoryStore
import com.victorious.spoof.backend.guardrails.PoolNumber
import com.victorious.spoof.backend.twilio.SignatureValidator
import com.victorious.spoof.backend.twilio.TwilioConfig
import com.victorious.spoof.backend.twilio.TwilioTokenIssuer
import com.victorious.spoof.backend.twilio.VoiceWebhook
import com.victorious.spoof.backend.twilio.WebhookConfig
import com.victorious.spoof.backend.verify.OtpProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*

const val POOL_E164 = "+15005550006"
val TEST_JWT = JwtConfig("unit-test-secret-unit-test-secret-0123456789")
val FAKE_TWILIO = TwilioConfig("AC" + "0".repeat(32), "SK" + "0".repeat(32), "fake-secret-for-tests-only-0123456789", "AP" + "0".repeat(32))

class FakeOtp(var approvedCode: String = "123456") : OtpProvider {
    val sent = mutableListOf<String>(); var checks = 0
    override fun send(e164: String) { sent += e164 }
    override fun check(e164: String, code: String): Boolean { checks++; return code == approvedCode }
}

fun testServices(twilio: Boolean = false, otp: OtpProvider? = FakeOtp(), signatureOk: Boolean = true): Services {
    val store = InMemoryStore(listOf(PoolNumber(POOL_E164, "Pizza Wizard", true)))
    val hookCfg = WebhookConfig("fake-auth-token", "https://api.example.test", null)
    return Services(store, TwilioTokenIssuer(if (twilio) FAKE_TWILIO else null), TEST_JWT, otp, hookCfg,
        VoiceWebhook(store, hookCfg, validator = SignatureValidator { _, _, sig -> signatureOk && sig == "good" }),
        missingTwilioEnv = if (twilio) emptyList() else listOf("TWILIO_ACCOUNT_SID", "TWILIO_API_KEY", "TWILIO_API_SECRET", "TWILIO_TWIML_APP_SID"))
}

fun field(body: String, name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

suspend fun HttpClient.json(path: String, body: String, bearer: String? = null): HttpResponse = post(path) {
    contentType(ContentType.Application.Json); setBody(body); bearer?.let { bearerAuth(it) }
}

/** Signs up, accepts the AUP and returns the access token. */
suspend fun HttpClient.onboard(email: String = "don@example.com"): String {
    val r = json("/v1/auth/signup", """{"email":"$email","password":"correct horse battery","ageAttested18":true,"disclaimerAccepted":true}""")
    check(r.status == HttpStatusCode.Created) { r.bodyAsText() }
    val token = field(r.bodyAsText(), "accessToken")!!
    check(json("/v1/aup/accept", """{"version":"$AUP_VERSION"}""", token).status == HttpStatusCode.OK)
    return token
}

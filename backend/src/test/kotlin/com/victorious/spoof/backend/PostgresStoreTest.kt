package com.victorious.spoof.backend

import com.victorious.spoof.backend.db.CallTicket
import com.victorious.spoof.backend.db.PostgresStore
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

/** Runs only when VS_TEST_DATABASE_URL points at a disposable Postgres (skipped otherwise). */
class PostgresStoreTest {
    private val url = System.getenv("VS_TEST_DATABASE_URL")
    private fun store() = PostgresStore.fromEnv { when (it) { "DATABASE_URL" -> url; "DATABASE_USER" -> System.getenv("VS_TEST_DATABASE_USER"); else -> null } }!!

    @Test fun roundTrip() {
        if (url == null) { println("SKIP: VS_TEST_DATABASE_URL not set"); return }
        val s = store(); store() // schema is idempotent
        val email = "pg${System.nanoTime()}@example.com"
        val id = s.createAccount(email, "hash", true)!!
        assertNull(s.createAccount(email.uppercase(), "hash", true), "email unique, case-insensitive")
        assertEquals(id, s.credentials(email)!!.accountId)
        assertFalse(s.account(id)!!.aupAccepted)
        s.logAup(id, AUP_VERSION, "1.2.3.4", "ua"); assertTrue(s.account(id)!!.aupAccepted)
        s.setVerifiedNumber(id, "+14155550123"); assertEquals("+14155550123", s.account(id)!!.verifiedNumber)
        s.saveRefresh("h$id", id, Instant.now().plusSeconds(60))
        assertEquals(id, s.consumeRefresh("h$id", Instant.now())); assertNull(s.consumeRefresh("h$id", Instant.now()))
        s.saveTicket(CallTicket("tk$id", id, "+14155550100", "+15005550006", null, 120, false, Instant.now().plusSeconds(60)))
        assertEquals("+14155550100", s.consumeTicket("tk$id")!!.target); assertNull(s.consumeTicket("tk$id"))
        s.ledger.record(id, "+14155550100", LocalDate.now()); assertEquals(1, s.ledger.countForDay(id, LocalDate.now()))
        assertNull(s.account("not-a-uuid"))
    }
}

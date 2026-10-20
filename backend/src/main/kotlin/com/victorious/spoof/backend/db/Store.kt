package com.victorious.spoof.backend.db

import com.victorious.spoof.backend.guardrails.AccountState
import com.victorious.spoof.backend.guardrails.CallLedger
import com.victorious.spoof.backend.guardrails.InMemoryCallLedger
import com.victorious.spoof.backend.guardrails.PoolNumber
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/** One-time ticket minted by /v1/calls/authorize and consumed exactly once by the TwiML voice webhook. */
data class CallTicket(
    val id: String, val accountId: String, val target: String, val callerId: String, val displayName: String?,
    val maxSeconds: Int, val recording: Boolean, val expiresAt: Instant,
)

data class Credentials(val accountId: String, val passwordHash: String)

interface Store {
    val ledger: CallLedger
    /** Returns null when the email is already registered. */
    fun createAccount(email: String, passwordHash: String, ageAttested18: Boolean): String?
    fun credentials(email: String): Credentials?
    fun account(id: String): AccountState?
    fun logAup(accountId: String, version: String, ip: String?, userAgent: String?)
    fun pool(): List<PoolNumber>
    fun setVerifiedNumber(accountId: String, e164: String)
    fun saveRefresh(tokenHash: String, accountId: String, expiresAt: Instant)
    /** Atomically revokes the refresh token and returns its account if it was valid (rotation). */
    fun consumeRefresh(tokenHash: String, now: Instant): String?
    fun revokeAllRefresh(accountId: String)
    fun saveTicket(t: CallTicket)
    /** Atomically marks the ticket used and returns it, or null if unknown / already used. */
    fun consumeTicket(id: String): CallTicket?
}

class InMemoryStore(private val poolNumbers: List<PoolNumber> = emptyList()) : Store {
    override val ledger = InMemoryCallLedger()
    private val accounts = mutableMapOf<String, AccountState>()
    private val emails = mutableMapOf<String, Credentials>()
    private val refresh = mutableMapOf<String, Pair<String, Instant>>()
    private val tickets = mutableMapOf<String, CallTicket>()
    val aupLog = mutableListOf<Pair<String, String>>()

    @Synchronized override fun createAccount(email: String, passwordHash: String, ageAttested18: Boolean): String? {
        val key = email.lowercase()
        if (key in emails) return null
        val id = UUID.randomUUID().toString()
        accounts[id] = AccountState(id, ageAttested18, aupAccepted = false, verifiedNumber = null)
        emails[key] = Credentials(id, passwordHash)
        return id
    }
    @Synchronized override fun credentials(email: String) = emails[email.lowercase()]
    @Synchronized override fun account(id: String) = accounts[id]
    @Synchronized override fun logAup(accountId: String, version: String, ip: String?, userAgent: String?) {
        aupLog += accountId to version
        accounts[accountId]?.let { accounts[accountId] = it.copy(aupAccepted = true) }
    }
    override fun pool() = poolNumbers
    @Synchronized override fun setVerifiedNumber(accountId: String, e164: String) {
        accounts[accountId]?.let { accounts[accountId] = it.copy(verifiedNumber = e164) }
    }
    @Synchronized override fun saveRefresh(tokenHash: String, accountId: String, expiresAt: Instant) { refresh[tokenHash] = accountId to expiresAt }
    @Synchronized override fun consumeRefresh(tokenHash: String, now: Instant): String? {
        val (acct, exp) = refresh.remove(tokenHash) ?: return null
        return acct.takeIf { exp.isAfter(now) }
    }
    @Synchronized override fun revokeAllRefresh(accountId: String) { refresh.entries.removeIf { it.value.first == accountId } }
    @Synchronized override fun saveTicket(t: CallTicket) { tickets[t.id] = t }
    @Synchronized override fun consumeTicket(id: String): CallTicket? = tickets.remove(id)
}

class PostgresStore(private val ds: DataSource) : Store {
    init {
        val sql = javaClass.getResource("/db/schema.sql")!!.readText()
        ds.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    override val ledger = object : CallLedger {
        override fun countForDay(accountId: String, day: LocalDate) = count(
            "SELECT count(*) FROM rate_limits WHERE account_id=?::uuid AND day_utc=?", accountId, day)
        override fun countForTarget(accountId: String, target: String, day: LocalDate) = count(
            "SELECT count(*) FROM rate_limits WHERE account_id=?::uuid AND target_e164=? AND day_utc=?", accountId, target, day)
        override fun record(accountId: String, target: String, day: LocalDate) {
            exec("INSERT INTO rate_limits(account_id,target_e164,day_utc) VALUES (?::uuid,?,?)", accountId, target, day)
        }
    }

    override fun createAccount(email: String, passwordHash: String, ageAttested18: Boolean): String? {
        val id = UUID.randomUUID().toString()
        return try {
            exec("INSERT INTO accounts(id, age_attested_18, email, password_hash) VALUES (?::uuid, ?, ?, ?)", id, ageAttested18, email.lowercase(), passwordHash)
            id
        } catch (e: java.sql.SQLException) {
            if (e.sqlState == "23505") null else throw e
        }
    }

    override fun credentials(email: String): Credentials? = ds.connection.use { c ->
        c.prepareStatement("SELECT id::text, password_hash FROM accounts WHERE lower(email)=lower(?) AND password_hash IS NOT NULL").use { st ->
            st.setString(1, email)
            st.executeQuery().use { rs -> if (rs.next()) Credentials(rs.getString(1), rs.getString(2)) else null }
        }
    }

    override fun account(id: String): AccountState? {
        if (runCatching { UUID.fromString(id) }.isFailure) return null
        return ds.connection.use { c ->
            c.prepareStatement("""SELECT a.age_attested_18, a.verified_number, a.status,
                EXISTS(SELECT 1 FROM aup_log l WHERE l.account_id=a.id) FROM accounts a WHERE a.id=?::uuid""").use { st ->
                st.setString(1, id)
                st.executeQuery().use { rs ->
                    if (!rs.next()) null else AccountState(id, rs.getBoolean(1), rs.getBoolean(4), rs.getString(2), rs.getString(3) == "suspended")
                }
            }
        }
    }

    override fun logAup(accountId: String, version: String, ip: String?, userAgent: String?) =
        exec("INSERT INTO aup_log(account_id,aup_version,ip,user_agent) VALUES (?::uuid,?,?,?)", accountId, version, ip, userAgent)

    override fun pool(): List<PoolNumber> = ds.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT e164,cnam_label,cnam_supported FROM number_pool WHERE active").use { rs ->
                buildList { while (rs.next()) add(PoolNumber(rs.getString(1), rs.getString(2), rs.getBoolean(3))) }
            }
        }
    }

    override fun setVerifiedNumber(accountId: String, e164: String) =
        exec("UPDATE accounts SET verified_number=?, verified_at=now() WHERE id=?::uuid", e164, accountId)

    override fun saveRefresh(tokenHash: String, accountId: String, expiresAt: Instant) =
        exec("INSERT INTO refresh_tokens(token_hash, account_id, expires_at) VALUES (?, ?::uuid, ?)", tokenHash, accountId, Timestamp.from(expiresAt))

    override fun consumeRefresh(tokenHash: String, now: Instant): String? = ds.connection.use { c ->
        c.prepareStatement("UPDATE refresh_tokens SET revoked=true WHERE token_hash=? AND NOT revoked AND expires_at > ? RETURNING account_id::text").use { st ->
            st.setString(1, tokenHash); st.setTimestamp(2, Timestamp.from(now))
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    override fun revokeAllRefresh(accountId: String) = exec("UPDATE refresh_tokens SET revoked=true WHERE account_id=?::uuid", accountId)

    override fun saveTicket(t: CallTicket) = exec(
        "INSERT INTO call_tickets(id,account_id,target_e164,caller_id,display_name,max_seconds,recording,expires_at) VALUES (?,?::uuid,?,?,?,?,?,?)",
        t.id, t.accountId, t.target, t.callerId, t.displayName, t.maxSeconds, t.recording, Timestamp.from(t.expiresAt))

    override fun consumeTicket(id: String): CallTicket? = ds.connection.use { c ->
        c.prepareStatement("""UPDATE call_tickets SET used=true WHERE id=? AND NOT used
            RETURNING account_id::text,target_e164,caller_id,display_name,max_seconds,recording,expires_at""").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else CallTicket(id, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getInt(5), rs.getBoolean(6), rs.getTimestamp(7).toInstant())
            }
        }
    }

    private fun bind(st: java.sql.PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
    private fun exec(sql: String, vararg args: Any?) { ds.connection.use { c -> c.prepareStatement(sql).use { bind(it, args); it.executeUpdate() } } }
    private fun count(sql: String, vararg args: Any?): Int = ds.connection.use { c ->
        c.prepareStatement(sql).use { bind(it, args); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
    }

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): PostgresStore? {
            val url = env("DATABASE_URL") ?: return null
            val cfg = HikariConfig().apply {
                jdbcUrl = url; username = env("DATABASE_USER"); password = env("DATABASE_PASSWORD"); maximumPoolSize = 5
            }
            return PostgresStore(HikariDataSource(cfg))
        }
    }
}

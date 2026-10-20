package com.victorious.spoof.backend.db

import com.victorious.spoof.backend.guardrails.AccountState
import com.victorious.spoof.backend.guardrails.CallLedger
import com.victorious.spoof.backend.guardrails.InMemoryCallLedger
import com.victorious.spoof.backend.guardrails.PoolNumber
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

interface Store {
    val ledger: CallLedger
    fun createAccount(ageAttested18: Boolean): String
    fun account(id: String): AccountState?
    fun logAup(accountId: String, version: String, ip: String?, userAgent: String?)
    fun pool(): List<PoolNumber>
}

class InMemoryStore(private val poolNumbers: List<PoolNumber> = emptyList()) : Store {
    override val ledger = InMemoryCallLedger()
    private val accounts = mutableMapOf<String, AccountState>()
    val aupLog = mutableListOf<Pair<String, String>>()
    @Synchronized override fun createAccount(ageAttested18: Boolean) = UUID.randomUUID().toString().also {
        accounts[it] = AccountState(it, ageAttested18, aupAccepted = false, verifiedNumber = null)
    }
    @Synchronized override fun account(id: String) = accounts[id]
    @Synchronized override fun logAup(accountId: String, version: String, ip: String?, userAgent: String?) {
        aupLog += accountId to version
        accounts[accountId]?.let { accounts[accountId] = it.copy(aupAccepted = true) }
    }
    override fun pool() = poolNumbers
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

    override fun createAccount(ageAttested18: Boolean): String {
        val id = UUID.randomUUID().toString()
        exec("INSERT INTO accounts(id, age_attested_18) VALUES (?::uuid, ?)", id, ageAttested18)
        return id
    }

    override fun account(id: String): AccountState? = ds.connection.use { c ->
        c.prepareStatement("""SELECT a.age_attested_18, a.verified_number, a.status,
            EXISTS(SELECT 1 FROM aup_log l WHERE l.account_id=a.id) FROM accounts a WHERE a.id=?::uuid""").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else AccountState(id, rs.getBoolean(1), rs.getBoolean(4), rs.getString(2), rs.getString(3) == "suspended")
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

    private fun bind(st: java.sql.PreparedStatement, args: Array<out Any?>) = args.forEachIndexed { i, a ->
        when (a) { is LocalDate -> st.setObject(i + 1, a); else -> st.setObject(i + 1, a) }
    }
    private fun exec(sql: String, vararg args: Any?) { ds.connection.use { c -> c.prepareStatement(sql).use { bind(it, args); it.executeUpdate() } } }
    private fun count(sql: String, vararg args: Any?): Int = ds.connection.use { c ->
        c.prepareStatement(sql).use { bind(it, args); it.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
    }

    companion object {
        fun fromEnv(): PostgresStore? {
            val url = System.getenv("DATABASE_URL") ?: return null
            val cfg = HikariConfig().apply {
                jdbcUrl = url; username = System.getenv("DATABASE_USER"); password = System.getenv("DATABASE_PASSWORD"); maximumPoolSize = 5
            }
            return PostgresStore(HikariDataSource(cfg))
        }
    }
}

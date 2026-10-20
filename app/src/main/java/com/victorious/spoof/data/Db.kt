package com.victorious.spoof.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "consent")
data class ConsentRecord(
    @PrimaryKey val id: Int = 1,
    val disclaimerAcceptedAt: Long? = null,
    val ageConfirmedAt: Long? = null,
    val aupVersion: String? = null,
    val aupAcceptedAt: Long? = null,
    val aupLoggedServerSide: Boolean = false,
)

@Entity(tableName = "paired_nodes", primaryKeys = ["packageName", "certSha256"])
data class PairedNode(val packageName: String, val certSha256: String, val label: String, val pairedAt: Long)

/** VictorSpoof's OWN history of prank calls (never the system call log). Targets stored masked. */
@Entity(tableName = "prank_history")
data class PrankRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetMasked: String,
    val startedAt: Long,
    val durationSec: Int,
    val recorded: Boolean,
)

@Dao
interface ConsentDao {
    @Query("SELECT * FROM consent WHERE id = 1") fun observe(): Flow<ConsentRecord?>
    @Query("SELECT * FROM consent WHERE id = 1") suspend fun get(): ConsentRecord?
    @Upsert suspend fun upsert(r: ConsentRecord)
}

@Dao
interface PairingDao {
    @Query("SELECT * FROM paired_nodes") fun observe(): Flow<List<PairedNode>>
    @Query("SELECT * FROM paired_nodes") fun allBlocking(): List<PairedNode>
    @Upsert suspend fun upsert(n: PairedNode)
    @Delete suspend fun delete(n: PairedNode)
}

@Dao
interface PrankDao {
    @Query("SELECT * FROM prank_history ORDER BY startedAt DESC") fun observe(): Flow<List<PrankRecord>>
    @Query("SELECT COUNT(*) FROM prank_history WHERE startedAt >= :since") fun countSinceBlocking(since: Long): Int
    @Insert suspend fun insert(r: PrankRecord)
}

@Database(entities = [ConsentRecord::class, PairedNode::class, PrankRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun consent(): ConsentDao
    abstract fun pairing(): PairingDao
    abstract fun pranks(): PrankDao
}

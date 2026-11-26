package com.sbkubric.robby.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.Transaction
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

/** A scenario stored as its JSON document (`ScenarioFormat`); the name is a column only for sorting. */
@Entity(tableName = "scenarios")
data class ScenarioEntity(
    @PrimaryKey val id: String,
    val name: String,
    val json: String,
)

/**
 * One run in the activity log. A pending run is the same row: delivery updates it in place,
 * so the log shows «в очереди» and later the result without a second table to keep in step.
 */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scenarioId: String,
    val scenarioName: String,
    val timeMillis: Long,
    val status: String,
    val message: String?,
    /** `RunReason` name, if the engine itself failed or skipped the run (the column predates skipping). */
    val error: String? = null,
    /** The snapshot of the run's actions, a JSON list of `RunAction`; `null` for skipped runs and runs before version 3. */
    val actions: String? = null,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    val nextAttemptMillis: Long? = null,
    @ColumnInfo(defaultValue = "0") val late: Boolean = false,
    /** The service's own wait (`retry_after`) while pending. */
    val notBeforeMillis: Long? = null,
    /** Started by the editor's «Тест». */
    @ColumnInfo(defaultValue = "0") val test: Boolean = false,
)

@Dao
interface ScenarioDao {
    @Query("SELECT * FROM scenarios ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<ScenarioEntity>>

    @Query("SELECT * FROM scenarios ORDER BY name COLLATE NOCASE, id")
    suspend fun all(): List<ScenarioEntity>

    @Query("SELECT * FROM scenarios WHERE id = :id")
    suspend fun get(id: String): ScenarioEntity?

    @Upsert
    suspend fun upsert(scenario: ScenarioEntity)

    @Query("DELETE FROM scenarios WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM scenarios")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(scenarios: List<ScenarioEntity>)

    /** One transaction: the scenario list and the engine never see a mix of the old and the new. */
    @Transaction
    suspend fun replaceAll(scenarios: List<ScenarioEntity>) {
        deleteAll()
        insertAll(scenarios)
    }
}

@Dao
interface RunDao {
    @Insert
    suspend fun insert(run: RunEntity): Long

    @Update
    suspend fun update(run: RunEntity)

    @Query("SELECT * FROM runs WHERE status = :pending ORDER BY timeMillis, id")
    suspend fun pending(pending: String): List<RunEntity>

    @Query("SELECT * FROM runs ORDER BY timeMillis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE id IN (SELECT MAX(id) FROM runs GROUP BY scenarioId)")
    fun observeLastRuns(): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE scenarioId = :scenarioId AND status NOT IN (:excluded) AND test = 0 ORDER BY timeMillis DESC, id DESC LIMIT 1")
    suspend fun lastExecuted(scenarioId: String, excluded: List<String>): RunEntity?

    @Query(
        "DELETE FROM runs WHERE status != :pending AND (timeMillis < :olderThanMillis " +
            "OR id NOT IN (SELECT id FROM runs ORDER BY timeMillis DESC, id DESC LIMIT :keepNewest))",
    )
    suspend fun prune(olderThanMillis: Long, keepNewest: Int, pending: String)
}

// No exported schema yet: nothing has been released. Migrations keep debug installs working meanwhile.
@Database(entities = [ScenarioEntity::class, RunEntity::class], version = 4, exportSchema = false)
abstract class RobbyDatabase : RoomDatabase() {
    abstract fun scenarios(): ScenarioDao
    abstract fun runs(): RunDao

    companion object {
        fun create(context: Context): RobbyDatabase =
            Room.databaseBuilder(context, RobbyDatabase::class.java, "robby.db")
                .addMigrations(ADD_RUN_ERROR, ADD_RUN_DELIVERY, ADD_RUN_SERVICE_WAIT_AND_TEST)
                .build()

        private val ADD_RUN_ERROR = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE runs ADD COLUMN error TEXT")
            }
        }

        /** Slice 6: the pending run's snapshot and retry state. Existing runs keep their rows and read as finished. */
        private val ADD_RUN_DELIVERY = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE runs ADD COLUMN actions TEXT")
                connection.execSQL("ALTER TABLE runs ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE runs ADD COLUMN nextAttemptMillis INTEGER")
                connection.execSQL("ALTER TABLE runs ADD COLUMN late INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Slice 7: the service's own wait of a pending run, and test runs. */
        private val ADD_RUN_SERVICE_WAIT_AND_TEST = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE runs ADD COLUMN notBeforeMillis INTEGER")
                connection.execSQL("ALTER TABLE runs ADD COLUMN test INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

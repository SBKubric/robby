package com.sbkubric.robby.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A scenario stored as its JSON document (`ScenarioFormat`); the name is a column only for sorting. */
@Entity(tableName = "scenarios")
data class ScenarioEntity(
    @PrimaryKey val id: String,
    val name: String,
    val json: String,
)

/** One run in the activity log. */
@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scenarioId: String,
    val scenarioName: String,
    val timeMillis: Long,
    val status: String,
    val message: String?,
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
}

@Dao
interface RunDao {
    @Insert
    suspend fun insert(run: RunEntity)

    @Query("SELECT * FROM runs ORDER BY timeMillis DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RunEntity>>

    @Query("SELECT * FROM runs WHERE id IN (SELECT MAX(id) FROM runs GROUP BY scenarioId)")
    fun observeLastRuns(): Flow<List<RunEntity>>

    @Query(
        "DELETE FROM runs WHERE timeMillis < :olderThanMillis " +
            "OR id NOT IN (SELECT id FROM runs ORDER BY timeMillis DESC, id DESC LIMIT :keepNewest)",
    )
    suspend fun prune(olderThanMillis: Long, keepNewest: Int)
}

// No exported schema and no migrations yet: nothing has been released, the schema may still change.
@Database(entities = [ScenarioEntity::class, RunEntity::class], version = 1, exportSchema = false)
abstract class RobbyDatabase : RoomDatabase() {
    abstract fun scenarios(): ScenarioDao
    abstract fun runs(): RunDao

    companion object {
        fun create(context: Context): RobbyDatabase =
            Room.databaseBuilder(context, RobbyDatabase::class.java, "robby.db").build()
    }
}

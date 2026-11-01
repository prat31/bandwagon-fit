package fit.bandwagon.core.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One recorded sleep or move score per date. Local only: computed from data that never leaves the
 * phone, and excluded from backups with the rest of this database.
 */
@Entity(tableName = "daily_scores", primaryKeys = ["kind", "date"])
data class DailyScoreEntity(
    val kind: String,
    val date: String,
    val score: Int,
    /** Contributors as JSON; see ScoreRepository. */
    val contributors: String,
    val partial: Boolean,
    val computedAt: Long,
)

@Entity(tableName = "personal_goals")
data class PersonalGoalEntity(
    @PrimaryKey val id: String,
    val metric: String,
    val period: String,
    val target: Double,
    val dailyThreshold: Double?,
    val createdOn: String,
    val createdAt: Long,
)

/** The recorded result of one closed period of a goal. */
@Entity(tableName = "goal_outcomes", primaryKeys = ["goalId", "periodStart"])
data class GoalOutcomeEntity(
    val goalId: String,
    val periodStart: String,
    val periodEnd: String,
    val target: Double,
    val dailyThreshold: Double?,
    val achieved: Double?,
    val result: String,
    val finalized: Boolean,
)

@Dao
interface DailyScoreDao {
    @Query("SELECT * FROM daily_scores WHERE kind = :kind AND date >= :start AND date <= :end ORDER BY date ASC")
    suspend fun range(kind: String, start: String, end: String): List<DailyScoreEntity>

    @Upsert
    suspend fun upsert(rows: List<DailyScoreEntity>)

    @Query("DELETE FROM daily_scores")
    suspend fun clear()
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM personal_goals ORDER BY createdAt ASC")
    fun observeGoals(): Flow<List<PersonalGoalEntity>>

    @Query("SELECT * FROM personal_goals ORDER BY createdAt ASC")
    suspend fun goals(): List<PersonalGoalEntity>

    @Upsert
    suspend fun upsertGoal(goal: PersonalGoalEntity)

    @Upsert
    suspend fun upsertGoals(goals: List<PersonalGoalEntity>)

    @Query("SELECT * FROM goal_outcomes WHERE goalId = :goalId ORDER BY periodStart ASC")
    suspend fun outcomes(goalId: String): List<GoalOutcomeEntity>

    @Query("SELECT * FROM goal_outcomes ORDER BY periodStart ASC")
    fun observeOutcomes(): Flow<List<GoalOutcomeEntity>>

    @Upsert
    suspend fun upsertOutcomes(rows: List<GoalOutcomeEntity>)

    @Query("DELETE FROM personal_goals WHERE id = :id")
    suspend fun deleteGoalRow(id: String)

    @Query("DELETE FROM goal_outcomes WHERE goalId = :id")
    suspend fun deleteOutcomes(id: String)

    @Transaction
    suspend fun deleteGoal(id: String) {
        deleteOutcomes(id)
        deleteGoalRow(id)
    }

    @Query("DELETE FROM personal_goals")
    suspend fun clearGoals()

    @Query("DELETE FROM goal_outcomes")
    suspend fun clearOutcomes()
}

/** v3 adds recorded scores and personal goals; the upload cache in v2 is kept. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `daily_scores` (`kind` TEXT NOT NULL, `date` TEXT NOT NULL, `score` INTEGER NOT NULL, " +
                "`contributors` TEXT NOT NULL, `partial` INTEGER NOT NULL, `computedAt` INTEGER NOT NULL, PRIMARY KEY(`kind`, `date`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `personal_goals` (`id` TEXT NOT NULL, `metric` TEXT NOT NULL, `period` TEXT NOT NULL, " +
                "`target` REAL NOT NULL, `dailyThreshold` REAL, `createdOn` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `goal_outcomes` (`goalId` TEXT NOT NULL, `periodStart` TEXT NOT NULL, `periodEnd` TEXT NOT NULL, " +
                "`target` REAL NOT NULL, `dailyThreshold` REAL, `achieved` REAL, `result` TEXT NOT NULL, `finalized` INTEGER NOT NULL, " +
                "PRIMARY KEY(`goalId`, `periodStart`))",
        )
    }
}

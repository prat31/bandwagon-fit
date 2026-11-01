package fit.bandwagon.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DailySummaryEntity::class, DailyScoreEntity::class, PersonalGoalEntity::class, GoalOutcomeEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class BandwagonDatabase : RoomDatabase() {
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun dailyScoreDao(): DailyScoreDao
    abstract fun goalDao(): GoalDao
}

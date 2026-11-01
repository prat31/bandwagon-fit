package fit.bandwagon.core.health.store

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * Local-only cache of daily metric values for the Health tab and insights. It never syncs and is
 * excluded from Android backups (see the app's backup rules), so health data can't leave the phone
 * this way either.
 */
@Entity(tableName = "metric_day", primaryKeys = ["metric", "date"])
data class MetricDayEntity(
    val metric: String,
    val date: String,
    val value: Double?,
    val min: Double?,
    val max: Double?,
    val origins: String,
)

@Entity(tableName = "metric_refresh", primaryKeys = ["metric"])
data class MetricRefreshEntity(
    val metric: String,
    val refreshedAt: Long,
    val fromDate: String,
)

@Dao
interface HealthStoreDao {
    @Query("SELECT * FROM metric_day WHERE metric = :metric AND date >= :start AND date <= :end ORDER BY date ASC")
    suspend fun range(metric: String, start: String, end: String): List<MetricDayEntity>

    @Upsert
    suspend fun upsert(rows: List<MetricDayEntity>)

    @Query("SELECT * FROM metric_refresh WHERE metric = :metric")
    suspend fun refreshOf(metric: String): MetricRefreshEntity?

    @Upsert
    suspend fun markRefreshed(entity: MetricRefreshEntity)

    @Query("DELETE FROM metric_refresh")
    suspend fun invalidateAll()

    @Query("DELETE FROM metric_day")
    suspend fun clearDays()
}

@Database(entities = [MetricDayEntity::class, MetricRefreshEntity::class], version = 1, exportSchema = false)
abstract class HealthStoreDatabase : RoomDatabase() {
    abstract fun dao(): HealthStoreDao
}

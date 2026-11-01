package fit.bandwagon.core.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DailySummaryDao {
    @Query("SELECT * FROM daily_summaries WHERE date = :date LIMIT 1")
    suspend fun get(date: String): DailySummaryEntity?

    @Query("SELECT * FROM daily_summaries ORDER BY date DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DailySummaryEntity>>

    @Query("SELECT * FROM daily_summaries WHERE date >= :start AND date <= :end ORDER BY date ASC")
    suspend fun range(start: String, end: String): List<DailySummaryEntity>

    @Upsert
    suspend fun upsert(entity: DailySummaryEntity)

    @Query("DELETE FROM daily_summaries")
    suspend fun clear()
}

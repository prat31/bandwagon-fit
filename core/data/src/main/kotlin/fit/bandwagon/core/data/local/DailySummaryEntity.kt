package fit.bandwagon.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The last uploaded summary per date, so unchanged days are never re-uploaded. */
@Entity(tableName = "daily_summaries")
data class DailySummaryEntity(
    @PrimaryKey val date: String,
    val userId: String,
    /** The summary as JSON; see DailySummaryMapper. */
    val payload: String,
    val computedAt: Long,
)

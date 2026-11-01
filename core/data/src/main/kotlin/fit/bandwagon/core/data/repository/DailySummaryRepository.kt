package fit.bandwagon.core.data.repository

import fit.bandwagon.core.data.local.LocalDailySummaryStore
import fit.bandwagon.core.data.mapper.sameValuesAs
import fit.bandwagon.core.data.remote.RemoteDataSource
import fit.bandwagon.core.model.DailySummary
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DailySummaryRepository @Inject constructor(
    private val local: LocalDailySummaryStore,
    private val remote: RemoteDataSource,
) {
    fun observeRecent(limit: Int = 14): Flow<List<DailySummary>> = local.observeRecent(limit)

    suspend fun get(date: java.time.LocalDate): DailySummary? = local.get(date)

    suspend fun recent(days: Long): List<DailySummary> {
        val today = java.time.LocalDate.now()
        return local.range(today.minusDays(days - 1), today)
    }

    /**
     * Firestore receives the summary first so a failed upload is retried on the next sync instead
     * of being hidden by an already-updated Room row. Writes are idempotent, and unchanged values
     * are skipped entirely.
     */
    suspend fun uploadIfChanged(summary: DailySummary): Boolean {
        val previous = local.get(summary.date)
        if (summary.sameValuesAs(previous)) return false
        remote.upsertDailySummary(summary)
        remote.updateLastSync(summary.userId, summary.computedAt)
        local.upsert(summary)
        return true
    }

    suspend fun clearLocal() = local.clear()

    /** The heartbeat that tells "no data" apart from "inactive". */
    suspend fun touchLastSync(userId: String, at: java.time.Instant) {
        remote.updateLastSync(userId, at)
    }

    /** Marks a member as "data paused" in group standings instead of showing misleading zeros. */
    suspend fun setDataPaused(userId: String, paused: Boolean) {
        remote.setDataPaused(userId, paused)
    }
}

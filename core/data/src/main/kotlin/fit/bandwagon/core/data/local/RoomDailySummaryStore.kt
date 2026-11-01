package fit.bandwagon.core.data.local

import fit.bandwagon.core.data.mapper.DailySummaryMapper
import fit.bandwagon.core.model.DailySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject

interface LocalDailySummaryStore {
    suspend fun get(date: LocalDate): DailySummary?
    suspend fun range(start: LocalDate, end: LocalDate): List<DailySummary>
    fun observeRecent(limit: Int): Flow<List<DailySummary>>
    suspend fun upsert(summary: DailySummary)
    suspend fun clear()
}

class RoomDailySummaryStore @Inject constructor(
    private val dao: DailySummaryDao,
) : LocalDailySummaryStore {
    override suspend fun get(date: LocalDate): DailySummary? =
        dao.get(date.toString())?.let(DailySummaryMapper::fromEntity)

    override suspend fun range(start: LocalDate, end: LocalDate): List<DailySummary> =
        dao.range(start.toString(), end.toString()).mapNotNull(DailySummaryMapper::fromEntity)

    override fun observeRecent(limit: Int): Flow<List<DailySummary>> =
        dao.observeRecent(limit).map { rows -> rows.mapNotNull(DailySummaryMapper::fromEntity) }

    override suspend fun upsert(summary: DailySummary) {
        dao.upsert(DailySummaryMapper.toEntity(summary))
    }

    override suspend fun clear() = dao.clear()
}

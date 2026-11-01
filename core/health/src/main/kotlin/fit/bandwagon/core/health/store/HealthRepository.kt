package fit.bandwagon.core.health.store

import fit.bandwagon.core.health.HealthCapabilitiesRepository
import fit.bandwagon.core.health.HealthDataSource
import fit.bandwagon.core.model.Bucket
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.LocalDateRange
import fit.bandwagon.core.model.MetricKind
import fit.bandwagon.core.model.MetricPoint
import fit.bandwagon.core.model.Reading
import fit.bandwagon.core.model.SleepSession
import fit.bandwagon.core.model.WorkoutSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One day of a metric, as the overview tiles and insights use it. */
data class DayValue(val date: LocalDate, val value: Double?, val min: Double? = null, val max: Double? = null)

/**
 * Health tab data with caching. Daily values live in Room so tiles open instantly; detail charts
 * read Health Connect directly with a short in-memory cache, which keeps within Health Connect's
 * foreground read quota.
 */
@Singleton
class HealthRepository @Inject constructor(
    private val source: HealthDataSource,
    private val capabilities: HealthCapabilitiesRepository,
    private val dao: HealthStoreDao,
    private val clock: Clock,
) {
    private val memory = ConcurrentHashMap<String, Pair<Instant, Any>>()
    private val mutex = Mutex()

    private val zone: ZoneId get() = clock.zone

    /** Daily values for [metric] over the last [days] days, refreshed when older than 15 minutes. */
    suspend fun daily(metric: HealthMetric, days: Long, force: Boolean = false): List<DayValue> {
        val today = LocalDate.now(clock)
        val range = LocalDateRange.lastDays(today, days)
        if (!capabilities.capabilities.value.granted(metric.dataType)) return emptyList()
        mutex.withLock {
            val refreshed = dao.refreshOf(metric.name)
            val stale = force || refreshed == null ||
                Duration.ofMillis(clock.millis() - refreshed.refreshedAt) > CACHE_TTL ||
                LocalDate.parse(refreshed.fromDate).isAfter(range.start)
            if (stale) {
                val points = runCatching { source.series(metric, range, Bucket.DAY, zone) }.getOrDefault(emptyList())
                dao.upsert(
                    points.map {
                        MetricDayEntity(
                            metric = metric.name,
                            date = it.start.atZone(zone).toLocalDate().toString(),
                            value = it.value,
                            min = it.min,
                            max = it.max,
                            origins = it.origins.joinToString(","),
                        )
                    },
                )
                dao.markRefreshed(MetricRefreshEntity(metric.name, clock.millis(), range.start.toString()))
            }
        }
        val rows = dao.range(metric.name, range.start.toString(), range.endInclusive.toString()).associateBy { it.date }
        return generateSequence(range.start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { date ->
            val row = rows[date.toString()]
            DayValue(date, row?.value, row?.min, row?.max)
        }.toList()
    }

    private suspend fun <T : Any> cached(key: String, ttl: Duration = DETAIL_TTL, load: suspend () -> T): T {
        val hit = memory[key]
        if (hit != null && Duration.between(hit.first, clock.instant()) < ttl) {
            @Suppress("UNCHECKED_CAST")
            return hit.second as T
        }
        val value = load()
        memory[key] = clock.instant() to value
        return value
    }

    suspend fun series(metric: HealthMetric, range: LocalDateRange, bucket: Bucket): List<MetricPoint> =
        cached("series:$metric:$range:$bucket") { source.series(metric, range, bucket, zone) }

    suspend fun readings(metric: HealthMetric, range: LocalDateRange): List<Reading> =
        cached("readings:$metric:$range") { source.readings(metric, range, zone) }

    suspend fun sleep(range: LocalDateRange): List<SleepSession> =
        cached("sleep:$range") { source.sleepSessions(range, zone) }

    suspend fun workouts(range: LocalDateRange): List<WorkoutSummary> =
        cached("workouts:$range") { source.workouts(range, zone) }

    /** Granted types with no records in the last 30 days, to hide empty tiles. */
    suspend fun typesWithoutData(types: Collection<HealthDataType>): Set<HealthDataType> =
        cached("empty:${types.sorted()}", CACHE_TTL) {
            val since = clock.instant().minus(Duration.ofDays(30))
            types.filterNot { source.hasData(it, since) }.toSet()
        }

    suspend fun recentOrigins(): Map<String, Instant> =
        cached("origins", CACHE_TTL) { source.recentOrigins(clock.instant().minus(Duration.ofDays(14))) }

    /** Health Connect data changed (sync saw changes, or permissions changed): drop caches. */
    suspend fun invalidate() {
        memory.clear()
        dao.invalidateAll()
    }

    /** Sign-out and account deletion wipe everything local. */
    suspend fun clear() {
        memory.clear()
        dao.invalidateAll()
        dao.clearDays()
    }

    /** Readings are latest-wins per day, so the daily cache uses the series of readings. */
    fun isReading(metric: HealthMetric) = metric.kind == MetricKind.READING

    private companion object {
        val CACHE_TTL: Duration = Duration.ofMinutes(15)
        val DETAIL_TTL: Duration = Duration.ofMinutes(2)
    }
}

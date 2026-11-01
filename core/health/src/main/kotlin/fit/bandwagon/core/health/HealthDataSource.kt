package fit.bandwagon.core.health

import fit.bandwagon.core.model.Bucket
import fit.bandwagon.core.model.ChangeResult
import fit.bandwagon.core.model.DailySummary
import fit.bandwagon.core.model.HealthCapabilities
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.LocalDateRange
import fit.bandwagon.core.model.MetricPoint
import fit.bandwagon.core.model.Reading
import fit.bandwagon.core.model.SleepSession
import fit.bandwagon.core.model.SourceAvailability
import fit.bandwagon.core.model.WorkoutSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The platform boundary for health data. Only this interface knows about Health Connect, so a
 * future HealthKit source can be added without touching features or sync code. It is generic on
 * purpose: a new metric is a new [HealthMetric] entry, not a new method.
 *
 * Every read checks capabilities first and returns empty/null for types the user hasn't shared.
 */
interface HealthDataSource {
    suspend fun availability(): SourceAvailability

    suspend fun capabilities(): HealthCapabilities

    /**
     * The challenge metrics for one local date, ready to upload. Manual entries are left out and
     * `shared` lists the metric ids the user lets the app read.
     */
    suspend fun dailySummary(userId: String, date: LocalDate, zone: ZoneId, now: Instant): DailySummary

    /** Bucketed values for cumulative and sample metrics (steps, heart rate, …). */
    suspend fun series(metric: HealthMetric, range: LocalDateRange, bucket: Bucket, zone: ZoneId): List<MetricPoint>

    /** Point readings (weight, blood pressure, glucose, temperature, VO2 max, body composition). */
    suspend fun readings(metric: HealthMetric, range: LocalDateRange, zone: ZoneId): List<Reading>

    /** Sleep sessions whose wake date falls in [range], with stages. */
    suspend fun sleepSessions(range: LocalDateRange, zone: ZoneId): List<SleepSession>

    /** Workouts in [range] with distance, calories and heart rate for each. */
    suspend fun workouts(range: LocalDateRange, zone: ZoneId): List<WorkoutSummary>

    /** Whether any record of [type] exists since [since]: tells "no data yet" apart from "zero". */
    suspend fun hasData(type: HealthDataType, since: Instant): Boolean

    /** App labels of the apps that wrote data recently, for You → Health data. */
    suspend fun recentOrigins(since: Instant): Map<String, Instant>

    /**
     * Changes since [token] for the record types behind [readable]. Health Connect rejects a
     * token request that includes a type the app can't read, so callers pass what is granted and
     * must start a fresh token whenever that set changes.
     */
    suspend fun changesSince(token: String?, readable: Set<HealthDataType>): ChangeResult
}

package fit.bandwagon.core.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ActivityIntensityRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import fit.bandwagon.core.model.TimedValue
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.TemperatureDelta
import androidx.health.connect.client.units.Velocity
import androidx.health.connect.client.units.Volume
import dagger.hilt.android.qualifiers.ApplicationContext
import fit.bandwagon.core.model.Access
import fit.bandwagon.core.model.Bucket
import fit.bandwagon.core.model.ChangeResult
import fit.bandwagon.core.model.DailySummary
import fit.bandwagon.core.model.HealthCapabilities
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.HealthMath
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.LocalDateRange
import fit.bandwagon.core.model.MetricPoint
import fit.bandwagon.core.model.Reading
import fit.bandwagon.core.model.RecordingMethod
import fit.bandwagon.core.model.SleepSelection
import fit.bandwagon.core.model.SleepSession
import fit.bandwagon.core.model.SleepStage
import fit.bandwagon.core.model.SleepStageType
import fit.bandwagon.core.model.SourceAvailability
import fit.bandwagon.core.model.WorkoutSummary
import fit.bandwagon.core.model.WorkoutTypeTotal
import fit.bandwagon.core.model.rules.DayRanges
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

private const val TAG = "HealthConnectSource"

@Singleton
class HealthConnectSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionMemory: PermissionMemory,
) : HealthDataSource {
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    override suspend fun availability(): SourceAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> SourceAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> SourceAvailability.UPDATE_REQUIRED
            else -> SourceAvailability.UNSUPPORTED
        }

    private fun featureAvailable(feature: Int): Boolean = runCatching {
        client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    }.getOrDefault(false)

    override suspend fun capabilities(): HealthCapabilities {
        if (availability() != SourceAvailability.AVAILABLE) return HealthCapabilities.NONE
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        val access = HealthPermissions.ENTRIES.mapValues { (_, entry) ->
            when {
                entry.feature != null && !featureAvailable(entry.feature) -> Access.UNSUPPORTED
                entry.permission in granted -> Access.GRANTED
                else -> Access.NOT_GRANTED
            }
        }
        return HealthCapabilities(
            access = access,
            background = HealthPermissions.BACKGROUND in granted &&
                featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND),
            canRequestAgain = permissionMemory.canRequestAgain(),
        )
    }

    /** Reads that hit a revoked permission return empty instead of crashing. */
    private suspend fun <T> guarded(default: T, block: suspend () -> T): T = try {
        block()
    } catch (error: SecurityException) {
        Log.w(TAG, "Permission revoked during read", error)
        default
    } catch (error: Exception) {
        Log.w(TAG, "Health Connect read failed", error)
        default
    }

    private fun isManual(record: Record) = record.metadata.recordingMethod == Metadata.RECORDING_METHOD_MANUAL_ENTRY

    // --- Daily summary for challenges --------------------------------------------------------

    override suspend fun dailySummary(userId: String, date: LocalDate, zone: ZoneId, now: Instant): DailySummary {
        val caps = capabilities()
        val range = DayRanges.dayRange(date, zone)
        val filter = TimeRangeFilter.between(range.startInclusive, range.endExclusive)
        val sources = mutableSetOf<String>()
        var manualSeen = false
        val shared = mutableSetOf<String>()

        /**
         * Health Connect can't aggregate by recording method, and one app can write both automatic
         * and manual records. Take the deduplicated total (a phone and a watch never double-count)
         * and subtract what was typed in by hand.
         */
        suspend fun <R : Record> totalWithoutManual(
            type: HealthDataType,
            record: KClass<R>,
            metric: AggregateMetric<*>,
            interval: (R) -> Pair<Instant, Instant>,
            manualValue: (R) -> Double,
        ): Double? {
            if (!caps.granted(type)) return null
            shared += type.challengeId() ?: return null
            return guarded(null) {
                val records = readAll(record, filter)
                if (records.isEmpty()) return@guarded null
                sources += records.map { it.metadata.dataOrigin.packageName }
                // The total is deduplicated, so the manual part must be too: the same entry saved twice
                // (or by two apps) counts once in the total and must be taken away once.
                val manual = HealthMath.dedupedTotal(records.filter(::isManual).map { TimedValue(interval(it), manualValue(it)) })
                if (manual > 0) manualSeen = true
                val total = numeric(client.aggregate(AggregateRequest(setOf(metric), filter)), metric) ?: 0.0
                (total - manual).coerceAtLeast(0.0)
            }
        }

        val steps = totalWithoutManual(HealthDataType.STEPS, StepsRecord::class, StepsRecord.COUNT_TOTAL, { it.startTime to it.endTime }) { it.count.toDouble() }
        val distance = totalWithoutManual(HealthDataType.DISTANCE, DistanceRecord::class, DistanceRecord.DISTANCE_TOTAL, { it.startTime to it.endTime }) { it.distance.inMeters }
        val activeCalories = totalWithoutManual(HealthDataType.ACTIVE_CALORIES, ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, { it.startTime to it.endTime }) { it.energy.inKilocalories }
        val floors = totalWithoutManual(HealthDataType.FLOORS, FloorsClimbedRecord::class, FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL, { it.startTime to it.endTime }) { it.floors }

        val sessions = if (caps.granted(HealthDataType.EXERCISE)) guarded(emptyList()) { readAll(ExerciseSessionRecord::class, filter) } else emptyList()
        sources += sessions.map { it.metadata.dataOrigin.packageName }
        if (sessions.any(::isManual)) manualSeen = true
        val eligible = sessions.filterNot(::isManual)
        var workoutMinutes: Int? = null
        var workoutCount: Int? = null
        val byType = mutableMapOf<String, WorkoutTypeTotal>()
        if (caps.granted(HealthDataType.EXERCISE)) {
            shared += listOf("workout_minutes", "workout_count")
            workoutMinutes = eligible.sumOf { DayRanges.overlapMinutes(it.startTime, it.endTime, date, zone) }
            workoutCount = eligible.count { Duration.between(it.startTime, it.endTime).toMinutes() >= WorkoutSummary.MIN_DURATION_MINUTES }
            for (session in eligible) {
                val type = exerciseTypeName(session.exerciseType)
                val minutes = DayRanges.overlapMinutes(session.startTime, session.endTime, date, zone)
                val counted = if (Duration.between(session.startTime, session.endTime).toMinutes() >= WorkoutSummary.MIN_DURATION_MINUTES) 1 else 0
                val meters = if (caps.granted(HealthDataType.DISTANCE)) guarded(null) {
                    numeric(
                        client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(session.startTime, session.endTime))),
                        DistanceRecord.DISTANCE_TOTAL,
                    )
                } else null
                val previous = byType[type]
                byType[type] = WorkoutTypeTotal(
                    minutes = (previous?.minutes ?: 0) + minutes,
                    count = (previous?.count ?: 0) + counted,
                    distanceMeters = if (meters == null && previous?.distanceMeters == null) null else (previous?.distanceMeters ?: 0.0) + (meters ?: 0.0),
                )
            }
        }

        // Active minutes: weighted intensity minutes when the device writes them, else workout minutes.
        val activeMinutes: Int? = when {
            caps.granted(HealthDataType.ACTIVITY_INTENSITY) -> {
                shared += "active_minutes"
                guarded(null) {
                    val records = readAll(ActivityIntensityRecord::class, filter)
                    if (records.isEmpty()) return@guarded workoutMinutes
                    val manual = records.filter(::isManual).sumOf {
                        val minutes = Duration.between(it.startTime, it.endTime).toMinutes()
                        if (it.activityIntensityType == ActivityIntensityRecord.ACTIVITY_INTENSITY_TYPE_VIGOROUS) minutes * 2 else minutes
                    }
                    val total = client.aggregate(AggregateRequest(setOf(ActivityIntensityRecord.INTENSITY_MINUTES_TOTAL), filter))[ActivityIntensityRecord.INTENSITY_MINUTES_TOTAL] ?: 0L
                    (total - manual).coerceAtLeast(0).toInt()
                }
            }
            workoutMinutes != null -> {
                shared += "active_minutes"
                workoutMinutes
            }
            else -> null
        }

        val mindful = if (caps.granted(HealthDataType.MINDFULNESS)) {
            shared += "mindful_minutes"
            guarded(null) {
                client.aggregate(AggregateRequest(setOf(MindfulnessSessionRecord.MINDFULNESS_DURATION_TOTAL), filter))[MindfulnessSessionRecord.MINDFULNESS_DURATION_TOTAL]?.toMinutes()?.toInt()
            }
        } else null

        val sleep = if (caps.granted(HealthDataType.SLEEP)) {
            shared += "sleep_minutes"
            val sessionsForNight = sleepSessionsBetween(DayRanges.sleepWindow(date, zone).let { it.startInclusive to it.endExclusive }, zone)
                .filter { it.end.atZone(zone).toLocalDate() == date }
            SleepSelection.mainSession(sessionsForNight)?.toSummary()
        } else null

        return DailySummary(
            userId = userId,
            date = date,
            timeZone = zone.id,
            steps = steps?.toLong(),
            distanceMeters = distance,
            activeCalories = activeCalories,
            activeMinutes = activeMinutes,
            workoutMinutes = workoutMinutes,
            workoutCount = workoutCount,
            workoutsByType = byType,
            floors = floors,
            mindfulMinutes = mindful,
            sleep = sleep,
            shared = shared,
            sources = sources.toList().sorted(),
            manualExcluded = manualSeen,
            computedAt = now,
        )
    }

    // --- Series --------------------------------------------------------------------------------

    private data class Aggregation(val avg: AggregateMetric<*>, val min: AggregateMetric<*>? = null, val max: AggregateMetric<*>? = null)

    private fun aggregationFor(metric: HealthMetric): Aggregation? = when (metric) {
        HealthMetric.STEPS -> Aggregation(StepsRecord.COUNT_TOTAL)
        HealthMetric.WHEELCHAIR_PUSHES -> Aggregation(WheelchairPushesRecord.COUNT_TOTAL)
        HealthMetric.DISTANCE -> Aggregation(DistanceRecord.DISTANCE_TOTAL)
        HealthMetric.ACTIVE_MINUTES -> Aggregation(ActivityIntensityRecord.INTENSITY_MINUTES_TOTAL)
        HealthMetric.WORKOUT_MINUTES -> Aggregation(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
        HealthMetric.ACTIVE_CALORIES -> Aggregation(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        HealthMetric.TOTAL_CALORIES -> Aggregation(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        HealthMetric.FLOORS -> Aggregation(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
        HealthMetric.ELEVATION -> Aggregation(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
        HealthMetric.HYDRATION -> Aggregation(HydrationRecord.VOLUME_TOTAL)
        HealthMetric.ENERGY_EATEN -> Aggregation(NutritionRecord.ENERGY_TOTAL)
        HealthMetric.PROTEIN -> Aggregation(NutritionRecord.PROTEIN_TOTAL)
        HealthMetric.CARBS -> Aggregation(NutritionRecord.TOTAL_CARBOHYDRATE_TOTAL)
        HealthMetric.FAT -> Aggregation(NutritionRecord.TOTAL_FAT_TOTAL)
        HealthMetric.MINDFULNESS -> Aggregation(MindfulnessSessionRecord.MINDFULNESS_DURATION_TOTAL)
        HealthMetric.HEART_RATE -> Aggregation(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_MAX)
        HealthMetric.RESTING_HEART_RATE -> Aggregation(RestingHeartRateRecord.BPM_AVG, RestingHeartRateRecord.BPM_MIN, RestingHeartRateRecord.BPM_MAX)
        HealthMetric.SKIN_TEMPERATURE -> Aggregation(SkinTemperatureRecord.TEMPERATURE_DELTA_AVG, SkinTemperatureRecord.TEMPERATURE_DELTA_MIN, SkinTemperatureRecord.TEMPERATURE_DELTA_MAX)
        HealthMetric.SPEED -> Aggregation(SpeedRecord.SPEED_AVG, SpeedRecord.SPEED_MIN, SpeedRecord.SPEED_MAX)
        HealthMetric.POWER -> Aggregation(PowerRecord.POWER_AVG, PowerRecord.POWER_MIN, PowerRecord.POWER_MAX)
        else -> null
    }

    /** Converts any aggregate value to the unit the app stores (meters, kcal, minutes, ml, grams…). */
    private fun numeric(result: AggregationResult, metric: AggregateMetric<*>): Double? =
        when (val value = result[metric]) {
            null -> null
            is Long -> value.toDouble()
            is Double -> value
            is Duration -> value.toMinutes().toDouble()
            is Energy -> value.inKilocalories
            is Length -> value.inMeters
            is Volume -> value.inMilliliters
            is Mass -> value.inGrams
            is TemperatureDelta -> value.inCelsius
            is Velocity -> value.inMetersPerSecond
            is Power -> value.inWatts
            else -> null
        }

    override suspend fun series(metric: HealthMetric, range: LocalDateRange, bucket: Bucket, zone: ZoneId): List<MetricPoint> {
        if (!capabilities().granted(metric.dataType)) return emptyList()
        val aggregation = aggregationFor(metric)
        if (metric == HealthMetric.SLEEP) return sleepSeries(range, bucket, zone)
        if (aggregation == null) return sampleSeries(metric, range, bucket, zone)
        val metrics = setOfNotNull(aggregation.avg, aggregation.min, aggregation.max)
        return guarded(emptyList()) {
            if (bucket == Bucket.HOUR) {
                val start = range.start.atStartOfDay(zone).toInstant()
                val end = range.endInclusive.plusDays(1).atStartOfDay(zone).toInstant()
                client.aggregateGroupByDuration(
                    AggregateGroupByDurationRequest(metrics, TimeRangeFilter.between(start, end), Duration.ofHours(1)),
                ).map { group ->
                    MetricPoint(
                        start = group.startTime,
                        end = group.endTime,
                        value = numeric(group.result, aggregation.avg),
                        min = aggregation.min?.let { numeric(group.result, it) },
                        max = aggregation.max?.let { numeric(group.result, it) },
                        origins = group.result.dataOrigins.map { it.packageName },
                    )
                }
            } else {
                val slicer = when (bucket) {
                    Bucket.WEEK -> Period.ofWeeks(1)
                    Bucket.MONTH -> Period.ofMonths(1)
                    else -> Period.ofDays(1)
                }
                val start = when (bucket) {
                    Bucket.WEEK -> range.start.with(java.time.DayOfWeek.MONDAY)
                    Bucket.MONTH -> range.start.withDayOfMonth(1)
                    else -> range.start
                }
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics,
                        TimeRangeFilter.between(start.atStartOfDay(), range.endInclusive.plusDays(1).atStartOfDay()),
                        slicer,
                    ),
                ).map { group ->
                    MetricPoint(
                        start = group.startTime.atZone(zone).toInstant(),
                        end = group.endTime.atZone(zone).toInstant(),
                        value = numeric(group.result, aggregation.avg),
                        min = aggregation.min?.let { numeric(group.result, it) },
                        max = aggregation.max?.let { numeric(group.result, it) },
                        origins = group.result.dataOrigins.map { it.packageName },
                    )
                }
            }
        }
    }

    /** Bucket boundaries in local time, matching what aggregateGroupByPeriod would return. */
    private fun buckets(range: LocalDateRange, bucket: Bucket, zone: ZoneId): List<Pair<Instant, Instant>> {
        val out = mutableListOf<Pair<Instant, Instant>>()
        when (bucket) {
            Bucket.HOUR -> {
                var t = range.start.atStartOfDay(zone).toInstant()
                val end = range.endInclusive.plusDays(1).atStartOfDay(zone).toInstant()
                while (t.isBefore(end)) {
                    out += t to t.plus(Duration.ofHours(1))
                    t = t.plus(Duration.ofHours(1))
                }
            }
            else -> {
                var date = when (bucket) {
                    Bucket.WEEK -> range.start.with(java.time.DayOfWeek.MONDAY)
                    Bucket.MONTH -> range.start.withDayOfMonth(1)
                    else -> range.start
                }
                while (!date.isAfter(range.endInclusive)) {
                    val next = when (bucket) {
                        Bucket.WEEK -> date.plusWeeks(1)
                        Bucket.MONTH -> date.plusMonths(1)
                        else -> date.plusDays(1)
                    }
                    out += date.atStartOfDay(zone).toInstant() to next.atStartOfDay(zone).toInstant()
                    date = next
                }
            }
        }
        return out
    }

    private data class Sample(val time: Instant, val value: Double, val origin: String)

    /** HRV, SpO2 and respiratory rate have no aggregate: bucket samples, one origin per bucket. */
    private suspend fun sampleSeries(metric: HealthMetric, range: LocalDateRange, bucket: Bucket, zone: ZoneId): List<MetricPoint> {
        val samples: List<Sample> = when (metric) {
            HealthMetric.HRV -> instantSamples(HeartRateVariabilityRmssdRecord::class, range, zone) { it.heartRateVariabilityMillis }
            HealthMetric.SPO2 -> instantSamples(OxygenSaturationRecord::class, range, zone) { it.percentage.value }
            HealthMetric.RESPIRATORY_RATE -> instantSamples(RespiratoryRateRecord::class, range, zone) { it.rate }
            else -> readings(metric, range, zone).map { Sample(it.time, it.value, it.origin) }
        }
        return buckets(range, bucket, zone).mapNotNull { (start, end) ->
            val inBucket = samples.filter { !it.time.isBefore(start) && it.time.isBefore(end) }
            if (inBucket.isEmpty()) return@mapNotNull MetricPoint(start, end, null)
            val origin = HealthMath.dominantOrigin(inBucket) { it.origin }
            val values = inBucket.filter { it.origin == origin }.map { it.value }
            val isReading = metric.kind == fit.bandwagon.core.model.MetricKind.READING
            MetricPoint(
                start = start,
                end = end,
                value = if (isReading) inBucket.maxBy { it.time }.value else values.average(),
                min = values.minOrNull(),
                max = values.maxOrNull(),
                origins = listOfNotNull(origin),
            )
        }
    }

    private suspend fun <R : Record> instantSamples(
        record: KClass<R>,
        range: LocalDateRange,
        zone: ZoneId,
        value: (R) -> Double,
    ): List<Sample> = guarded(emptyList()) {
        readAll(record, rangeFilter(range, zone)).map { Sample(timesOf(it).second, value(it), it.metadata.dataOrigin.packageName) }
    }

    private fun rangeFilter(range: LocalDateRange, zone: ZoneId) = TimeRangeFilter.between(
        range.start.atStartOfDay(zone).toInstant(),
        range.endInclusive.plusDays(1).atStartOfDay(zone).toInstant(),
    )

    /** Sleep per night (asleep minutes of the main session), bucketed by wake date. */
    private suspend fun sleepSeries(range: LocalDateRange, bucket: Bucket, zone: ZoneId): List<MetricPoint> {
        val nights = sleepSessions(range, zone)
            .groupBy { it.end.atZone(zone).toLocalDate() }
            .mapValues { (_, sessions) -> SleepSelection.mainSession(sessions)!! }
        return buckets(range, bucket, zone).map { (start, end) ->
            val values = nights.filterKeys { d ->
                val t = d.atStartOfDay(zone).toInstant()
                !t.isBefore(start) && t.isBefore(end)
            }.values.map { it.asleepMinutes.toDouble() }
            MetricPoint(start, end, values.takeIf { it.isNotEmpty() }?.average(), values.minOrNull(), values.maxOrNull())
        }
    }

    // --- Readings ------------------------------------------------------------------------------

    override suspend fun readings(metric: HealthMetric, range: LocalDateRange, zone: ZoneId): List<Reading> {
        if (!capabilities().granted(metric.dataType)) return emptyList()
        val filter = rangeFilter(range, zone)
        return guarded(emptyList()) {
            when (metric) {
                HealthMetric.WEIGHT -> readAll(WeightRecord::class, filter).map { reading(it, it.weight.inKilograms) }
                HealthMetric.HEIGHT -> readAll(HeightRecord::class, filter).map { reading(it, it.height.inMeters) }
                HealthMetric.BODY_FAT -> readAll(BodyFatRecord::class, filter).map { reading(it, it.percentage.value) }
                HealthMetric.LEAN_BODY_MASS -> readAll(LeanBodyMassRecord::class, filter).map { reading(it, it.mass.inKilograms) }
                HealthMetric.BODY_WATER -> readAll(BodyWaterMassRecord::class, filter).map { reading(it, it.mass.inKilograms) }
                HealthMetric.BONE_MASS -> readAll(BoneMassRecord::class, filter).map { reading(it, it.mass.inKilograms) }
                HealthMetric.BMR -> readAll(BasalMetabolicRateRecord::class, filter).map { reading(it, it.basalMetabolicRate.inKilocaloriesPerDay) }
                HealthMetric.VO2_MAX -> readAll(Vo2MaxRecord::class, filter).map { reading(it, it.vo2MillilitersPerMinuteKilogram) }
                HealthMetric.BODY_TEMPERATURE -> readAll(BodyTemperatureRecord::class, filter).map { reading(it, it.temperature.inCelsius) }
                HealthMetric.BLOOD_PRESSURE -> readAll(BloodPressureRecord::class, filter).map {
                    reading(it, it.systolic.inMillimetersOfMercury, secondary = it.diastolic.inMillimetersOfMercury)
                }
                HealthMetric.BLOOD_GLUCOSE -> readAll(BloodGlucoseRecord::class, filter).map {
                    reading(it, it.level.inMilligramsPerDeciliter, note = BloodGlucoseRecord.RELATION_TO_MEAL_INT_TO_STRING_MAP[it.relationToMeal])
                }
                HealthMetric.HRV -> readAll(HeartRateVariabilityRmssdRecord::class, filter).map { reading(it, it.heartRateVariabilityMillis) }
                HealthMetric.SPO2 -> readAll(OxygenSaturationRecord::class, filter).map { reading(it, it.percentage.value) }
                HealthMetric.RESPIRATORY_RATE -> readAll(RespiratoryRateRecord::class, filter).map { reading(it, it.rate) }
                HealthMetric.RESTING_HEART_RATE -> readAll(RestingHeartRateRecord::class, filter).map { reading(it, it.beatsPerMinute.toDouble()) }
                HealthMetric.SKIN_TEMPERATURE -> readAll(SkinTemperatureRecord::class, filter).flatMap { record ->
                    record.deltas.map { Reading(it.time, it.delta.inCelsius, origin = record.metadata.dataOrigin.packageName) }
                }
                HealthMetric.HEART_RATE -> readAll(HeartRateRecord::class, filter).flatMap { record ->
                    record.samples.map { Reading(it.time, it.beatsPerMinute.toDouble(), origin = record.metadata.dataOrigin.packageName) }
                }
                else -> emptyList()
            }.sortedBy { it.time }
        }
    }

    private fun reading(record: Record, value: Double, secondary: Double? = null, note: String? = null) = Reading(
        time = timesOf(record).second,
        value = value,
        secondary = secondary,
        origin = record.metadata.dataOrigin.packageName,
        manual = isManual(record),
        note = note,
    )

    // --- Sessions -----------------------------------------------------------------------------

    override suspend fun sleepSessions(range: LocalDateRange, zone: ZoneId): List<SleepSession> {
        if (!capabilities().granted(HealthDataType.SLEEP)) return emptyList()
        val from = DayRanges.sleepWindow(range.start, zone).startInclusive
        val to = DayRanges.sleepWindow(range.endInclusive, zone).endExclusive
        return sleepSessionsBetween(from to to, zone).filter { it.end.atZone(zone).toLocalDate() in range }
    }

    private suspend fun sleepSessionsBetween(window: Pair<Instant, Instant>, zone: ZoneId): List<SleepSession> =
        guarded(emptyList()) {
            readAll(SleepSessionRecord::class, TimeRangeFilter.between(window.first, window.second)).map { session ->
                SleepSession(
                    start = session.startTime,
                    end = session.endTime,
                    zoneId = session.startZoneOffset?.id ?: zone.id,
                    stages = session.stages.map { SleepStage(stageType(it.stage), it.startTime, it.endTime) },
                    origin = session.metadata.dataOrigin.packageName,
                    title = session.title,
                )
            }
        }

    private fun stageType(stage: Int): SleepStageType = when (stage) {
        SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> SleepStageType.AWAKE
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStageType.OUT_OF_BED
        SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStageType.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStageType.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> SleepStageType.REM
        else -> SleepStageType.ASLEEP
    }

    override suspend fun workouts(range: LocalDateRange, zone: ZoneId): List<WorkoutSummary> {
        val caps = capabilities()
        if (!caps.granted(HealthDataType.EXERCISE)) return emptyList()
        return guarded(emptyList()) {
            readAll(ExerciseSessionRecord::class, rangeFilter(range, zone)).map { session ->
                val window = TimeRangeFilter.between(session.startTime, session.endTime)
                val metrics = buildSet<AggregateMetric<*>> {
                    if (caps.granted(HealthDataType.DISTANCE)) add(DistanceRecord.DISTANCE_TOTAL)
                    if (caps.granted(HealthDataType.ACTIVE_CALORIES)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                    if (caps.granted(HealthDataType.HEART_RATE)) {
                        add(HeartRateRecord.BPM_AVG)
                        add(HeartRateRecord.BPM_MAX)
                    }
                }
                val result = if (metrics.isEmpty()) null else guarded(null) { client.aggregate(AggregateRequest(metrics, window)) }
                WorkoutSummary(
                    start = session.startTime,
                    end = session.endTime,
                    durationMinutes = Duration.between(session.startTime, session.endTime).toMinutes().toInt(),
                    title = session.title,
                    recordingMethod = when (session.metadata.recordingMethod) {
                        Metadata.RECORDING_METHOD_MANUAL_ENTRY -> RecordingMethod.MANUAL
                        Metadata.RECORDING_METHOD_AUTOMATICALLY_RECORDED, Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> RecordingMethod.AUTOMATIC
                        else -> RecordingMethod.UNKNOWN
                    },
                    type = exerciseTypeName(session.exerciseType),
                    distanceMeters = result?.let { numeric(it, DistanceRecord.DISTANCE_TOTAL) },
                    activeCalories = result?.let { numeric(it, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL) },
                    avgHeartRate = result?.get(HeartRateRecord.BPM_AVG)?.toInt(),
                    maxHeartRate = result?.get(HeartRateRecord.BPM_MAX)?.toInt(),
                    origin = session.metadata.dataOrigin.packageName,
                )
            }.sortedByDescending { it.start }
        }
    }

    private fun exerciseTypeName(type: Int): String =
        ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[type]?.uppercase() ?: "OTHER"

    override suspend fun hasData(type: HealthDataType, since: Instant): Boolean {
        val caps = capabilities()
        if (!caps.granted(type)) return false
        val record = HealthPermissions.ENTRIES.getValue(type).record
        return guarded(false) {
            client.readRecords(
                ReadRecordsRequest(recordType = record, timeRangeFilter = TimeRangeFilter.after(since), pageSize = 1),
            ).records.isNotEmpty()
        }
    }

    override suspend fun recentOrigins(since: Instant): Map<String, Instant> {
        val caps = capabilities()
        val out = mutableMapOf<String, Instant>()
        for (type in listOf(HealthDataType.STEPS, HealthDataType.SLEEP, HealthDataType.EXERCISE, HealthDataType.HEART_RATE, HealthDataType.WEIGHT)) {
            if (!caps.granted(type)) continue
            val record = HealthPermissions.ENTRIES.getValue(type).record
            guarded(Unit) {
                client.readRecords(
                    ReadRecordsRequest(recordType = record, timeRangeFilter = TimeRangeFilter.after(since), ascendingOrder = false, pageSize = 200),
                ).records.forEach { r ->
                    val at = timesOf(r).second
                    val name = r.metadata.dataOrigin.packageName
                    if (out[name]?.isBefore(at) != false) out[name] = at
                }
            }
        }
        return out
    }

    /** Reads every page; a phone can write more step records in a day than one page holds. */
    private suspend fun <T : Record> readAll(type: KClass<T>, filter: TimeRangeFilter): List<T> {
        val records = mutableListOf<T>()
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = filter,
                    ascendingOrder = true,
                    pageSize = 5_000,
                    pageToken = pageToken,
                ),
            )
            records += response.records
            pageToken = response.pageToken
        } while (pageToken != null)
        return records
    }

    override suspend fun changesSince(token: String?, readable: Set<HealthDataType>): ChangeResult {
        val recordTypes = readable.mapNotNull { HealthPermissions.ENTRIES[it]?.record }.toSet()
        if (recordTypes.isEmpty()) return ChangeResult(nextToken = null, changedDates = emptySet())

        var activeToken = token ?: client.getChangesToken(ChangesTokenRequest(recordTypes = recordTypes))
        val zone = ZoneId.systemDefault()
        val dates = mutableSetOf<LocalDate>()
        val types = mutableSetOf<HealthDataType>()
        var expired = false
        var hasMore = true

        while (hasMore) {
            val response = client.getChanges(activeToken)
            if (response.changesTokenExpired) {
                expired = true
                break
            }
            response.changes.forEach { change ->
                when (change) {
                    is UpsertionChange -> {
                        val record = change.record
                        HealthPermissions.typeOf(record::class)?.let(types::add)
                        val (start, end) = timesOf(record)
                        dates += start.atZone(zone).toLocalDate()
                        dates += end.atZone(zone).toLocalDate()
                    }
                    // A deletion carries no record or time, so the caller refreshes recent days.
                    is DeletionChange -> dates += LocalDate.now(zone)
                }
            }
            activeToken = response.nextChangesToken
            hasMore = response.hasMore
        }

        return ChangeResult(
            nextToken = if (expired) null else activeToken,
            changedDates = dates,
            changedTypes = types,
            expired = expired,
        )
    }
}

/**
 * Start and end of any record. Health Connect keeps its instant/interval record interfaces
 * internal, so read the public getters; an instantaneous record returns its time twice.
 */
internal fun timesOf(record: Record): Pair<Instant, Instant> {
    val type = record.javaClass
    fun instant(name: String): Instant? = runCatching { type.getMethod(name).invoke(record) as? Instant }.getOrNull()
    val time = instant("getTime")
    if (time != null) return time to time
    val start = instant("getStartTime") ?: record.metadata.lastModifiedTime
    return start to (instant("getEndTime") ?: start)
}

/** Challenge metric id for a data type, when a challenge can use it. */
internal fun HealthDataType.challengeId(): String? = when (this) {
    HealthDataType.STEPS -> "steps"
    HealthDataType.DISTANCE -> "distance"
    HealthDataType.ACTIVE_CALORIES -> "active_calories"
    HealthDataType.FLOORS -> "floors"
    else -> null
}

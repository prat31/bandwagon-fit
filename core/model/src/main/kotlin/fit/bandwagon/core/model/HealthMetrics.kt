package fit.bandwagon.core.model

import java.time.Instant
import java.time.LocalDate

/** How a metric is read and combined over a period. */
enum class MetricKind {
    /** Health Connect aggregates and deduplicates a total (steps, calories, floors…). */
    CUMULATIVE,

    /** Many samples a day from one device: shown as average with min/max (heart rate, HRV…). */
    SAMPLE,

    /** Occasional readings, latest wins (weight, blood pressure, VO2 max…). */
    READING,
}

enum class MetricUnit {
    COUNT, METERS, KCAL, MINUTES, BPM, MILLISECONDS, PERCENT, BREATHS_PER_MIN, CELSIUS_DELTA, CELSIUS,
    KILOGRAMS, METERS_HEIGHT, MMHG, MG_DL, MILLILITERS, GRAMS, ML_KG_MIN, FLOORS, KCAL_PER_DAY,
}

/** How values over a period are summarised in charts and headlines. */
enum class PeriodStat { TOTAL_AND_DAILY_AVG, AVERAGE, LATEST }

/**
 * The Health tab's metric catalogue. Each entry names its Health Connect type, how to read it and
 * how to summarise it. Adding a metric here plus a read in `core:health` is all a tile needs.
 */
enum class HealthMetric(
    val label: String,
    val dataType: HealthDataType,
    val kind: MetricKind,
    val unit: MetricUnit,
    val stat: PeriodStat,
    /** Personal goal key in [Goals], if the metric has one. */
    val goalKey: String? = null,
) {
    STEPS("Steps", HealthDataType.STEPS, MetricKind.CUMULATIVE, MetricUnit.COUNT, PeriodStat.TOTAL_AND_DAILY_AVG, "steps"),
    WHEELCHAIR_PUSHES("Wheelchair pushes", HealthDataType.WHEELCHAIR_PUSHES, MetricKind.CUMULATIVE, MetricUnit.COUNT, PeriodStat.TOTAL_AND_DAILY_AVG),
    DISTANCE("Distance", HealthDataType.DISTANCE, MetricKind.CUMULATIVE, MetricUnit.METERS, PeriodStat.TOTAL_AND_DAILY_AVG),
    ACTIVE_MINUTES("Active minutes", HealthDataType.ACTIVITY_INTENSITY, MetricKind.CUMULATIVE, MetricUnit.MINUTES, PeriodStat.TOTAL_AND_DAILY_AVG, "activeMinutesWeekly"),
    WORKOUT_MINUTES("Workout minutes", HealthDataType.EXERCISE, MetricKind.CUMULATIVE, MetricUnit.MINUTES, PeriodStat.TOTAL_AND_DAILY_AVG),
    ACTIVE_CALORIES("Active calories", HealthDataType.ACTIVE_CALORIES, MetricKind.CUMULATIVE, MetricUnit.KCAL, PeriodStat.TOTAL_AND_DAILY_AVG),
    TOTAL_CALORIES("Total calories", HealthDataType.TOTAL_CALORIES, MetricKind.CUMULATIVE, MetricUnit.KCAL, PeriodStat.TOTAL_AND_DAILY_AVG),
    FLOORS("Floors", HealthDataType.FLOORS, MetricKind.CUMULATIVE, MetricUnit.FLOORS, PeriodStat.TOTAL_AND_DAILY_AVG),
    ELEVATION("Elevation gained", HealthDataType.ELEVATION, MetricKind.CUMULATIVE, MetricUnit.METERS, PeriodStat.TOTAL_AND_DAILY_AVG),
    VO2_MAX("VO2 max", HealthDataType.VO2_MAX, MetricKind.READING, MetricUnit.ML_KG_MIN, PeriodStat.LATEST),
    SPEED("Speed", HealthDataType.SPEED, MetricKind.SAMPLE, MetricUnit.METERS, PeriodStat.AVERAGE),
    POWER("Power", HealthDataType.POWER, MetricKind.SAMPLE, MetricUnit.COUNT, PeriodStat.AVERAGE),
    SLEEP("Sleep", HealthDataType.SLEEP, MetricKind.CUMULATIVE, MetricUnit.MINUTES, PeriodStat.AVERAGE, "sleepMinutes"),
    HEART_RATE("Heart rate", HealthDataType.HEART_RATE, MetricKind.SAMPLE, MetricUnit.BPM, PeriodStat.AVERAGE),
    RESTING_HEART_RATE("Resting heart rate", HealthDataType.RESTING_HEART_RATE, MetricKind.SAMPLE, MetricUnit.BPM, PeriodStat.AVERAGE),
    HRV("Heart rate variability", HealthDataType.HRV, MetricKind.SAMPLE, MetricUnit.MILLISECONDS, PeriodStat.AVERAGE),
    SPO2("Blood oxygen", HealthDataType.SPO2, MetricKind.SAMPLE, MetricUnit.PERCENT, PeriodStat.AVERAGE),
    RESPIRATORY_RATE("Respiratory rate", HealthDataType.RESPIRATORY_RATE, MetricKind.SAMPLE, MetricUnit.BREATHS_PER_MIN, PeriodStat.AVERAGE),
    SKIN_TEMPERATURE("Skin temperature", HealthDataType.SKIN_TEMPERATURE, MetricKind.SAMPLE, MetricUnit.CELSIUS_DELTA, PeriodStat.AVERAGE),
    BODY_TEMPERATURE("Body temperature", HealthDataType.BODY_TEMPERATURE, MetricKind.READING, MetricUnit.CELSIUS, PeriodStat.LATEST),
    BLOOD_PRESSURE("Blood pressure", HealthDataType.BLOOD_PRESSURE, MetricKind.READING, MetricUnit.MMHG, PeriodStat.LATEST),
    BLOOD_GLUCOSE("Blood glucose", HealthDataType.BLOOD_GLUCOSE, MetricKind.READING, MetricUnit.MG_DL, PeriodStat.LATEST),
    WEIGHT("Weight", HealthDataType.WEIGHT, MetricKind.READING, MetricUnit.KILOGRAMS, PeriodStat.LATEST),
    BODY_FAT("Body fat", HealthDataType.BODY_FAT, MetricKind.READING, MetricUnit.PERCENT, PeriodStat.LATEST),
    LEAN_BODY_MASS("Lean mass", HealthDataType.LEAN_BODY_MASS, MetricKind.READING, MetricUnit.KILOGRAMS, PeriodStat.LATEST),
    BODY_WATER("Body water", HealthDataType.BODY_WATER, MetricKind.READING, MetricUnit.KILOGRAMS, PeriodStat.LATEST),
    BONE_MASS("Bone mass", HealthDataType.BONE_MASS, MetricKind.READING, MetricUnit.KILOGRAMS, PeriodStat.LATEST),
    BMR("Basal metabolic rate", HealthDataType.BMR, MetricKind.READING, MetricUnit.KCAL_PER_DAY, PeriodStat.LATEST),
    HEIGHT("Height", HealthDataType.HEIGHT, MetricKind.READING, MetricUnit.METERS_HEIGHT, PeriodStat.LATEST),
    HYDRATION("Hydration", HealthDataType.HYDRATION, MetricKind.CUMULATIVE, MetricUnit.MILLILITERS, PeriodStat.TOTAL_AND_DAILY_AVG, "hydrationMl"),
    ENERGY_EATEN("Energy eaten", HealthDataType.NUTRITION, MetricKind.CUMULATIVE, MetricUnit.KCAL, PeriodStat.TOTAL_AND_DAILY_AVG),
    PROTEIN("Protein", HealthDataType.NUTRITION, MetricKind.CUMULATIVE, MetricUnit.GRAMS, PeriodStat.TOTAL_AND_DAILY_AVG),
    CARBS("Carbohydrates", HealthDataType.NUTRITION, MetricKind.CUMULATIVE, MetricUnit.GRAMS, PeriodStat.TOTAL_AND_DAILY_AVG),
    FAT("Fat", HealthDataType.NUTRITION, MetricKind.CUMULATIVE, MetricUnit.GRAMS, PeriodStat.TOTAL_AND_DAILY_AVG),
    MINDFULNESS("Mindfulness", HealthDataType.MINDFULNESS, MetricKind.CUMULATIVE, MetricUnit.MINUTES, PeriodStat.TOTAL_AND_DAILY_AVG),
    ;

    val category: HealthCategory get() = dataType.category
}

enum class Bucket { HOUR, DAY, WEEK, MONTH }

/** The chart periods on every metric detail screen. */
enum class ChartPeriod(val label: String, val bucket: Bucket, val days: Long) {
    DAY("D", Bucket.HOUR, 1),
    WEEK("W", Bucket.DAY, 7),
    MONTH("M", Bucket.DAY, 30),
    SIX_MONTHS("6M", Bucket.WEEK, 182),
    YEAR("Y", Bucket.MONTH, 365),
}

data class LocalDateRange(val start: LocalDate, val endInclusive: LocalDate) {
    val days: Long get() = java.time.temporal.ChronoUnit.DAYS.between(start, endInclusive) + 1

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)

    companion object {
        fun lastDays(end: LocalDate, days: Long) = LocalDateRange(end.minusDays(days - 1), end)
    }
}

/** One bucket of a metric. `value` is the total (cumulative) or average (sample) for the bucket. */
data class MetricPoint(
    val start: Instant,
    val end: Instant,
    val value: Double?,
    val min: Double? = null,
    val max: Double? = null,
    val origins: List<String> = emptyList(),
)

/** A point reading. Blood pressure uses [value] for systolic and [secondary] for diastolic. */
data class Reading(
    val time: Instant,
    val value: Double,
    val secondary: Double? = null,
    val origin: String = "",
    val manual: Boolean = false,
    /** Extra context such as a glucose reading's meal relation. */
    val note: String? = null,
)

/**
 * The four core targets other screens read. They follow the user's goals on Home (see
 * `GoalEngine.coreTargets`); defaults follow common guidance.
 */
data class Goals(
    val steps: Int = 8_000,
    /** WHO: 150 minutes of moderate activity a week. */
    val activeMinutesWeekly: Int = 150,
    /** Adults need 7 hours or more; 7h30 leaves room. */
    val sleepMinutes: Int = 450,
    val hydrationMl: Int = 2_000,
) {
    fun forKey(key: String?): Double? = when (key) {
        "steps" -> steps.toDouble()
        "activeMinutesWeekly" -> activeMinutesWeekly.toDouble()
        "sleepMinutes" -> sleepMinutes.toDouble()
        "hydrationMl" -> hydrationMl.toDouble()
        else -> null
    }
}

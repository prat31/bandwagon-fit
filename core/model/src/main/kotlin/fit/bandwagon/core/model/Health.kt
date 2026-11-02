package fit.bandwagon.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Result of checking whether a Health Connect implementation is usable on this device. */
enum class SourceAvailability {
    AVAILABLE,
    UPDATE_REQUIRED,
    UNSUPPORTED,
}

enum class HealthCategory(val label: String) {
    ACTIVITY("Activity"),
    SLEEP("Sleep"),
    HEART("Heart & vitals"),
    BODY("Body"),
    NUTRITION("Nutrition"),
    WELLNESS("Wellness"),
}

/**
 * Every Health Connect data type the app can read, platform-agnostic. `core:health` maps each
 * [REQUESTED] type to its Health Connect permission; the rest stay in code but are never asked for,
 * so their tiles never appear.
 */
enum class HealthDataType(val label: String, val category: HealthCategory) {
    STEPS("Steps", HealthCategory.ACTIVITY),
    DISTANCE("Distance", HealthCategory.ACTIVITY),
    ACTIVE_CALORIES("Active calories", HealthCategory.ACTIVITY),
    TOTAL_CALORIES("Total calories", HealthCategory.ACTIVITY),
    EXERCISE("Workouts", HealthCategory.ACTIVITY),
    FLOORS("Floors", HealthCategory.ACTIVITY),
    ELEVATION("Elevation", HealthCategory.ACTIVITY),
    ACTIVITY_INTENSITY("Active minutes", HealthCategory.ACTIVITY),
    VO2_MAX("VO2 max", HealthCategory.ACTIVITY),
    WHEELCHAIR_PUSHES("Wheelchair pushes", HealthCategory.ACTIVITY),
    SPEED("Speed", HealthCategory.ACTIVITY),
    POWER("Power", HealthCategory.ACTIVITY),
    SLEEP("Sleep", HealthCategory.SLEEP),
    HEART_RATE("Heart rate", HealthCategory.HEART),
    RESTING_HEART_RATE("Resting heart rate", HealthCategory.HEART),
    HRV("Heart rate variability", HealthCategory.HEART),
    SPO2("Blood oxygen", HealthCategory.HEART),
    RESPIRATORY_RATE("Respiratory rate", HealthCategory.HEART),
    SKIN_TEMPERATURE("Skin temperature", HealthCategory.HEART),
    BODY_TEMPERATURE("Body temperature", HealthCategory.HEART),
    BLOOD_PRESSURE("Blood pressure", HealthCategory.HEART),
    BLOOD_GLUCOSE("Blood glucose", HealthCategory.HEART),
    WEIGHT("Weight", HealthCategory.BODY),
    HEIGHT("Height", HealthCategory.BODY),
    BODY_FAT("Body fat", HealthCategory.BODY),
    LEAN_BODY_MASS("Lean mass", HealthCategory.BODY),
    BMR("Basal metabolic rate", HealthCategory.BODY),
    BODY_WATER("Body water", HealthCategory.BODY),
    BONE_MASS("Bone mass", HealthCategory.BODY),
    NUTRITION("Nutrition", HealthCategory.NUTRITION),
    HYDRATION("Hydration", HealthCategory.NUTRITION),
    MINDFULNESS("Mindfulness", HealthCategory.WELLNESS),
    ;

    companion object {
        /**
         * Types left out of this release: they only show as Health tab tiles, and Google Play rejects
         * permissions no feature visibly needs. Adding one back means the manifest, the Play Console
         * health declaration and the privacy policy too.
         */
        private val DEFERRED = setOf(
            ELEVATION, VO2_MAX, SPEED, POWER, SPO2, BODY_TEMPERATURE, BLOOD_PRESSURE, BLOOD_GLUCOSE,
            HEIGHT, BODY_FAT, LEAN_BODY_MASS, BMR, BODY_WATER, BONE_MASS, NUTRITION,
        )

        /**
         * Types the app asks for: each drives a challenge, goal or Home card. This list is the
         * manifest's health permissions and the Play Console declaration.
         */
        val REQUESTED: Set<HealthDataType> = entries.filterNot { it in DEFERRED }.toSet()
    }
}

enum class Access {
    GRANTED,
    NOT_GRANTED,

    /** The installed Health Connect doesn't offer this type yet (feature flag off). */
    UNSUPPORTED,
}

/** What the app may read right now. Recomputed on start, on resume and after permission results. */
data class HealthCapabilities(
    val access: Map<HealthDataType, Access> = emptyMap(),
    /** Background reads granted and supported. */
    val background: Boolean = false,
    /** False once Health Connect stops showing its dialog (two denials on Android 14+). */
    val canRequestAgain: Boolean = true,
) {
    fun granted(type: HealthDataType): Boolean = access[type] == Access.GRANTED

    val grantedTypes: Set<HealthDataType>
        get() = access.filterValues { it == Access.GRANTED }.keys

    /** Types Health Connect supports that the user hasn't shared. */
    val notGranted: Set<HealthDataType>
        get() = access.filterValues { it == Access.NOT_GRANTED }.keys

    val supported: Set<HealthDataType>
        get() = access.filterValues { it != Access.UNSUPPORTED }.keys

    val anyGranted: Boolean
        get() = grantedTypes.isNotEmpty()

    companion object {
        val NONE = HealthCapabilities()
    }
}

/** The four ways a metric can appear, so missing data is never shown as zero. */
enum class MetricState {
    READY,
    NOT_SHARED,
    NO_DATA,
    UNSUPPORTED,
}

/**
 * How a record was produced. Manual entries are excluded from competitive features.
 * Mirrors Health Connect's `Metadata.RECORDING_METHOD_*` constants without depending on Android.
 */
enum class RecordingMethod {
    AUTOMATIC,
    MANUAL,
    UNKNOWN,
}

data class SleepSummary(
    val start: Instant,
    val end: Instant,
    /** Duration in minutes with awake stages removed when the source provides stages. */
    val durationMinutes: Int,
    /** IANA zone id of the member at the time of the record. */
    val zoneId: String,
) {
    fun bedtimeLocal(): LocalTime = start.atZone(ZoneId.of(zoneId)).toLocalTime()

    fun wakeDate(): LocalDate = end.atZone(ZoneId.of(zoneId)).toLocalDate()
}

enum class SleepStageType {
    AWAKE,
    LIGHT,
    DEEP,
    REM,
    /** "Sleeping" or "unknown" from sources without stage detail. */
    ASLEEP,
    OUT_OF_BED,
}

data class SleepStage(val type: SleepStageType, val start: Instant, val end: Instant)

data class SleepSession(
    val start: Instant,
    val end: Instant,
    val zoneId: String,
    val stages: List<SleepStage> = emptyList(),
    val origin: String = "",
    val title: String? = null,
) {
    val inBedMinutes: Int
        get() = java.time.Duration.between(start, end).toMinutes().toInt()

    private fun minutesOf(type: SleepStageType): Int =
        stages.filter { it.type == type }.sumOf { java.time.Duration.between(it.start, it.end).toMinutes().toInt() }

    val awakeMinutes: Int
        get() = minutesOf(SleepStageType.AWAKE) + minutesOf(SleepStageType.OUT_OF_BED)

    val asleepMinutes: Int
        get() = (inBedMinutes - awakeMinutes).coerceAtLeast(0)

    /** True only when the source wrote real stages (light/deep/REM), not just "sleeping". */
    val hasStages: Boolean
        get() = stages.any { it.type == SleepStageType.LIGHT || it.type == SleepStageType.DEEP || it.type == SleepStageType.REM }

    fun stageMinutes(type: SleepStageType): Int = minutesOf(type)

    fun toSummary(): SleepSummary = SleepSummary(start, end, asleepMinutes, zoneId)
}

data class WorkoutSummary(
    val start: Instant,
    val end: Instant,
    val durationMinutes: Int,
    val title: String? = null,
    val recordingMethod: RecordingMethod = RecordingMethod.UNKNOWN,
    /** Platform-agnostic exercise type, e.g. RUNNING, WALKING, YOGA. */
    val type: String = "OTHER",
    val distanceMeters: Double? = null,
    val activeCalories: Double? = null,
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
    val origin: String = "",
) {
    /** A workout only counts for competitive features when it is at least 10 minutes and not manual. */
    val qualifies: Boolean
        get() = durationMinutes >= MIN_DURATION_MINUTES && recordingMethod != RecordingMethod.MANUAL

    companion object {
        const val MIN_DURATION_MINUTES = 10
    }
}

data class WorkoutTypeTotal(
    val minutes: Int,
    val count: Int,
    val distanceMeters: Double? = null,
)

/**
 * The only health data that ever leaves the device: one row per local date, holding the metrics a
 * challenge can use. Values are nullable so "not shared" is never displayed as zero, and [shared]
 * lists the challenge metric ids the user lets the app read.
 */
data class DailySummary(
    val userId: String,
    val date: LocalDate,
    val timeZone: String,
    val steps: Long? = null,
    val distanceMeters: Double? = null,
    val activeCalories: Double? = null,
    val activeMinutes: Int? = null,
    val workoutMinutes: Int? = null,
    val workoutCount: Int? = null,
    val workoutsByType: Map<String, WorkoutTypeTotal> = emptyMap(),
    val floors: Double? = null,
    val mindfulMinutes: Int? = null,
    val sleep: SleepSummary? = null,
    val shared: Set<String> = emptySet(),
    val sources: List<String> = emptyList(),
    /** True when at least one manual record was seen for this date and left out of the totals. */
    val manualExcluded: Boolean = false,
    val computedAt: Instant = Instant.EPOCH,
) {
    fun hasAnyData(): Boolean = steps != null || workoutMinutes != null || sleep != null ||
        distanceMeters != null || activeMinutes != null
}

data class ChangeResult(
    val nextToken: String?,
    val changedDates: Set<LocalDate>,
    /** Types whose records changed, so the local health store refreshes only those. */
    val changedTypes: Set<HealthDataType> = emptySet(),
    /** True when the Health Connect changes token expired (30 days); callers should backfill. */
    val expired: Boolean = false,
)

/** A workout or sleep session counts once it is attributed to a local date. */
object SleepSelection {
    /** The longest sleep session that overlaps the night window is treated as the main sleep. */
    fun mainSleep(sessions: List<SleepSummary>): SleepSummary? =
        sessions.maxWithOrNull(compareBy<SleepSummary> { it.durationMinutes }.thenBy { it.end })

    fun mainSession(sessions: List<SleepSession>): SleepSession? =
        sessions.maxWithOrNull(compareBy<SleepSession> { it.asleepMinutes }.thenBy { it.end })
}

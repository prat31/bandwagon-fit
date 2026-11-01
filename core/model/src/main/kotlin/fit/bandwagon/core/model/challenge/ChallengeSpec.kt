package fit.bandwagon.core.model.challenge

import fit.bandwagon.core.model.HealthDataType
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/*
 * Kotlin mirror of functions/src/domain/types.ts. A challenge is data, not a code path: every preset
 * (step battle, team goal, sleep pact, …) is one ChallengeSpec. The server scores; this copy powers
 * the create wizard, previews and the shared golden-fixture tests.
 */

@Serializable
enum class ChallengeKind { COMPETE, TARGET, TEAM }

@Serializable
enum class Aggregate { SUM, AVERAGE, DAYS_HIT, BEST_DAY, LONGEST_STREAK }

@Serializable
enum class Renderer { LEADERBOARD, PROGRESS, BOARD, BOSS, ROUTE }

@Serializable
data class MetricFilter(val workoutTypes: List<String>? = null)

@Serializable
data class MetricRef(val id: String, val filter: MetricFilter? = null)

@Serializable
data class Condition(
    val metric: String,
    val cmp: String,
    val value: Double,
    val toleranceMin: Double? = null,
)

/** op = value | cap (max) | atLeast (value) | all (conditions). Flat so it maps 1:1 to Firestore. */
@Serializable
data class DailyRule(
    val op: String,
    val max: Double? = null,
    val value: Double? = null,
    val conditions: List<Condition>? = null,
) {
    companion object {
        val VALUE = DailyRule("value")
        fun cap(max: Double) = DailyRule("cap", max = max)
        fun atLeast(value: Double) = DailyRule("atLeast", value = value)
    }
}

@Serializable
data class Goal(val target: Double)

@Serializable
data class ChallengeWindow(
    val start: String,
    val end: String,
    /** 0 = Sunday … 6 = Saturday. Null means every day. */
    val days: List<Int>? = null,
    val recurrence: String? = null,
) {
    val startDate: LocalDate get() = LocalDate.parse(start)
    val endDate: LocalDate get() = LocalDate.parse(end)
}

@Serializable
data class Ranking(val tieBreak: String? = null)

@Serializable
data class Display(
    val templateId: String,
    val renderer: Renderer,
    val title: String,
    val description: String? = null,
    val theme: Map<String, String>? = null,
)

@Serializable
data class ChallengeSpec(
    val schemaVersion: Int = 1,
    val kind: ChallengeKind,
    val metric: MetricRef,
    val daily: DailyRule,
    val aggregate: Aggregate,
    val goal: Goal? = null,
    val window: ChallengeWindow,
    val ranking: Ranking? = null,
    val groupStreak: Boolean = false,
    val display: Display,
) {
    val title: String get() = display.title
}

/** Challenge metrics, matching functions/src/domain/metrics.ts. */
enum class ChallengeMetric(
    val id: String,
    val label: String,
    val unitLabel: String,
    val dataType: HealthDataType,
) {
    STEPS("steps", "Steps", "steps", HealthDataType.STEPS),
    DISTANCE("distance", "Distance", "km", HealthDataType.DISTANCE),
    ACTIVE_CALORIES("active_calories", "Active calories", "kcal", HealthDataType.ACTIVE_CALORIES),
    ACTIVE_MINUTES("active_minutes", "Active minutes", "min", HealthDataType.ACTIVITY_INTENSITY),
    WORKOUT_MINUTES("workout_minutes", "Workout minutes", "min", HealthDataType.EXERCISE),
    WORKOUT_COUNT("workout_count", "Workouts", "workouts", HealthDataType.EXERCISE),
    FLOORS("floors", "Floors", "floors", HealthDataType.FLOORS),
    SLEEP_MINUTES("sleep_minutes", "Sleep", "h", HealthDataType.SLEEP),
    BEDTIME("bedtime", "Bedtime", "", HealthDataType.SLEEP),
    WAKE_TIME("wake_time", "Wake time", "", HealthDataType.SLEEP),
    MINDFUL_MINUTES("mindful_minutes", "Mindful minutes", "min", HealthDataType.MINDFULNESS),
    ;

    companion object {
        fun of(id: String): ChallengeMetric? = entries.firstOrNull { it.id == id }

        /** Metrics a custom challenge can pick directly (clock times only make sense in rules). */
        val PICKABLE = entries.filter { it != BEDTIME && it != WAKE_TIME }
    }
}

enum class ChallengeStatus { ACTIVE, COMPLETED, CANCELLED }

enum class ChallengePhase { UPCOMING, LIVE, ENDED, CANCELLED }

/**
 * A challenge in a group. [spec] is null when this app version can't read it (a newer schema,
 * metric or renderer); the UI then asks the user to update instead of crashing.
 */
data class Challenge(
    val id: String,
    val groupId: String,
    val spec: ChallengeSpec?,
    val status: ChallengeStatus,
    val createdByMemberId: String? = null,
    val createdAt: Instant? = null,
) {
    fun phase(today: LocalDate): ChallengePhase = when {
        status == ChallengeStatus.CANCELLED -> ChallengePhase.CANCELLED
        status == ChallengeStatus.COMPLETED -> ChallengePhase.ENDED
        spec == null -> ChallengePhase.LIVE
        today.isBefore(spec.window.startDate) -> ChallengePhase.UPCOMING
        today.isAfter(spec.window.endDate) -> ChallengePhase.ENDED
        else -> ChallengePhase.LIVE
    }
}

data class MemberScore(
    val memberId: String,
    val score: Double,
    val tieBreak: Double = 0.0,
    val progress: Double? = null,
    val daysHit: Int = 0,
    val daysWithData: Int = 0,
    val longestStreak: Int = 0,
    val finished: Boolean = false,
    val notShared: Boolean = false,
    val flagged: Boolean = false,
    val lastSyncAt: Instant? = null,
)

data class TopRow(
    val memberId: String,
    val score: Double,
    val rank: Int,
    val finished: Boolean = false,
    val notShared: Boolean = false,
)

data class Streak(val current: Int, val best: Int)

data class ChallengeSummary(
    val top: List<TopRow> = emptyList(),
    val participants: Int = 0,
    val scored: Int = 0,
    val finishedCount: Int = 0,
    val total: Double? = null,
    val target: Double? = null,
    val pct: Double? = null,
    val milestonesHit: List<Int> = emptyList(),
    val streak: Streak? = null,
    val refreshedAt: Instant? = null,
    val finalised: Boolean = false,
)

enum class DayStatus { HIT, MISSED, NOT_SHARED }

data class DayRow(
    val memberId: String,
    val status: DayStatus,
    val bedtime: Instant? = null,
    val zoneId: String? = null,
)

data class DayCounts(val date: String, val hit: Int, val missed: Int, val members: Int)

fun DayOfWeek.toSpecDay(): Int = value % 7

fun Int.toDayOfWeek(): DayOfWeek = DayOfWeek.of(if (this == 0) 7 else this)

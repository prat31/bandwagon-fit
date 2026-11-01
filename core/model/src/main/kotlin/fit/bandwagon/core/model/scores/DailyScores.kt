package fit.bandwagon.core.model.scores

import fit.bandwagon.core.model.HealthMath
import fit.bandwagon.core.model.SleepSession
import fit.bandwagon.core.model.SleepStageType
import java.time.Duration
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ScoreKind { SLEEP, MOVE }

/** One part of a score, shown as "points of max" so the number is never a black box. */
data class ScoreContributor(val key: String, val label: String, val points: Double, val max: Double) {
    val fraction: Double get() = if (max <= 0) 0.0 else (points / max).coerceIn(0.0, 1.0)
}

/**
 * A 0–100 score with the contributors behind it. When an input is missing (no sleep stages, no
 * active-minute data…) its contributor is left out and the rest are scaled to 100, so a missing
 * input never counts as a bad night or a lazy day. [partial] tells the UI to say so.
 */
data class DailyScore(val kind: ScoreKind, val value: Int, val contributors: List<ScoreContributor>, val partial: Boolean) {
    val rating: ScoreRating get() = ScoreRating.of(value)
}

/** Bands shared by both scores: 90+ excellent, 80+ good, 60+ fair, below that low. */
enum class ScoreRating(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    LOW("Low"),
    ;

    companion object {
        fun of(value: Int): ScoreRating = when {
            value >= 90 -> EXCELLENT
            value >= 80 -> GOOD
            value >= 60 -> FAIR
            else -> LOW
        }
    }
}

private fun ramp(value: Double, zeroAt: Double, fullAt: Double): Double =
    if (fullAt == zeroAt) (if (value >= fullAt) 1.0 else 0.0)
    else ((value - zeroAt) / (fullAt - zeroAt)).coerceIn(0.0, 1.0)

private fun scaled(kind: ScoreKind, parts: List<ScoreContributor>, possible: Double): DailyScore? {
    val max = parts.sumOf { it.max }
    if (parts.isEmpty() || max <= 0) return null
    val value = (parts.sumOf { it.points } / max * 100).roundToInt().coerceIn(0, 100)
    return DailyScore(kind, value, parts, partial = max < possible)
}

/**
 * Sleep score, 0–100. Modelled on how widely used sleep trackers build theirs: most of the weight
 * on time asleep against your need, then sleep quality (deep and REM), restfulness and timing.
 *
 * | Contributor | Points | Full marks at |
 * |---|---|---|
 * | Time asleep | 50 | your sleep goal (linear below it) |
 * | Deep + REM  | 25 | deep ≥ 16% and REM ≥ 21% of time asleep (adult norms 13–23% and 20–25%) |
 * | Restfulness | 15 | efficiency (asleep ÷ in bed) ≥ 90%, nothing at 70% or less (85% is the clinical "good") |
 * | Timing      | 10 | bedtime within 30 min of your usual (none at 2 h off); with under 3 nights of history, a sleep midpoint between 00:00 and 03:00 |
 *
 * Deep + REM and restfulness need real stages from the source; without them the score is the
 * duration and timing parts scaled to 100 and marked partial.
 */
object SleepScoreCalculator {
    const val DURATION_POINTS = 50.0
    const val STAGES_POINTS = 25.0
    const val RESTFUL_POINTS = 15.0
    const val TIMING_POINTS = 10.0
    private const val DEEP_TARGET = 0.16
    private const val REM_TARGET = 0.21
    private const val MIN_HISTORY = 3

    fun score(night: SleepSession, goalMinutes: Int, previousNights: List<SleepSession>, zone: ZoneId): DailyScore? {
        if (night.asleepMinutes <= 0 || goalMinutes <= 0) return null
        val parts = mutableListOf<ScoreContributor>()
        parts += ScoreContributor("duration", "Time asleep", DURATION_POINTS * ramp(night.asleepMinutes.toDouble(), 0.0, goalMinutes.toDouble()), DURATION_POINTS)

        if (night.hasStages && night.asleepMinutes > 0) {
            val asleep = night.asleepMinutes.toDouble()
            val deep = night.stageMinutes(SleepStageType.DEEP) / asleep
            val rem = night.stageMinutes(SleepStageType.REM) / asleep
            val points = STAGES_POINTS / 2 * ramp(deep, 0.0, DEEP_TARGET) + STAGES_POINTS / 2 * ramp(rem, 0.0, REM_TARGET)
            parts += ScoreContributor("stages", "Deep and REM", points, STAGES_POINTS)
        }
        HealthMath.sleepEfficiency(night)?.let { efficiency ->
            parts += ScoreContributor("restfulness", "Restfulness", RESTFUL_POINTS * ramp(efficiency, 0.70, 0.90), RESTFUL_POINTS)
        }
        parts += ScoreContributor("timing", "Timing", TIMING_POINTS * timingFraction(night, previousNights, zone), TIMING_POINTS)

        return scaled(ScoreKind.SLEEP, parts, DURATION_POINTS + STAGES_POINTS + RESTFUL_POINTS + TIMING_POINTS)
    }

    private fun timingFraction(night: SleepSession, previous: List<SleepSession>, zone: ZoneId): Double {
        val history = previous.filter { it.end.isBefore(night.end) }.takeLast(14)
        if (history.size >= MIN_HISTORY) {
            val usual = HealthMath.meanClockMinutes(history.map { it.start }, zone) ?: return 1.0
            val bed = night.start.atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
            return 1 - ramp(circularDistance(bed, usual).toDouble(), 30.0, 120.0)
        }
        val midpoint = night.start.plus(Duration.between(night.start, night.end).dividedBy(2)).atZone(zone).toLocalTime()
        val minutes = midpoint.hour * 60 + midpoint.minute
        // Minutes outside 00:00–03:00, measured around the clock; full marks inside, none 3 h out.
        val outside = when {
            minutes <= 180 -> 0
            minutes >= 720 -> 1440 - minutes
            else -> minutes - 180
        }
        return 1 - ramp(outside.toDouble(), 0.0, 180.0)
    }

    private fun circularDistance(a: Int, b: Int): Int {
        val d = abs(a - b) % 1440
        return if (d > 720) 1440 - d else d
    }
}

/** One day of activity, as the move score reads it. Nulls mean "not shared or no data". */
data class ActivityDay(val steps: Double?, val activeMinutes: Double?)

/**
 * Move score, 0–100, for one day. Modelled on how activity scores in popular trackers work: credit
 * for today's steps and active minutes, plus a weekly view so one rest day isn't punished and a
 * habit is rewarded.
 *
 * | Contributor | Points | Full marks at |
 * |---|---|---|
 * | Steps today          | 35 | your daily step goal |
 * | Active minutes today | 25 | a seventh of your weekly active-minute goal (WHO: 150 min a week, so ~21 min) |
 * | Weekly activity      | 20 | your weekly active-minute goal over the last 7 days, today included |
 * | Daily goals met      | 10 | step goal hit on 6 of the previous 7 days |
 * | Workout frequency    | 10 | 3 days with 20+ active minutes in the previous 7 days |
 *
 * Contributors whose data isn't shared are left out and the rest scaled to 100.
 */
object MoveScoreCalculator {
    const val STEPS_POINTS = 35.0
    const val ACTIVE_POINTS = 25.0
    const val WEEKLY_POINTS = 20.0
    const val GOALS_MET_POINTS = 10.0
    const val FREQUENCY_POINTS = 10.0
    const val SESSION_MINUTES = 20.0

    /** [previousDays] are the 7 days before today, oldest first; fewer is fine. */
    fun score(today: ActivityDay, previousDays: List<ActivityDay>, stepGoal: Int, weeklyActiveGoal: Int): DailyScore? {
        if (today.steps == null && today.activeMinutes == null) return null
        val week = previousDays.takeLast(7)
        val parts = mutableListOf<ScoreContributor>()
        today.steps?.let { steps ->
            parts += ScoreContributor("steps", "Steps today", STEPS_POINTS * ramp(steps, 0.0, stepGoal.toDouble()), STEPS_POINTS)
            val days = week.mapNotNull { it.steps }
            if (days.isNotEmpty()) {
                val hit = days.count { it >= stepGoal }
                parts += ScoreContributor("goals", "Daily goals met", GOALS_MET_POINTS * ramp(hit.toDouble(), 0.0, 6.0), GOALS_MET_POINTS)
            }
        }
        today.activeMinutes?.let { active ->
            parts += ScoreContributor("active", "Active minutes", ACTIVE_POINTS * ramp(active, 0.0, weeklyActiveGoal / 7.0), ACTIVE_POINTS)
            val recent = week.takeLast(6).mapNotNull { it.activeMinutes }
            val weekly = recent.sum() + active
            parts += ScoreContributor("weekly", "Weekly activity", WEEKLY_POINTS * ramp(weekly, 0.0, weeklyActiveGoal.toDouble()), WEEKLY_POINTS)
            val sessions = week.count { (it.activeMinutes ?: 0.0) >= SESSION_MINUTES }
            parts += ScoreContributor("frequency", "Workout frequency", FREQUENCY_POINTS * ramp(sessions.toDouble(), 0.0, 3.0), FREQUENCY_POINTS)
        }
        return scaled(ScoreKind.MOVE, parts, STEPS_POINTS + ACTIVE_POINTS + WEEKLY_POINTS + GOALS_MET_POINTS + FREQUENCY_POINTS)
    }
}

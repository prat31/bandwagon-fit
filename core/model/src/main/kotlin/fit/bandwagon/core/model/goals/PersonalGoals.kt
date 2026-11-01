package fit.bandwagon.core.model.goals

import fit.bandwagon.core.model.Goals
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.LocalDateRange
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class GoalPeriod(val label: String, val noun: String) {
    DAILY("Daily", "day"),
    WEEKLY("Weekly", "week"),
    MONTHLY("Monthly", "month"),
    ;

    /** The period containing [date]: the day, the Monday–Sunday week, or the calendar month. */
    fun window(date: LocalDate): LocalDateRange = when (this) {
        DAILY -> LocalDateRange(date, date)
        WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { LocalDateRange(it, it.plusDays(6)) }
        MONTHLY -> LocalDateRange(date.withDayOfMonth(1), date.with(TemporalAdjusters.lastDayOfMonth()))
    }
}

/** How a metric's days add up over a week or month. */
enum class GoalAggregation {
    /** Steps, distance, minutes…: the period total. */
    TOTAL,

    /** Sleep and scores: the average over the days with data. */
    AVERAGE,
}

/**
 * What a personal goal can measure. Values are in the units `core:health` stores (meters,
 * minutes, ml); the UI converts for display. [dataType] is what must be shared to track it.
 */
enum class GoalMetric(
    val label: String,
    val aggregation: GoalAggregation,
    val dataType: HealthDataType?,
    val defaults: Map<GoalPeriod, Double>,
    val min: Double,
    val max: Double,
) {
    STEPS("Steps", GoalAggregation.TOTAL, HealthDataType.STEPS, mapOf(GoalPeriod.DAILY to 8_000.0, GoalPeriod.WEEKLY to 56_000.0, GoalPeriod.MONTHLY to 240_000.0), 100.0, 5_000_000.0),
    DISTANCE("Distance", GoalAggregation.TOTAL, HealthDataType.DISTANCE, mapOf(GoalPeriod.DAILY to 5_000.0, GoalPeriod.WEEKLY to 35_000.0, GoalPeriod.MONTHLY to 150_000.0), 100.0, 5_000_000.0),
    ACTIVE_MINUTES("Active minutes", GoalAggregation.TOTAL, null, mapOf(GoalPeriod.DAILY to 30.0, GoalPeriod.WEEKLY to 150.0, GoalPeriod.MONTHLY to 600.0), 5.0, 30_000.0),
    WORKOUTS("Workouts", GoalAggregation.TOTAL, HealthDataType.EXERCISE, mapOf(GoalPeriod.DAILY to 1.0, GoalPeriod.WEEKLY to 3.0, GoalPeriod.MONTHLY to 12.0), 1.0, 200.0),
    ACTIVE_CALORIES("Active calories", GoalAggregation.TOTAL, HealthDataType.ACTIVE_CALORIES, mapOf(GoalPeriod.DAILY to 400.0, GoalPeriod.WEEKLY to 2_800.0, GoalPeriod.MONTHLY to 12_000.0), 50.0, 500_000.0),
    FLOORS("Floors", GoalAggregation.TOTAL, HealthDataType.FLOORS, mapOf(GoalPeriod.DAILY to 10.0, GoalPeriod.WEEKLY to 70.0, GoalPeriod.MONTHLY to 300.0), 1.0, 50_000.0),
    SLEEP("Sleep", GoalAggregation.AVERAGE, HealthDataType.SLEEP, mapOf(GoalPeriod.DAILY to 450.0, GoalPeriod.WEEKLY to 450.0, GoalPeriod.MONTHLY to 450.0), 180.0, 720.0),
    SLEEP_SCORE("Sleep score", GoalAggregation.AVERAGE, HealthDataType.SLEEP, mapOf(GoalPeriod.DAILY to 80.0, GoalPeriod.WEEKLY to 80.0, GoalPeriod.MONTHLY to 80.0), 10.0, 100.0),
    MOVE_SCORE("Move score", GoalAggregation.AVERAGE, null, mapOf(GoalPeriod.DAILY to 70.0, GoalPeriod.WEEKLY to 70.0, GoalPeriod.MONTHLY to 70.0), 10.0, 100.0),
    HYDRATION("Water", GoalAggregation.TOTAL, HealthDataType.HYDRATION, mapOf(GoalPeriod.DAILY to 2_000.0, GoalPeriod.WEEKLY to 14_000.0, GoalPeriod.MONTHLY to 60_000.0), 100.0, 1_000_000.0),
    MINDFULNESS("Mindful minutes", GoalAggregation.TOTAL, HealthDataType.MINDFULNESS, mapOf(GoalPeriod.DAILY to 10.0, GoalPeriod.WEEKLY to 70.0, GoalPeriod.MONTHLY to 300.0), 1.0, 30_000.0),
    ;

    fun defaultTarget(period: GoalPeriod): Double = defaults.getValue(period)

    /** The usual daily value, for the "on N days" style. */
    val dailyDefault: Double get() = defaultTarget(GoalPeriod.DAILY)
}

/**
 * A goal the user set for themselves. [dailyThreshold] switches a weekly or monthly goal to the
 * "days" style: [target] is then the number of days whose value reaches the threshold
 * ("10,000 steps on 5 days a week"). Goals are personal and stay on this phone.
 */
data class PersonalGoal(
    val id: String,
    val metric: GoalMetric,
    val period: GoalPeriod,
    val target: Double,
    val dailyThreshold: Double? = null,
    val createdOn: LocalDate,
) {
    val countsDays: Boolean get() = dailyThreshold != null && period != GoalPeriod.DAILY
}

enum class GoalStatus {
    /** Reached; for average goals only once the period has closed. */
    DONE,
    ON_TRACK,
    BEHIND,

    /** A days-style goal that can no longer be reached this period. */
    OUT_OF_REACH,
    NO_DATA,
}

/** Live progress for the current period. [expected] is where a steady pace would be by now. */
data class GoalProgress(
    val window: LocalDateRange,
    val achieved: Double?,
    val target: Double,
    val expected: Double?,
    val status: GoalStatus,
    val daysLeft: Int,
) {
    val fraction: Float get() = if (target <= 0 || achieved == null) 0f else (achieved / target).toFloat().coerceIn(0f, 1f)
    val remaining: Double? get() = achieved?.let { (target - it).coerceAtLeast(0.0) }
}

enum class OutcomeResult { HIT, MISSED, NO_DATA }

/**
 * The recorded result of one closed period. [target] is a snapshot, so editing a goal never
 * rewrites history. [finalized] becomes true once the settle window has passed and late-arriving
 * data can no longer change it.
 */
data class GoalOutcome(
    val goalId: String,
    val window: LocalDateRange,
    val target: Double,
    val dailyThreshold: Double?,
    val achieved: Double?,
    val result: OutcomeResult,
    val finalized: Boolean,
)

object GoalEngine {
    /**
     * Health Connect data can arrive late (a watch syncing a day later), so a closed period is
     * recomputed for this many days after it ends before its outcome is frozen.
     */
    const val SETTLE_DAYS = 3L

    /** How far back unrecorded periods are reconciled: about two months of daily data. */
    const val LOOKBACK_DAYS = 62L

    /** Achieved value for [goal] over [window], counting only days up to [today]. */
    fun achieved(goal: PersonalGoal, window: LocalDateRange, values: Map<LocalDate, Double>, today: LocalDate, threshold: Double? = goal.dailyThreshold): Double? {
        val days = generateSequence(window.start) { it.plusDays(1) }.takeWhile { !it.isAfter(window.endInclusive) && !it.isAfter(today) }
        val present = days.mapNotNull { values[it] }.toList()
        if (present.isEmpty()) return null
        if (threshold != null && goal.period != GoalPeriod.DAILY) return present.count { it >= threshold }.toDouble()
        return when (goal.metric.aggregation) {
            GoalAggregation.TOTAL -> present.sum()
            GoalAggregation.AVERAGE -> present.average()
        }
    }

    /**
     * Progress in the period containing [today]. [dayFraction] is how much of today has gone
     * (0–1), so a pace for today isn't expected at 7 am.
     */
    fun progress(goal: PersonalGoal, values: Map<LocalDate, Double>, today: LocalDate, dayFraction: Double): GoalProgress {
        val window = goal.period.window(today)
        val achieved = achieved(goal, window, values, today)
        val totalDays = window.days.toDouble()
        val elapsedDays = ChronoUnit.DAYS.between(window.start, today) + dayFraction.coerceIn(0.0, 1.0)
        val daysLeft = (ChronoUnit.DAYS.between(today, window.endInclusive) + 1).toInt()
        val target = goal.target
        if (achieved == null) return GoalProgress(window, null, target, null, GoalStatus.NO_DATA, daysLeft)

        if (goal.countsDays) {
            val status = when {
                achieved >= target -> GoalStatus.DONE
                // Today can still count, so it is one of the days left.
                achieved + daysLeft < target -> GoalStatus.OUT_OF_REACH
                achieved >= target * elapsedDays / totalDays -> GoalStatus.ON_TRACK
                else -> GoalStatus.BEHIND
            }
            return GoalProgress(window, achieved, target, target * elapsedDays / totalDays, status, daysLeft)
        }
        return when (goal.metric.aggregation) {
            GoalAggregation.TOTAL -> {
                val expected = target * elapsedDays / totalDays
                val status = when {
                    achieved >= target -> GoalStatus.DONE
                    // A little slack so "on track" doesn't flicker for a few steps.
                    achieved >= expected * 0.9 -> GoalStatus.ON_TRACK
                    else -> GoalStatus.BEHIND
                }
                GoalProgress(window, achieved, target, expected, status, daysLeft)
            }
            GoalAggregation.AVERAGE -> {
                val status = if (achieved >= target) {
                    if (goal.period == GoalPeriod.DAILY) GoalStatus.DONE else GoalStatus.ON_TRACK
                } else GoalStatus.BEHIND
                GoalProgress(window, achieved, target, target, status, daysLeft)
            }
        }
    }

    private fun result(achieved: Double?, target: Double): OutcomeResult = when {
        achieved == null -> OutcomeResult.NO_DATA
        achieved >= target -> OutcomeResult.HIT
        else -> OutcomeResult.MISSED
    }

    /**
     * Brings the recorded history of [goal] up to date: every closed period since the goal was
     * created (at most [LOOKBACK_DAYS] back) gets an outcome, and outcomes still inside the settle
     * window are recomputed from the latest data. Finalized outcomes are never touched. Returns
     * only the outcomes to write.
     */
    fun reconcile(goal: PersonalGoal, existing: List<GoalOutcome>, values: Map<LocalDate, Double>, today: LocalDate): List<GoalOutcome> {
        val byStart = existing.associateBy { it.window.start }
        val earliest = maxOf(goal.createdOn, today.minusDays(LOOKBACK_DAYS))
        val current = goal.period.window(today)
        val out = mutableListOf<GoalOutcome>()
        var window = goal.period.window(earliest)
        while (window.start.isBefore(current.start)) {
            val prior = byStart[window.start]
            if (prior?.finalized != true) {
                val target = prior?.target ?: goal.target
                val threshold = if (prior != null) prior.dailyThreshold else goal.dailyThreshold
                val achieved = achieved(goal, window, values, today, threshold)
                val outcome = GoalOutcome(
                    goalId = goal.id,
                    window = window,
                    target = target,
                    dailyThreshold = threshold,
                    achieved = achieved,
                    result = result(achieved, target),
                    finalized = ChronoUnit.DAYS.between(window.endInclusive, today) > SETTLE_DAYS,
                )
                if (outcome != prior) out += outcome
            }
            window = goal.period.window(window.endInclusive.plusDays(1))
        }
        return out
    }

    /**
     * Consecutive hit periods, newest first, counting the current period when it is already done.
     * A period with no data ends the streak like a miss.
     */
    fun streak(outcomes: List<GoalOutcome>, currentDone: Boolean): Int {
        val closed = outcomes.sortedByDescending { it.window.start }.takeWhile { it.result == OutcomeResult.HIT }.size
        return closed + if (currentDone) 1 else 0
    }

    /**
     * The four targets the rest of the app reads (Health tab goal lines, insights, the hero card)
     * follow the user's goals: a daily steps goal sets the step target, and so on. Metrics without
     * a matching goal keep the defaults.
     */
    fun coreTargets(goals: List<PersonalGoal>): Goals {
        fun find(metric: GoalMetric, vararg periods: GoalPeriod) =
            periods.firstNotNullOfOrNull { p -> goals.firstOrNull { it.metric == metric && it.period == p && !it.countsDays } }
        val defaults = Goals()
        val steps = find(GoalMetric.STEPS, GoalPeriod.DAILY)?.target
            ?: goals.firstOrNull { it.metric == GoalMetric.STEPS && it.countsDays }?.dailyThreshold
        val active = find(GoalMetric.ACTIVE_MINUTES, GoalPeriod.WEEKLY)?.target
            ?: find(GoalMetric.ACTIVE_MINUTES, GoalPeriod.DAILY)?.target?.times(7)
        val sleep = find(GoalMetric.SLEEP, GoalPeriod.DAILY, GoalPeriod.WEEKLY, GoalPeriod.MONTHLY)?.target
            ?: goals.firstOrNull { it.metric == GoalMetric.SLEEP && it.countsDays }?.dailyThreshold
        val water = find(GoalMetric.HYDRATION, GoalPeriod.DAILY)?.target
        return Goals(
            steps = steps?.toInt() ?: defaults.steps,
            activeMinutesWeekly = active?.toInt() ?: defaults.activeMinutesWeekly,
            sleepMinutes = sleep?.toInt() ?: defaults.sleepMinutes,
            hydrationMl = water?.toInt() ?: defaults.hydrationMl,
        )
    }

    /** Goals equivalent to targets set before goals moved to Home, so nothing set earlier is lost. */
    fun fromCoreTargets(targets: Goals, today: LocalDate, newId: () -> String): List<PersonalGoal> {
        val defaults = Goals()
        if (targets == defaults) return emptyList()
        return listOf(
            PersonalGoal(newId(), GoalMetric.STEPS, GoalPeriod.DAILY, targets.steps.toDouble(), createdOn = today),
            PersonalGoal(newId(), GoalMetric.ACTIVE_MINUTES, GoalPeriod.WEEKLY, targets.activeMinutesWeekly.toDouble(), createdOn = today),
            PersonalGoal(newId(), GoalMetric.SLEEP, GoalPeriod.DAILY, targets.sleepMinutes.toDouble(), createdOn = today),
        ) + if (targets.hydrationMl != defaults.hydrationMl) {
            listOf(PersonalGoal(newId(), GoalMetric.HYDRATION, GoalPeriod.DAILY, targets.hydrationMl.toDouble(), createdOn = today))
        } else emptyList()
    }
}

package fit.bandwagon.feature.home

import fit.bandwagon.core.data.repository.ScoreRepository
import fit.bandwagon.core.health.store.HealthRepository
import fit.bandwagon.core.model.HealthCapabilities
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.LocalDateRange
import fit.bandwagon.core.model.SleepSelection
import fit.bandwagon.core.model.goals.GoalEngine
import fit.bandwagon.core.model.goals.GoalMetric
import fit.bandwagon.core.model.goals.GoalOutcome
import fit.bandwagon.core.model.goals.GoalPeriod
import fit.bandwagon.core.model.goals.GoalProgress
import fit.bandwagon.core.model.goals.GoalStatus
import fit.bandwagon.core.model.goals.PersonalGoal
import fit.bandwagon.core.model.scores.ScoreKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** One goal on Home: live progress, the streak and the last few closed periods. */
data class GoalCard(
    val goal: PersonalGoal,
    val progress: GoalProgress,
    val streak: Int,
    val recent: List<GoalOutcome>,
    val shared: Boolean,
)

/** Which health metric feeds a goal, given the user's settings and what is shared. */
data class GoalSources(val stepsMetric: HealthMetric, val activeMetric: HealthMetric)

/** Reads the per-day values each goal metric needs, from Health Connect or the recorded scores. */
class GoalValues @Inject constructor(
    private val health: HealthRepository,
    private val scores: ScoreRepository,
) {
    /** Days of data to read so every period [GoalEngine.reconcile] may revisit is covered. */
    fun daysNeeded(goals: List<PersonalGoal>): Long = when {
        goals.any { it.period == GoalPeriod.MONTHLY } -> GoalEngine.LOOKBACK_DAYS + 31
        goals.any { it.period == GoalPeriod.WEEKLY } -> GoalEngine.LOOKBACK_DAYS + 7
        else -> GoalEngine.LOOKBACK_DAYS + 1
    }

    fun shared(metric: GoalMetric, caps: HealthCapabilities, sources: GoalSources): Boolean = when (metric) {
        GoalMetric.STEPS -> caps.granted(sources.stepsMetric.dataType)
        GoalMetric.ACTIVE_MINUTES -> caps.granted(sources.activeMetric.dataType)
        GoalMetric.MOVE_SCORE -> caps.granted(sources.stepsMetric.dataType) || caps.granted(sources.activeMetric.dataType)
        else -> metric.dataType?.let(caps::granted) ?: true
    }

    suspend fun load(metric: GoalMetric, days: Long, today: LocalDate, zone: ZoneId, sources: GoalSources, force: Boolean): Map<LocalDate, Double> {
        val range = LocalDateRange.lastDays(today, days)
        return runCatching {
            when (metric) {
                GoalMetric.STEPS -> daily(sources.stepsMetric, days, force)
                GoalMetric.ACTIVE_MINUTES -> daily(sources.activeMetric, days, force)
                GoalMetric.DISTANCE -> daily(HealthMetric.DISTANCE, days, force)
                GoalMetric.ACTIVE_CALORIES -> daily(HealthMetric.ACTIVE_CALORIES, days, force)
                GoalMetric.FLOORS -> daily(HealthMetric.FLOORS, days, force)
                GoalMetric.HYDRATION -> daily(HealthMetric.HYDRATION, days, force)
                GoalMetric.MINDFULNESS -> daily(HealthMetric.MINDFULNESS, days, force)
                GoalMetric.WORKOUTS -> {
                    // No workout on a day is a real zero, not missing data.
                    val counts = health.workouts(range).filter { it.qualifies }.groupingBy { it.start.atZone(zone).toLocalDate() }.eachCount()
                    generateSequence(range.start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
                        .associateWith { (counts[it] ?: 0).toDouble() }
                }
                GoalMetric.SLEEP -> health.sleep(range).groupBy { it.end.atZone(zone).toLocalDate() }
                    .mapNotNull { (date, sessions) -> SleepSelection.mainSession(sessions)?.let { date to it.asleepMinutes.toDouble() } }.toMap()
                GoalMetric.SLEEP_SCORE -> scores.range(ScoreKind.SLEEP, range.start, today).mapValues { it.value.value.toDouble() }
                GoalMetric.MOVE_SCORE -> scores.range(ScoreKind.MOVE, range.start, today).mapValues { it.value.value.toDouble() }
            }
        }.getOrDefault(emptyMap())
    }

    private suspend fun daily(metric: HealthMetric, days: Long, force: Boolean): Map<LocalDate, Double> =
        health.daily(metric, days, force).mapNotNull { d -> d.value?.let { d.date to it } }.toMap()

    companion object {
        /** Share of the waking day (07:00–22:00) gone, so a daily pace isn't expected overnight. */
        fun dayFraction(now: LocalTime): Double = ((now.toSecondOfDay() / 60.0 - 7 * 60) / (15 * 60)).coerceIn(0.0, 1.0)

        fun isDone(progress: GoalProgress) = progress.status == GoalStatus.DONE

        /** Source data types for a goal, for the share prompt. */
        fun typesFor(metric: GoalMetric, sources: GoalSources): Set<HealthDataType> = when (metric) {
            GoalMetric.STEPS -> setOf(sources.stepsMetric.dataType)
            GoalMetric.ACTIVE_MINUTES -> setOf(sources.activeMetric.dataType)
            GoalMetric.MOVE_SCORE -> setOf(sources.stepsMetric.dataType, sources.activeMetric.dataType)
            else -> setOfNotNull(metric.dataType)
        }
    }
}

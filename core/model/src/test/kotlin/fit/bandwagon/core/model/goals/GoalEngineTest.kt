package fit.bandwagon.core.model.goals

import com.google.common.truth.Truth.assertThat
import fit.bandwagon.core.model.Goals
import org.junit.Test
import java.time.LocalDate

class GoalEngineTest {
    // Sunday 1 Nov 2026; the week is Mon 26 Oct – Sun 1 Nov.
    private val today = LocalDate.of(2026, 11, 1)

    private fun goal(metric: GoalMetric, period: GoalPeriod, target: Double, threshold: Double? = null, created: LocalDate = today.minusDays(40)) =
        PersonalGoal("g", metric, period, target, threshold, created)

    private fun days(from: LocalDate, vararg values: Double?): Map<LocalDate, Double> =
        values.withIndex().mapNotNull { (i, v) -> v?.let { from.plusDays(i.toLong()) to it } }.toMap()

    @Test
    fun windowsAreDayMondayWeekAndCalendarMonth() {
        assertThat(GoalPeriod.WEEKLY.window(today).start).isEqualTo(LocalDate.of(2026, 10, 26))
        assertThat(GoalPeriod.WEEKLY.window(today).endInclusive).isEqualTo(today)
        assertThat(GoalPeriod.MONTHLY.window(LocalDate.of(2026, 2, 14)).endInclusive).isEqualTo(LocalDate.of(2026, 2, 28))
        assertThat(GoalPeriod.DAILY.window(today).days).isEqualTo(1)
    }

    @Test
    fun weeklyTotalIsOnTrackWhenAtPace() {
        val g = goal(GoalMetric.ACTIVE_MINUTES, GoalPeriod.WEEKLY, 140.0)
        // 6 full days of 20 min = 120; by Sunday noon pace is 130; 120 ≥ 90% of 130.
        val p = GoalEngine.progress(g, days(LocalDate.of(2026, 10, 26), 20.0, 20.0, 20.0, 20.0, 20.0, 20.0, null), today, 0.5)
        assertThat(p.achieved).isEqualTo(120.0)
        assertThat(p.status).isEqualTo(GoalStatus.ON_TRACK)
        assertThat(p.remaining).isEqualTo(20.0)
        assertThat(p.daysLeft).isEqualTo(1)
    }

    @Test
    fun totalIsDoneOnceReached() {
        val g = goal(GoalMetric.STEPS, GoalPeriod.DAILY, 8_000.0)
        assertThat(GoalEngine.progress(g, days(today, 8_100.0), today, 0.3).status).isEqualTo(GoalStatus.DONE)
        assertThat(GoalEngine.progress(g, emptyMap(), today, 0.3).status).isEqualTo(GoalStatus.NO_DATA)
    }

    @Test
    fun daysStyleCountsQualifyingDaysAndKnowsWhenItIsOutOfReach() {
        val g = goal(GoalMetric.STEPS, GoalPeriod.WEEKLY, 5.0, threshold = 10_000.0)
        val week = days(LocalDate.of(2026, 10, 26), 12_000.0, 3_000.0, 11_000.0, 2_000.0, 4_000.0, 5_000.0, 1_000.0)
        val p = GoalEngine.progress(g, week, today, 0.5)
        assertThat(p.achieved).isEqualTo(2.0)
        // 2 hit + 1 day left (today) < 5.
        assertThat(p.status).isEqualTo(GoalStatus.OUT_OF_REACH)
    }

    @Test
    fun averageGoalsAreOnlyDoneWhenThePeriodCloses() {
        val g = goal(GoalMetric.SLEEP, GoalPeriod.WEEKLY, 420.0)
        val p = GoalEngine.progress(g, days(LocalDate.of(2026, 10, 26), 450.0, 430.0), today, 0.5)
        assertThat(p.achieved).isEqualTo(440.0)
        assertThat(p.status).isEqualTo(GoalStatus.ON_TRACK)
    }

    @Test
    fun reconcileRecordsClosedPeriodsAndFreezesThemAfterTheSettleWindow() {
        val g = goal(GoalMetric.STEPS, GoalPeriod.DAILY, 8_000.0, created = today.minusDays(5))
        val values = days(today.minusDays(5), 9_000.0, 7_000.0, null, 8_000.0, 10_000.0, 1_000.0)
        val out = GoalEngine.reconcile(g, emptyList(), values, today)
        assertThat(out.map { it.result }).containsExactly(
            OutcomeResult.HIT, OutcomeResult.MISSED, OutcomeResult.NO_DATA, OutcomeResult.HIT, OutcomeResult.HIT,
        ).inOrder()
        // 27 Oct ended 5 days ago (> 3): frozen. 31 Oct ended yesterday: still settling.
        assertThat(out.first().finalized).isTrue()
        assertThat(out.last().finalized).isFalse()
    }

    @Test
    fun reconcileUpdatesSettlingOutcomesButNeverFinalizedOnesAndKeepsTheTargetSnapshot() {
        val g = goal(GoalMetric.STEPS, GoalPeriod.DAILY, 12_000.0, created = today.minusDays(5))
        val frozen = GoalOutcome("g", GoalPeriod.DAILY.window(today.minusDays(5)), 8_000.0, null, 7_000.0, OutcomeResult.MISSED, finalized = true)
        val settling = GoalOutcome("g", GoalPeriod.DAILY.window(today.minusDays(1)), 8_000.0, null, 7_000.0, OutcomeResult.MISSED, finalized = false)
        // Late data: yesterday was really 9,000.
        val values = days(today.minusDays(5), 9_000.0, null, null, null, 9_000.0)
        val out = GoalEngine.reconcile(g, listOf(frozen, settling), values, today)
        assertThat(out.none { it.window == frozen.window }).isTrue()
        val updated = out.single { it.window == settling.window }
        assertThat(updated.target).isEqualTo(8_000.0)
        assertThat(updated.result).isEqualTo(OutcomeResult.HIT)
    }

    @Test
    fun reconcileSkipsUnchangedOutcomes() {
        val g = goal(GoalMetric.STEPS, GoalPeriod.DAILY, 8_000.0, created = today.minusDays(1))
        val values = days(today.minusDays(1), 9_000.0)
        val first = GoalEngine.reconcile(g, emptyList(), values, today)
        assertThat(GoalEngine.reconcile(g, first, values, today)).isEmpty()
    }

    @Test
    fun streakCountsConsecutiveHitsAndTheCurrentPeriodWhenDone() {
        fun o(daysAgo: Long, r: OutcomeResult) = GoalOutcome("g", GoalPeriod.DAILY.window(today.minusDays(daysAgo)), 1.0, null, 1.0, r, true)
        val history = listOf(o(4, OutcomeResult.HIT), o(3, OutcomeResult.MISSED), o(2, OutcomeResult.HIT), o(1, OutcomeResult.HIT))
        assertThat(GoalEngine.streak(history, currentDone = false)).isEqualTo(2)
        assertThat(GoalEngine.streak(history, currentDone = true)).isEqualTo(3)
    }

    @Test
    fun coreTargetsFollowMatchingGoalsAndFallBackToDefaults() {
        val goals = listOf(
            goal(GoalMetric.STEPS, GoalPeriod.DAILY, 10_000.0),
            goal(GoalMetric.ACTIVE_MINUTES, GoalPeriod.DAILY, 30.0),
            goal(GoalMetric.SLEEP, GoalPeriod.WEEKLY, 480.0),
        )
        assertThat(GoalEngine.coreTargets(goals)).isEqualTo(Goals(steps = 10_000, activeMinutesWeekly = 210, sleepMinutes = 480))
        assertThat(GoalEngine.coreTargets(emptyList())).isEqualTo(Goals())
    }

    @Test
    fun oldTargetsBecomeGoalsOnlyWhenCustomised() {
        var n = 0
        assertThat(GoalEngine.fromCoreTargets(Goals(), today) { "${n++}" }).isEmpty()
        val seeded = GoalEngine.fromCoreTargets(Goals(steps = 10_000), today) { "${n++}" }
        assertThat(seeded.map { it.metric }).containsExactly(GoalMetric.STEPS, GoalMetric.ACTIVE_MINUTES, GoalMetric.SLEEP)
        assertThat(GoalEngine.coreTargets(seeded)).isEqualTo(Goals(steps = 10_000))
    }
}

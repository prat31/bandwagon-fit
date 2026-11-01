package fit.bandwagon.core.model.insights

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class InsightEngineTest {
    private val utc = ZoneId.of("UTC")
    private val sunday = LocalDate.of(2026, 11, 1)
    private val base = InsightInputs(today = sunday, zone = utc)

    @Test
    fun activityChangeNeedsFifteenPercentAndEnoughDays() {
        val up = base.copy(steps = List(28) { 8000.0 } + List(7) { 9500.0 })
        assertThat(InsightEngine.activityChange(up)?.title).isEqualTo("19% more steps than usual")
        val small = base.copy(steps = List(28) { 8000.0 } + List(7) { 9000.0 })
        assertThat(InsightEngine.activityChange(small)).isNull()
        assertThat(InsightEngine.activityChange(base.copy(steps = List(20) { 8000.0 }))).isNull()
    }

    @Test
    fun missingDaysAreNeverTreatedAsZero() {
        val withGaps = base.copy(steps = List(28) { 8000.0 } + listOf(null, null, null, 8100.0, 8000.0, 7900.0, 8050.0))
        assertThat(InsightEngine.activityChange(withGaps)).isNull()
    }

    @Test
    fun goalStreakIgnoresUnfinishedToday() {
        val streak = base.copy(steps = listOf(5000.0, 9000.0, 9000.0, 9000.0, 3000.0), stepsGoal = 8000)
        assertThat(InsightEngine.goalStreak(streak)?.title).startsWith("3 days")
        val broken = base.copy(steps = listOf(9000.0, 5000.0, 9000.0, 9000.0, 3000.0), stepsGoal = 8000)
        assertThat(InsightEngine.goalStreak(broken)).isNull()
    }

    @Test
    fun personalBestBeatsEveryEarlierDay() {
        val pb = base.copy(steps = List(30) { 8000.0 } + listOf(12000.0))
        assertThat(InsightEngine.personalBest(pb)?.title).isEqualTo("New personal best!")
        assertThat(InsightEngine.personalBest(base.copy(steps = List(31) { 8000.0 }))).isNull()
    }

    @Test
    fun whoGoalReachedOrNearlyThere() {
        // Sunday: the whole week counts.
        val done = base.copy(activeMinutes = List(7) { 25.0 })
        assertThat(InsightEngine.whoProgress(done)?.title).isEqualTo("Weekly active goal reached")
        val friday = base.copy(today = LocalDate.of(2026, 10, 30), activeMinutes = List(5) { 20.0 })
        assertThat(InsightEngine.whoProgress(friday)?.title).isEqualTo("50 active minutes to go")
    }

    @Test
    fun sleepDebtOverThreeHours() {
        val short = base.copy(nights = List(7) { Night(400, Instant.parse("2026-10-25T23:00:00Z")) }, sleepGoalMinutes = 450)
        assertThat(InsightEngine.sleepDebt(short)?.title).isEqualTo("5h 50m of sleep debt")
        val fine = base.copy(nights = List(7) { Night(450, Instant.parse("2026-10-25T23:00:00Z")) })
        assertThat(InsightEngine.sleepDebt(fine)).isNull()
    }

    @Test
    fun bedtimeDriftAcrossMidnight() {
        val early = List(21) { Night(450, Instant.parse("2026-10-01T22:45:00Z").plusSeconds(it * 86_400L)) }
        val late = List(7) { Night(450, Instant.parse("2026-10-23T00:15:00Z").plusSeconds(it * 86_400L)) }
        val drift = InsightEngine.bedtimeDrift(base.copy(nights = early + late))
        assertThat(drift?.title).isEqualTo("Bedtime has drifted 90 min later")
    }

    @Test
    fun restingHeartRateAbovePersonalRangeForThreeDays() {
        val history = List(20) { if (it % 2 == 0) 55.0 else 57.0 }
        val raised = base.copy(restingHeartRate = history + listOf(62.0, 63.0, 61.0))
        assertThat(InsightEngine.restingHeartRateRaised(raised)?.title).isEqualTo("Resting heart rate is up")
        val oneDay = base.copy(restingHeartRate = history + listOf(56.0, 56.0, 63.0))
        assertThat(InsightEngine.restingHeartRateRaised(oneDay)).isNull()
    }

    @Test
    fun challengeRemindersAndRankingOrder() {
        val inputs = base.copy(
            challenges = listOf(
                ChallengeContext("g", "c1", "Step Battle", rank = 2, gapToNext = "300 steps", gapRatio = 0.03, endsToday = false, teamPacePerDay = null),
                ChallengeContext("g", "c2", "Weekend", rank = 4, gapToNext = "5,000 steps", gapRatio = 0.4, endsToday = true, teamPacePerDay = null),
            ),
        )
        val insights = InsightEngine.evaluate(inputs)
        assertThat(insights.map { it.id.substringBefore("-") }).containsExactly("gap", "ends").inOrder()
        assertThat(InsightEngine.evaluate(inputs, dismissed = setOf(insights.first().id)).map { it.id.substringBefore("-") }).containsExactly("ends")
    }
}

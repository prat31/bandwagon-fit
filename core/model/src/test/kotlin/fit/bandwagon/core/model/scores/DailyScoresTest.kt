package fit.bandwagon.core.model.scores

import com.google.common.truth.Truth.assertThat
import fit.bandwagon.core.model.SleepSession
import fit.bandwagon.core.model.SleepStage
import fit.bandwagon.core.model.SleepStageType
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DailyScoresTest {
    private val utc = ZoneId.of("UTC")

    private fun night(bed: String, hours: Long, day: Int = 1, stages: ((Instant) -> List<SleepStage>)? = null): SleepSession {
        val start = Instant.parse("2026-11-%02dT%s:00Z".format(day, bed))
        return SleepSession(start, start.plusSeconds(hours * 3600), "UTC", stages?.invoke(start).orEmpty())
    }

    /** 8 h in bed: 30 min awake, 1h20 deep (17%), 1h45 REM (22%), rest light. */
    private fun stagedNight(bed: String = "23:00", day: Int = 1) = night(bed, 8, day) { s ->
        fun at(min: Long) = s.plusSeconds(min * 60)
        listOf(
            SleepStage(SleepStageType.AWAKE, at(0), at(30)),
            SleepStage(SleepStageType.DEEP, at(30), at(110)),
            SleepStage(SleepStageType.LIGHT, at(110), at(375)),
            SleepStage(SleepStageType.REM, at(375), at(480)),
        )
    }

    @Test
    fun aFullStagedNightOnScheduleScoresAtTheTop() {
        val score = SleepScoreCalculator.score(stagedNight(), goalMinutes = 450, previousNights = emptyList(), zone = utc)!!
        assertThat(score.partial).isFalse()
        assertThat(score.contributors.map { it.key }).containsExactly("duration", "stages", "restfulness", "timing")
        // 7h30 asleep = goal; efficiency 93.75% ≥ 90%; midpoint 03:00 is inside the window.
        assertThat(score.value).isEqualTo(100)
        assertThat(score.rating).isEqualTo(ScoreRating.EXCELLENT)
    }

    @Test
    fun shortSleepLosesDurationPointsInProportion() {
        // 4 h asleep of a 8 h goal, no stages: duration 25/50, timing 10/10 → 35/60.
        val score = SleepScoreCalculator.score(night("01:00", 4), goalMinutes = 480, previousNights = emptyList(), zone = utc)!!
        assertThat(score.partial).isTrue()
        assertThat(score.value).isEqualTo(58)
        assertThat(score.rating).isEqualTo(ScoreRating.LOW)
    }

    @Test
    fun timingComparesBedtimeWithYourUsualOnceThereIsHistory() {
        val usual = (1..5).map { stagedNight("22:30", it) }
        val late = SleepScoreCalculator.score(stagedNight("01:30", 7), 450, usual, utc)!!
        val onTime = SleepScoreCalculator.score(stagedNight("22:45", 7), 450, usual, utc)!!
        assertThat(late.contributors.first { it.key == "timing" }.points).isEqualTo(0.0)
        assertThat(onTime.contributors.first { it.key == "timing" }.points).isEqualTo(10.0)
    }

    @Test
    fun noSleepMeansNoScore() {
        assertThat(SleepScoreCalculator.score(night("23:00", 0), 450, emptyList(), utc)).isNull()
    }

    @Test
    fun moveScoreRewardsTodayAndTheWeek() {
        val week = List(7) { ActivityDay(steps = 9_000.0, activeMinutes = 30.0) }
        val score = MoveScoreCalculator.score(ActivityDay(8_000.0, 22.0), week, stepGoal = 8_000, weeklyActiveGoal = 150)!!
        assertThat(score.value).isEqualTo(100)
        assertThat(score.partial).isFalse()
    }

    @Test
    fun moveScoreScalesWhenActiveMinutesArentShared() {
        // Steps only: 4,000 of 8,000 → 17.5/35, goals met 0 of 6 → 0/10: 17.5/45.
        val score = MoveScoreCalculator.score(ActivityDay(4_000.0, null), List(7) { ActivityDay(5_000.0, null) }, 8_000, 150)!!
        assertThat(score.partial).isTrue()
        assertThat(score.value).isEqualTo(39)
    }

    @Test
    fun moveScoreNeedsSomeData() {
        assertThat(MoveScoreCalculator.score(ActivityDay(null, null), emptyList(), 8_000, 150)).isNull()
    }

    @Test
    fun ratingBands() {
        assertThat(ScoreRating.of(90)).isEqualTo(ScoreRating.EXCELLENT)
        assertThat(ScoreRating.of(89)).isEqualTo(ScoreRating.GOOD)
        assertThat(ScoreRating.of(60)).isEqualTo(ScoreRating.FAIR)
        assertThat(ScoreRating.of(59)).isEqualTo(ScoreRating.LOW)
    }
}

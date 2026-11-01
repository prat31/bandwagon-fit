package fit.bandwagon.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HealthMathTest {
    private val utc = ZoneId.of("UTC")

    private fun at(time: String) = Instant.parse("2026-11-01T${time}:00Z")

    @Test
    fun personalRangeNeedsEnoughSamplesAndIsMeanPlusMinusOneSd() {
        assertThat(HealthMath.personalRange(List(13) { 50.0 })).isNull()
        val range = HealthMath.personalRange(List(7) { 40.0 } + List(7) { 60.0 })!!
        assertThat(range.mean).isWithin(1e-9).of(50.0)
        assertThat(range.low).isWithin(1e-9).of(40.0)
        assertThat(range.high).isWithin(1e-9).of(60.0)
        assertThat(range.position(65.0)).isEqualTo(RangePosition.ABOVE)
        assertThat(range.position(45.0)).isEqualTo(RangePosition.WITHIN)
    }

    @Test
    fun smoothedWeightMovesTenPercentTowardEachReading() {
        val trend = HealthMath.smoothed(listOf(80.0, 81.0, 79.0))
        assertThat(trend[0]).isWithin(1e-9).of(80.0)
        assertThat(trend[1]).isWithin(1e-9).of(80.1)
        assertThat(trend[2]).isWithin(1e-9).of(79.99)
    }

    @Test
    fun sleepEfficiencyAndStagesOnlyWithRealStages() {
        val start = at("23:00")
        val session = SleepSession(
            start = start,
            end = start.plusSeconds(8 * 3600),
            zoneId = "UTC",
            stages = listOf(
                SleepStage(SleepStageType.LIGHT, start, start.plusSeconds(4 * 3600)),
                SleepStage(SleepStageType.AWAKE, start.plusSeconds(4 * 3600), start.plusSeconds(4 * 3600 + 1800)),
                SleepStage(SleepStageType.DEEP, start.plusSeconds(4 * 3600 + 1800), start.plusSeconds(6 * 3600)),
                SleepStage(SleepStageType.REM, start.plusSeconds(6 * 3600), start.plusSeconds(8 * 3600)),
            ),
        )
        assertThat(session.asleepMinutes).isEqualTo(450)
        assertThat(HealthMath.sleepEfficiency(session)!!).isWithin(1e-9).of(450.0 / 480)
        assertThat(HealthMath.stageShares(session)[SleepStageType.REM]!!).isWithin(1e-9).of(120.0 / 450)

        val plain = session.copy(stages = listOf(SleepStage(SleepStageType.ASLEEP, start, start.plusSeconds(8 * 3600))))
        assertThat(HealthMath.sleepEfficiency(plain)).isNull()
        assertThat(HealthMath.stageShares(plain)).isEmpty()
    }

    @Test
    fun clockSpreadWrapsAroundMidnight() {
        val spread = HealthMath.clockSpreadMinutes(listOf(at("23:50"), Instant.parse("2026-11-02T00:10:00Z")), utc)
        assertThat(spread).isEqualTo(10)
        assertThat(HealthMath.meanClockMinutes(listOf(at("23:50"), Instant.parse("2026-11-02T00:10:00Z")), utc)).isEqualTo(0)
        assertThat(HealthMath.clockSpreadMinutes(listOf(at("23:00")), utc)).isNull()
    }

    @Test
    fun sleepDebtCountsOnlyShortNights() {
        assertThat(HealthMath.sleepDebtMinutes(listOf(420, 480, 400), 450)).isEqualTo(30 + 50)
    }

    @Test
    fun recoveryScoreCentresOnYourUsualAndNeedsBaseline() {
        // 40..60 range → mean 50, sd 10.
        val rhr = HealthMath.personalRange(List(7) { 40.0 } + List(7) { 60.0 })!!
        val hrv = HealthMath.personalRange(List(7) { 20.0 } + List(7) { 40.0 })!!
        // No baseline → null.
        assertThat(HealthMath.recoveryScore(55.0, null, 30.0, null)).isNull()
        // At your own mean → 50.
        assertThat(HealthMath.recoveryScore(50.0, rhr, 30.0, hrv)).isEqualTo(50)
        // Better than usual: lower resting HR and higher HRV → above 50.
        assertThat(HealthMath.recoveryScore(40.0, rhr, 40.0, hrv)).isGreaterThan(70)
        // Worse than usual: higher resting HR and lower HRV → below 50.
        assertThat(HealthMath.recoveryScore(60.0, rhr, 20.0, hrv)).isLessThan(30)
        // Clamped to 0–100.
        assertThat(HealthMath.recoveryScore(90.0, rhr, 0.0, hrv)).isEqualTo(0)
        assertThat(HealthMath.recoveryScore(10.0, rhr, 60.0, hrv)).isEqualTo(100)
    }

    @Test
    fun bloodPressureCategoriesFollowTheGuideline() {
        assertThat(HealthMath.bloodPressureCategory(118.0, 76.0)).isEqualTo(BloodPressureCategory.NORMAL)
        assertThat(HealthMath.bloodPressureCategory(124.0, 78.0)).isEqualTo(BloodPressureCategory.ELEVATED)
        assertThat(HealthMath.bloodPressureCategory(118.0, 82.0)).isEqualTo(BloodPressureCategory.STAGE_1)
        assertThat(HealthMath.bloodPressureCategory(142.0, 70.0)).isEqualTo(BloodPressureCategory.STAGE_2)
        assertThat(HealthMath.bloodPressureCategory(185.0, 100.0)).isEqualTo(BloodPressureCategory.SEVERE)
    }

    @Test
    fun timeInRangeAndDominantOrigin() {
        assertThat(HealthMath.timeInRange(listOf(60.0, 100.0, 150.0, 200.0))).isWithin(1e-9).of(0.5)
        assertThat(HealthMath.timeInRange(emptyList())).isNull()
        assertThat(HealthMath.dominantOrigin(listOf("a", "b", "b")) { it }).isEqualTo("b")
    }

    @Test
    fun capabilitiesSplitGrantedFromUnsupported() {
        val caps = HealthCapabilities(
            access = mapOf(
                HealthDataType.STEPS to Access.GRANTED,
                HealthDataType.SLEEP to Access.NOT_GRANTED,
                HealthDataType.MINDFULNESS to Access.UNSUPPORTED,
            ),
        )
        assertThat(caps.grantedTypes).containsExactly(HealthDataType.STEPS)
        assertThat(caps.notGranted).containsExactly(HealthDataType.SLEEP)
        assertThat(caps.supported).doesNotContain(HealthDataType.MINDFULNESS)
    }

    @Test
    fun dedupedTotalCountsDuplicatesAndOverlapsOnce() {
        fun tv(from: String, to: String, v: Double) = TimedValue(at(from) to at(to), v)
        // The same manual entry saved three times counts once.
        assertThat(HealthMath.dedupedTotal(List(3) { tv("21:00", "21:30", 9_999.0) })).isEqualTo(9_999.0)
        // Overlapping entries count the largest; separate ones add up.
        assertThat(HealthMath.dedupedTotal(listOf(tv("08:00", "09:00", 500.0), tv("08:30", "09:30", 700.0), tv("12:00", "12:10", 100.0)))).isEqualTo(800.0)
        // Back-to-back intervals don't overlap.
        assertThat(HealthMath.dedupedTotal(listOf(tv("08:00", "09:00", 1.0), tv("09:00", "10:00", 2.0)))).isEqualTo(3.0)
        // Duplicate point readings count once.
        assertThat(HealthMath.dedupedTotal(listOf(tv("10:00", "10:00", 4.0), tv("10:00", "10:00", 4.0)))).isEqualTo(4.0)
        assertThat(HealthMath.dedupedTotal(emptyList())).isEqualTo(0.0)
    }
}

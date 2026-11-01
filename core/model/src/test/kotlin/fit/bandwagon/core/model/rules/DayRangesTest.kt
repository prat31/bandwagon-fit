package fit.bandwagon.core.model.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DayRangesTest {
    private val newYork = ZoneId.of("America/New_York")
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun `day range is half open in local time`() {
        val date = LocalDate.of(2026, 11, 3)
        val range = DayRanges.dayRange(date, kolkata)

        assertThat(range.startInclusive).isEqualTo(Instant.parse("2026-11-02T18:30:00Z"))
        assertThat(range.endExclusive).isEqualTo(Instant.parse("2026-11-03T18:30:00Z"))
        assertThat(range.contains(range.startInclusive)).isTrue()
        assertThat(range.contains(range.endExclusive)).isFalse()
    }

    @Test
    fun `day range respects the spring forward DST transition`() {
        val date = LocalDate.of(2026, 3, 8)
        val range = DayRanges.dayRange(date, newYork)

        // 23 hours, not 24.
        assertThat(java.time.Duration.between(range.startInclusive, range.endExclusive).toHours()).isEqualTo(23)
        assertThat(range.startInclusive).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"))
    }

    @Test
    fun `overlap clips a session to the requested day`() {
        val date = LocalDate.of(2026, 11, 3)
        val start = date.atTime(23, 30).atZone(kolkata).toInstant()
        val end = date.plusDays(1).atTime(0, 45).atZone(kolkata).toInstant()

        assertThat(DayRanges.overlapMinutes(start, end, date, kolkata)).isEqualTo(30)
    }

    @Test
    fun `sleep window runs from noon the day before to 18 00 on the wake date`() {
        val wake = LocalDate.of(2026, 11, 3)
        val window = DayRanges.sleepWindow(wake, kolkata)

        assertThat(window.startInclusive).isEqualTo(Instant.parse("2026-11-02T06:30:00Z"))
        assertThat(window.endExclusive).isEqualTo(Instant.parse("2026-11-03T12:30:00Z"))
    }

    @Test
    fun `dates between is inclusive and safe for reversed input`() {
        val start = LocalDate.of(2026, 10, 30)
        val end = LocalDate.of(2026, 11, 2)

        assertThat(DayRanges.datesBetween(start, end)).containsExactly(
            LocalDate.of(2026, 10, 30),
            LocalDate.of(2026, 10, 31),
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 2),
        ).inOrder()
        assertThat(DayRanges.datesBetween(end, start)).isEmpty()
    }

    @Test
    fun `sleep cutoff is 18 00 local`() {
        val cutoff = DayRanges.sleepCutoff(LocalDate.of(2026, 11, 3), kolkata)
        assertThat(cutoff.atZone(kolkata).toLocalTime()).isEqualTo(LocalTime.of(18, 0))
    }
}

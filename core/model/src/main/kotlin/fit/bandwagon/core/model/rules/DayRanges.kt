package fit.bandwagon.core.model.rules

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Half-open instant range: [startInclusive, endExclusive). */
data class InstantRange(val startInclusive: Instant, val endExclusive: Instant) {
    operator fun contains(instant: Instant): Boolean =
        !instant.isBefore(startInclusive) && instant.isBefore(endExclusive)
}

/**
 * All date bucketing happens in the member's local time zone.
 * Using `atStartOfDay(zone)` (rather than `atTime(0, 0)`) keeps DST transitions correct.
 */
object DayRanges {
    fun dayStart(date: LocalDate, zone: ZoneId): Instant = date.atStartOfDay(zone).toInstant()

    fun dayRange(date: LocalDate, zone: ZoneId): InstantRange =
        InstantRange(dayStart(date, zone), dayStart(date.plusDays(1), zone))

    /**
     * Sleep for a wake date is read from 12:00 the previous day until 18:00 on the wake date.
     * This captures late bedtimes without pulling in the following night's sleep.
     */
    fun sleepWindow(wakeDate: LocalDate, zone: ZoneId): InstantRange = InstantRange(
        wakeDate.minusDays(1).atTime(12, 0).atZone(zone).toInstant(),
        wakeDate.atTime(18, 0).atZone(zone).toInstant(),
    )

    fun datesBetween(start: LocalDate, end: LocalDate): List<LocalDate> {
        if (end.isBefore(start)) return emptyList()
        return generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .toList()
    }

    /** Minutes of [start, end) that fall inside the given day. */
    fun overlapMinutes(start: Instant, end: Instant, date: LocalDate, zone: ZoneId): Int {
        val day = dayRange(date, zone)
        val from = maxOf(start, day.startInclusive)
        val to = minOf(end, day.endExclusive)
        if (!to.isAfter(from)) return 0
        return java.time.Duration.between(from, to).toMinutes().toInt()
    }

    /** 18:00 local on the wake date: the point where pending sleep becomes "no data". */
    fun sleepCutoff(wakeDate: LocalDate, zone: ZoneId): Instant =
        wakeDate.atTime(LocalTime.of(18, 0)).atZone(zone).toInstant()
}

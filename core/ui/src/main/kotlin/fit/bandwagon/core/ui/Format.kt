package fit.bandwagon.core.ui

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun formatRelative(instant: Instant?, now: Instant = Instant.now()): String {
    if (instant == null) return "not synced yet"
    val minutes = Duration.between(instant, now).toMinutes()
    return when {
        minutes < 1 -> "synced just now"
        minutes < 60 -> "synced ${minutes}m ago"
        minutes < 60 * 24 -> "synced ${minutes / 60}h ago"
        else -> "synced ${minutes / (60 * 24)}d ago"
    }
}

/** When something happened, for feed events (not sync status). */
fun formatAgo(instant: Instant?, now: Instant = Instant.now()): String {
    if (instant == null) return ""
    val minutes = Duration.between(instant, now).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

fun peopleCount(count: Int): String = if (count == 1) "1 person" else "$count people"

/** For future instants such as a paused-until timestamp. */
fun formatUntil(instant: Instant, now: Instant = Instant.now()): String {
    val minutes = Duration.between(now, instant).toMinutes()
    return when {
        minutes <= 0 -> "now"
        minutes < 60 -> "in ${minutes}m"
        minutes < 60 * 24 -> "in ${minutes / 60}h"
        else -> "in ${minutes / (60 * 24)}d"
    }
}

fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

fun formatShortDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

fun formatTime(time: LocalTime): String =
    time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()))

fun formatNumber(value: Long): String =
    java.text.NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours == 0) "${rest}m" else "${hours}h ${rest}m"
}

fun formatNumber(value: Double, decimals: Int = 0): String {
    val format = java.text.NumberFormat.getNumberInstance(Locale.getDefault())
    format.maximumFractionDigits = decimals
    format.minimumFractionDigits = 0
    return format.format(value)
}

/** Locale default: miles and pounds in the few countries that use them. */
fun usesImperial(units: fit.bandwagon.core.model.UnitSystem = fit.bandwagon.core.model.UnitSystem.AUTO): Boolean = when (units) {
    fit.bandwagon.core.model.UnitSystem.METRIC -> false
    fit.bandwagon.core.model.UnitSystem.IMPERIAL -> true
    fit.bandwagon.core.model.UnitSystem.AUTO -> Locale.getDefault().country in setOf("US", "LR", "MM")
}

fun formatDistance(meters: Double, imperial: Boolean = usesImperial()): String =
    if (imperial) "${formatNumber(meters / 1609.344, 1)} mi" else "${formatNumber(meters / 1000, 1)} km"

fun formatClockMinutes(minutesAfterMidnight: Int): String =
    formatTime(LocalTime.of(((minutesAfterMidnight % 1440) + 1440) % 1440 / 60, ((minutesAfterMidnight % 60) + 60) % 60))

fun plural(count: Int, one: String, many: String = one + "s"): String = "${formatNumber(count.toLong())} ${if (count == 1) one else many}"

package fit.bandwagon.feature.challenges

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.ui.graphics.vector.ImageVector
import fit.bandwagon.core.model.challenge.Aggregate
import fit.bandwagon.core.model.challenge.ChallengeKind
import fit.bandwagon.core.model.challenge.ChallengeMetric
import fit.bandwagon.core.model.challenge.ChallengeSpec
import fit.bandwagon.core.ui.formatDistance
import fit.bandwagon.core.ui.formatDuration
import fit.bandwagon.core.ui.formatNumber
import fit.bandwagon.core.ui.plural
import kotlin.math.roundToInt

fun templateIcon(icon: String): ImageVector = when (icon) {
    "steps" -> Icons.Filled.DirectionsWalk
    "bolt" -> Icons.Filled.Bolt
    "weekend" -> Icons.Filled.Weekend
    "work" -> Icons.Filled.Work
    "calendar" -> Icons.Filled.CalendarMonth
    "heart" -> Icons.Filled.FavoriteBorder
    "target" -> Icons.Filled.TrackChanges
    "fire" -> Icons.Filled.Whatshot
    "run" -> Icons.Filled.DirectionsRun
    "stairs" -> Icons.Filled.Stairs
    "route" -> Icons.Filled.Map
    "boss" -> Icons.Filled.LocalFireDepartment
    "sleep" -> Icons.Filled.Bedtime
    "moon" -> Icons.Filled.Nightlight
    "spa" -> Icons.Filled.SelfImprovement
    else -> Icons.Filled.Tune
}

/** A score in the challenge's own terms: steps, km, minutes, days, a streak… */
fun formatScore(spec: ChallengeSpec, score: Double): String {
    if (spec.aggregate == Aggregate.DAYS_HIT) return plural(score.roundToInt(), "day")
    if (spec.aggregate == Aggregate.LONGEST_STREAK) return "${plural(score.roundToInt(), "day")} streak"
    return formatMetric(spec.metric.id, score, spec.display.theme?.get("unit"))
}

fun formatMetric(metricId: String, value: Double, unitHint: String? = null): String = when (metricId) {
    "steps" -> if (unitHint == "km_from_steps") "≈ ${formatDistance(value * 0.75)}" else plural(value.roundToInt(), "step")
    "distance" -> formatDistance(value)
    "active_calories" -> "${formatNumber(value)} kcal"
    "active_minutes", "workout_minutes", "mindful_minutes" -> "${formatNumber(value)} min"
    "workout_count" -> plural(value.roundToInt(), "workout")
    "floors" -> plural(value.roundToInt(), "floor")
    "sleep_minutes" -> formatDuration(value.roundToInt())
    else -> formatNumber(value)
}

/** One line explaining the rules, built from the spec so new templates explain themselves. */
fun describeRules(spec: ChallengeSpec): String {
    val metric = ChallengeMetric.of(spec.metric.id)?.label?.lowercase() ?: spec.metric.id
    val daily = when (spec.daily.op) {
        "cap" -> " (up to ${formatMetric(spec.metric.id, spec.daily.max ?: 0.0)} a day)"
        "atLeast" -> ""
        else -> ""
    }
    val types = spec.metric.filter?.workoutTypes?.takeIf { it.isNotEmpty() }?.joinToString { it.lowercase().replace('_', ' ') }
    val what = if (types != null) "$metric from $types" else metric
    return when {
        spec.daily.op == "all" -> {
            val parts = spec.daily.conditions.orEmpty().map { c ->
                when (c.metric) {
                    "bedtime" -> "in bed by ${clock(c.value + (c.toleranceMin ?: 0.0))}"
                    "sleep_minutes" -> "sleep at least ${formatDuration(c.value.roundToInt())}"
                    else -> "${ChallengeMetric.of(c.metric)?.label ?: c.metric} ${c.cmp} ${formatMetric(c.metric, c.value)}"
                }
            }
            "Each night: ${parts.joinToString(" and ")}." + (spec.goal?.let { " Goal: ${plural(it.target.roundToInt(), "night")}." } ?: "")
        }
        spec.aggregate == Aggregate.DAYS_HIT -> "Count days with at least ${formatMetric(spec.metric.id, spec.daily.value ?: 0.0)}" +
            (spec.goal?.let { " — goal: ${plural(it.target.roundToInt(), "day")}" } ?: ", most days wins") + "."
        spec.aggregate == Aggregate.LONGEST_STREAK -> "Longest run of days with $what."
        spec.kind == ChallengeKind.TEAM -> "Everyone's $what$daily adds up to ${formatMetric(spec.metric.id, spec.goal?.target ?: 0.0, spec.display.theme?.get("unit"))}."
        spec.kind == ChallengeKind.TARGET -> "Everyone aims for ${formatMetric(spec.metric.id, spec.goal?.target ?: 0.0)} of $what$daily."
        spec.aggregate == Aggregate.BEST_DAY -> "Best single day of $what wins."
        spec.aggregate == Aggregate.AVERAGE -> "Highest daily average of $what wins."
        else -> "Most $what$daily wins."
    }
}

/** Wrapped minutes (-720..720) to a clock time. */
fun clock(wrapped: Double): String = fit.bandwagon.core.ui.formatClockMinutes(wrapped.roundToInt())

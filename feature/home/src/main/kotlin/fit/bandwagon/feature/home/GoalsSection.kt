@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package fit.bandwagon.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.goals.GoalAggregation
import fit.bandwagon.core.model.goals.GoalMetric
import fit.bandwagon.core.model.goals.GoalPeriod
import fit.bandwagon.core.model.goals.GoalStatus
import fit.bandwagon.core.model.goals.OutcomeResult
import fit.bandwagon.core.model.goals.PersonalGoal
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.BandwagonTheme
import fit.bandwagon.core.ui.Pill
import fit.bandwagon.core.ui.brandFilterChipColors
import fit.bandwagon.core.ui.charts.ProgressTrack
import fit.bandwagon.core.ui.formatDuration
import fit.bandwagon.core.ui.formatNumber
import fit.bandwagon.core.ui.metricColor
import fit.bandwagon.core.ui.metricIcon
import fit.bandwagon.core.ui.plural
import kotlin.math.roundToInt

/** Display units and conversions for goal values, which are stored in meters, minutes and ml. */
internal object GoalFormat {
    fun healthMetric(metric: GoalMetric): HealthMetric? = when (metric) {
        GoalMetric.STEPS -> HealthMetric.STEPS
        GoalMetric.DISTANCE -> HealthMetric.DISTANCE
        GoalMetric.ACTIVE_MINUTES -> HealthMetric.ACTIVE_MINUTES
        GoalMetric.WORKOUTS -> HealthMetric.WORKOUT_MINUTES
        GoalMetric.ACTIVE_CALORIES -> HealthMetric.ACTIVE_CALORIES
        GoalMetric.FLOORS -> HealthMetric.FLOORS
        GoalMetric.SLEEP, GoalMetric.SLEEP_SCORE -> HealthMetric.SLEEP
        GoalMetric.HYDRATION -> HealthMetric.HYDRATION
        GoalMetric.MINDFULNESS -> HealthMetric.MINDFULNESS
        GoalMetric.MOVE_SCORE -> null
    }

    fun icon(metric: GoalMetric): ImageVector = healthMetric(metric)?.let(::metricIcon) ?: Icons.Filled.Bolt

    @Composable
    fun color(metric: GoalMetric): Color = healthMetric(metric)?.let { metricColor(it) } ?: MaterialTheme.colorScheme.secondary

    /** A stored value as the user reads it, with its unit. */
    fun value(metric: GoalMetric, value: Double, imperial: Boolean): String = when (metric) {
        GoalMetric.STEPS -> "${formatNumber(value)} steps"
        GoalMetric.DISTANCE -> if (imperial) "${formatNumber(value / 1609.344, 1)} mi" else "${formatNumber(value / 1000, 1)} km"
        GoalMetric.ACTIVE_MINUTES, GoalMetric.MINDFULNESS -> "${formatNumber(value)} min"
        GoalMetric.WORKOUTS -> plural(value.roundToInt(), "workout")
        GoalMetric.ACTIVE_CALORIES -> "${formatNumber(value)} kcal"
        GoalMetric.FLOORS -> plural(value.roundToInt(), "floor")
        GoalMetric.SLEEP -> formatDuration(value.roundToInt())
        GoalMetric.SLEEP_SCORE, GoalMetric.MOVE_SCORE -> formatNumber(value)
        GoalMetric.HYDRATION -> if (imperial) "${formatNumber(value / 29.5735)} fl oz" else "${formatNumber(value / 1000, 1)} L"
    }

    fun inputUnit(metric: GoalMetric, imperial: Boolean): String = when (metric) {
        GoalMetric.STEPS -> "steps"
        GoalMetric.DISTANCE -> if (imperial) "mi" else "km"
        GoalMetric.ACTIVE_MINUTES, GoalMetric.MINDFULNESS -> "min"
        GoalMetric.WORKOUTS -> "workouts"
        GoalMetric.ACTIVE_CALORIES -> "kcal"
        GoalMetric.FLOORS -> "floors"
        GoalMetric.SLEEP -> "hours"
        GoalMetric.SLEEP_SCORE, GoalMetric.MOVE_SCORE -> "out of 100"
        GoalMetric.HYDRATION -> if (imperial) "fl oz" else "L"
    }

    fun toInput(metric: GoalMetric, stored: Double, imperial: Boolean): String = when (metric) {
        GoalMetric.DISTANCE -> formatPlain(if (imperial) stored / 1609.344 else stored / 1000)
        GoalMetric.SLEEP -> formatPlain(stored / 60)
        GoalMetric.HYDRATION -> formatPlain(if (imperial) stored / 29.5735 else stored / 1000)
        else -> formatPlain(stored)
    }

    fun fromInput(metric: GoalMetric, text: String, imperial: Boolean): Double? {
        val v = text.replace(',', '.').trim().toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return when (metric) {
            GoalMetric.DISTANCE -> if (imperial) v * 1609.344 else v * 1000
            GoalMetric.SLEEP -> v * 60
            GoalMetric.HYDRATION -> if (imperial) v * 29.5735 else v * 1000
            else -> v
        }
    }

    private fun formatPlain(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(java.util.Locale.ROOT, v)

    private fun per(period: GoalPeriod, metric: GoalMetric): String = when (period) {
        GoalPeriod.DAILY -> if (metric == GoalMetric.SLEEP || metric == GoalMetric.SLEEP_SCORE) "a night" else "a day"
        GoalPeriod.WEEKLY -> "a week"
        GoalPeriod.MONTHLY -> "a month"
    }

    /** "8,000 steps a day", "10,000 steps on 5 days a week", "7h 30m a night on average, weekly". */
    fun describe(goal: PersonalGoal, imperial: Boolean): String {
        val m = goal.metric
        if (goal.countsDays) {
            val unit = "${dayWord(m)}s"
            return "${value(m, goal.dailyThreshold!!, imperial)}+ on ${goal.target.roundToInt()} $unit ${per(goal.period, m)}"
        }
        return when {
            m == GoalMetric.SLEEP_SCORE || m == GoalMetric.MOVE_SCORE -> when (goal.period) {
                GoalPeriod.DAILY -> "${value(m, goal.target, imperial)} or more ${per(goal.period, m)}"
                else -> "Average ${value(m, goal.target, imperial)} or more over the ${goal.period.noun}"
            }
            m.aggregation == GoalAggregation.AVERAGE && goal.period != GoalPeriod.DAILY ->
                "${value(m, goal.target, imperial)} a night on average over the ${goal.period.noun}"
            else -> "${value(m, goal.target, imperial)} ${per(goal.period, m)}"
        }
    }

    /** Sleep goals count nights, everything else days. */
    fun dayWord(metric: GoalMetric): String = if (metric == GoalMetric.SLEEP || metric == GoalMetric.SLEEP_SCORE) "night" else "day"

    fun achieved(goal: PersonalGoal, value: Double, imperial: Boolean): String =
        if (goal.countsDays) "${value.roundToInt()} of ${goal.target.roundToInt()} ${dayWord(goal.metric)}s" else value(goal.metric, value, imperial)
}

@Composable
internal fun GoalsSection(
    goals: List<GoalCard>,
    loaded: Boolean,
    imperial: Boolean,
    onAdd: () -> Unit,
    onQuickAdd: (GoalMetric, GoalPeriod, Double) -> Unit,
    onOpen: (PersonalGoal) -> Unit,
    onShare: (PersonalGoal) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Goals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (goals.isNotEmpty()) TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }
        if (loaded && goals.isEmpty()) {
            EmptyGoals(onAdd, onQuickAdd)
        } else {
            goals.forEach { card -> GoalCardView(card, imperial, onClick = { onOpen(card.goal) }, onShare = { onShare(card.goal) }) }
        }
    }
}

@Composable
private fun EmptyGoals(onAdd: () -> Unit, onQuickAdd: (GoalMetric, GoalPeriod, Double) -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Flag, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Set your first goal", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Daily, weekly or monthly: steps, sleep, active minutes, workouts and more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Quick start", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onQuickAdd(GoalMetric.STEPS, GoalPeriod.DAILY, 8_000.0) }, label = { Text("8,000 steps a day") })
            AssistChip(onClick = { onQuickAdd(GoalMetric.ACTIVE_MINUTES, GoalPeriod.WEEKLY, 150.0) }, label = { Text("150 active min a week") })
            AssistChip(onClick = { onQuickAdd(GoalMetric.SLEEP, GoalPeriod.DAILY, 450.0) }, label = { Text("7h 30m sleep a night") })
            AssistChip(onClick = { onQuickAdd(GoalMetric.WORKOUTS, GoalPeriod.WEEKLY, 3.0) }, label = { Text("3 workouts a week") })
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAdd) { Text("Add a goal") }
    }
}

@Composable
private fun GoalCardView(card: GoalCard, imperial: Boolean, onClick: () -> Unit, onShare: () -> Unit) {
    val goal = card.goal
    val progress = card.progress
    val ext = BandwagonTheme.extendedColors
    val scheme = MaterialTheme.colorScheme
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(GoalFormat.icon(goal.metric), null, tint = GoalFormat.color(goal.metric), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${goal.metric.label} · ${goal.period.label}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(GoalFormat.describe(goal, imperial), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (card.shared) {
                val (label, color) = when (progress.status) {
                    GoalStatus.DONE -> "Done" to ext.successContainer
                    GoalStatus.ON_TRACK -> "On track" to scheme.primaryContainer
                    GoalStatus.BEHIND -> "Behind" to scheme.secondaryContainer
                    GoalStatus.OUT_OF_REACH -> "Missed" to scheme.errorContainer
                    GoalStatus.NO_DATA -> "No data yet" to scheme.surfaceVariant
                }
                Pill(label, color)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!card.shared) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Not shared with Bandwagon", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onShare) { Text("Share") }
            }
            return@AppCard
        }
        val done = progress.status == GoalStatus.DONE
        ProgressTrack(
            progress.fraction,
            if (done) ext.success else scheme.secondary,
            height = 8.dp,
            description = "${goal.metric.label} goal ${(progress.fraction * 100).roundToInt()} percent",
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                progress.achieved?.let { a ->
                    if (goal.metric.aggregation == GoalAggregation.AVERAGE && !goal.countsDays && goal.period != GoalPeriod.DAILY) "Average ${GoalFormat.achieved(goal, a, imperial)}"
                    else GoalFormat.achieved(goal, a, imperial)
                } ?: "Nothing yet",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(goalFooter(card, imperial), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        if (card.recent.isNotEmpty() || card.streak > 1) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                card.recent.forEach { o ->
                    val (fill, line) = when (o.result) {
                        OutcomeResult.HIT -> ext.success to ext.success
                        OutcomeResult.MISSED -> Color.Transparent to scheme.error
                        OutcomeResult.NO_DATA -> Color.Transparent to scheme.outline
                    }
                    Box(Modifier.padding(end = 6.dp).size(10.dp).background(fill, CircleShape).border(1.5.dp, line, CircleShape))
                }
                if (card.recent.isNotEmpty()) {
                    Text("last ${plural(card.recent.size, goal.period.noun)}", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (card.streak > 1) {
                    Text("🔥 ${card.streak} ${goal.period.noun}s in a row", style = MaterialTheme.typography.labelSmall, color = scheme.secondary)
                }
            }
        }
    }
}

private fun goalFooter(card: GoalCard, imperial: Boolean): String {
    val p = card.progress
    val goal = card.goal
    val left = when (goal.period) {
        GoalPeriod.DAILY -> "today"
        else -> if (p.daysLeft == 1) "last day" else "${p.daysLeft} days left"
    }
    return when (p.status) {
        GoalStatus.DONE -> if (goal.period == GoalPeriod.DAILY) "Goal hit 🎉" else "Hit · $left"
        GoalStatus.OUT_OF_REACH -> "Out of reach this ${goal.period.noun}"
        GoalStatus.NO_DATA -> left
        else -> when {
            goal.countsDays -> "${(goal.target - (p.achieved ?: 0.0)).roundToInt()} more ${GoalFormat.dayWord(goal.metric)}s · $left"
            goal.metric.aggregation == GoalAggregation.TOTAL -> "${p.remaining?.let { GoalFormat.value(goal.metric, it, imperial) }} to go · $left"
            else -> left
        }
    }
}

/** Add a goal, or edit one's target. Metric and period are fixed once a goal exists. */
@Composable
internal fun GoalSheet(
    editing: PersonalGoal?,
    imperial: Boolean,
    onDismiss: () -> Unit,
    onSave: (GoalMetric, GoalPeriod, Double, Double?) -> Unit,
    onDelete: (PersonalGoal) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var metric by remember { mutableStateOf(editing?.metric ?: GoalMetric.STEPS) }
    var period by remember { mutableStateOf(editing?.period ?: GoalPeriod.DAILY) }
    var daysStyle by remember { mutableStateOf(editing?.countsDays ?: false) }
    var target by remember { mutableStateOf(editing?.let { if (it.countsDays) it.target.roundToInt().toString() else GoalFormat.toInput(it.metric, it.target, imperial) } ?: GoalFormat.toInput(metric, metric.defaultTarget(period), imperial)) }
    var threshold by remember { mutableStateOf(GoalFormat.toInput(metric, editing?.dailyThreshold ?: metric.dailyDefault, imperial)) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun resetDefaults() {
        target = if (daysStyle && period != GoalPeriod.DAILY) (if (period == GoalPeriod.WEEKLY) "5" else "20") else GoalFormat.toInput(metric, metric.defaultTarget(period), imperial)
        threshold = GoalFormat.toInput(metric, metric.dailyDefault, imperial)
    }

    val usesDays = daysStyle && period != GoalPeriod.DAILY
    val maxDays = if (period == GoalPeriod.WEEKLY) 7 else 31
    val parsedTarget = if (usesDays) target.trim().toIntOrNull()?.takeIf { it in 1..maxDays }?.toDouble() else GoalFormat.fromInput(metric, target, imperial)?.takeIf { it in metric.min..metric.max }
    val parsedThreshold = if (usesDays) GoalFormat.fromInput(metric, threshold, imperial)?.takeIf { it in metric.min..metric.max } else null
    val valid = parsedTarget != null && (!usesDays || parsedThreshold != null)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (editing == null) "New goal" else "Edit goal", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            if (editing == null) {
                Text("What", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalMetric.entries.forEach { m ->
                        FilterChip(
                            selected = metric == m,
                            onClick = { metric = m; resetDefaults() },
                            label = { Text(m.label) },
                            leadingIcon = { Icon(GoalFormat.icon(m), null, Modifier.size(16.dp)) },
                            colors = brandFilterChipColors(),
                        )
                    }
                }
                Text("How often", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalPeriod.entries.forEach { p ->
                        FilterChip(selected = period == p, onClick = { period = p; resetDefaults() }, label = { Text(p.label) }, colors = brandFilterChipColors())
                    }
                }
                if (period != GoalPeriod.DAILY) {
                    Text("Count", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !daysStyle,
                            onClick = { daysStyle = false; resetDefaults() },
                            label = { Text(if (metric.aggregation == GoalAggregation.TOTAL) "Total for the ${period.noun}" else "Average over the ${period.noun}") },
                            colors = brandFilterChipColors(),
                        )
                        FilterChip(selected = daysStyle, onClick = { daysStyle = true; resetDefaults() }, label = { Text("Number of ${GoalFormat.dayWord(metric)}s") }, colors = brandFilterChipColors())
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(GoalFormat.icon(metric), null, tint = GoalFormat.color(metric))
                    Spacer(Modifier.width(8.dp))
                    Text("${metric.label} · ${period.label}", style = MaterialTheme.typography.titleMedium)
                }
            }

            val unit = GoalFormat.inputUnit(metric, imperial)
            val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            if (usesDays) {
                OutlinedTextField(threshold, { threshold = it.take(9) }, label = { Text("Each ${GoalFormat.dayWord(metric)} at least") }, suffix = { Text(unit) }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    target, { target = it.filter(Char::isDigit).take(2) },
                    label = { Text("On how many ${GoalFormat.dayWord(metric)}s") },
                    suffix = { Text("of $maxDays") },
                    supportingText = { Text("${GoalFormat.dayWord(metric).replaceFirstChar { it.uppercase() }}s of the ${period.noun} that reach the amount above") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                val hint = when {
                    period == GoalPeriod.DAILY -> if (metric == GoalMetric.SLEEP || metric == GoalMetric.SLEEP_SCORE) "Each night" else "Each day"
                    metric.aggregation == GoalAggregation.AVERAGE -> "Average over the ${period.noun}"
                    else -> "Total for the ${period.noun}"
                }
                OutlinedTextField(target, { target = it.take(9) }, label = { Text("Target") }, suffix = { Text(unit) }, supportingText = { Text(hint) }, singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth())
            }
            if (period != GoalPeriod.DAILY && editing == null) {
                Text(
                    "Weeks run Monday to Sunday. A finished ${period.noun} is checked again for ${fit.bandwagon.core.model.goals.GoalEngine.SETTLE_DAYS} days in case your devices sync late.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(onClick = { onSave(metric, period, parsedTarget!!, parsedThreshold) }, enabled = valid, modifier = Modifier.fillMaxWidth()) {
                Text(if (editing == null) "Add goal" else "Save")
            }
            if (editing != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete goal", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmDelete && editing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this goal?") },
            text = { Text("Its history goes too. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(editing) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Health Connect types to ask for when a goal's data isn't shared. */
internal fun shareTypes(goal: PersonalGoal, sources: GoalSources): Set<HealthDataType> = GoalValues.typesFor(goal.metric, sources)

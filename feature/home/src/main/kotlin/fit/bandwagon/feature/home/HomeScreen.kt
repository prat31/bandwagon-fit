@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fit.bandwagon.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fit.bandwagon.core.health.rememberShareHealth
import fit.bandwagon.core.model.Goals
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.RangePosition
import fit.bandwagon.core.model.goals.GoalMetric
import fit.bandwagon.core.model.goals.GoalPeriod
import fit.bandwagon.core.model.goals.PersonalGoal
import fit.bandwagon.core.model.scores.DailyScore
import fit.bandwagon.core.model.scores.ScoreRating
import fit.bandwagon.core.model.insights.Insight
import fit.bandwagon.core.model.insights.InsightKind
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.BandwagonTheme
import fit.bandwagon.core.ui.MetricFormat
import fit.bandwagon.core.ui.charts.GradientProgressTrack
import fit.bandwagon.core.ui.charts.ProgressTrack
import fit.bandwagon.core.ui.formatNumber
import fit.bandwagon.core.ui.formatRelative
import fit.bandwagon.core.ui.usesImperial
import fit.bandwagon.feature.challenges.ChallengeCard
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    onOpenChallenge: (groupId: String, challengeId: String) -> Unit,
    onOpenMetric: (HealthMetric) -> Unit,
    onOpenSleep: () -> Unit,
    onOpenGroups: () -> Unit,
    onDeepLink: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val share = rememberShareHealth()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val imperial = usesImperial(state.profile?.settings?.units ?: fit.bandwagon.core.model.UnitSystem.AUTO)
    val caps = state.capabilities
    val today = LocalDate.now()
    // null = closed; a new goal uses a placeholder until saved.
    var sheetFor by remember { mutableStateOf<GoalSheetTarget?>(null) }

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${greeting()}, ${state.profile?.displayName?.substringBefore(' ')?.ifBlank { null } ?: "there"}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(today.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { TodayCard(state, onOpenMetric, onOpenSleep, onShare = { share.request(it) }) }

            if (caps.anyGranted) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.today.distance?.let {
                            QuickStat("Distance", MetricFormat.full(HealthMetric.DISTANCE, it, imperial), it, dailyTarget(state, GoalMetric.DISTANCE), Modifier.weight(1f)) { onOpenMetric(HealthMetric.DISTANCE) }
                        }
                        state.today.activeCalories?.let {
                            QuickStat("Active", "${formatNumber(it)} kcal", it, dailyTarget(state, GoalMetric.ACTIVE_CALORIES), Modifier.weight(1f)) { onOpenMetric(HealthMetric.ACTIVE_CALORIES) }
                        }
                        state.today.floors?.let {
                            QuickStat("Floors", formatNumber(it), it, dailyTarget(state, GoalMetric.FLOORS), Modifier.weight(1f)) { onOpenMetric(HealthMetric.FLOORS) }
                        }
                    }
                }
            }

            item {
                GoalsSection(
                    goals = state.goals,
                    loaded = state.goalsLoaded,
                    imperial = imperial,
                    onAdd = { sheetFor = GoalSheetTarget(null) },
                    onQuickAdd = { metric, period, target -> viewModel.addGoal(metric, period, target, null) },
                    onOpen = { sheetFor = GoalSheetTarget(it) },
                    onShare = { share.request(shareTypes(it, state.goalSources)) },
                )
            }

            if (state.invites > 0) {
                item {
                    AppCard(onClick = onOpenGroups) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Mail, null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(12.dp))
                            Text("${state.invites} group ${if (state.invites == 1) "invite" else "invites"} waiting", style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your challenges", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenGroups) { Text("Groups") }
                }
            }
            if (state.challenges.isEmpty()) {
                item {
                    AppCard(onClick = onOpenGroups) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Groups, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(if (state.groupCount == 0) "Find a group" else "No live challenges", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (state.groupCount == 0) "Join a public group or create one with friends." else "Owners and mods can start one in any of your groups.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.challenges, key = { it.card.challenge.id }) { tc ->
                            Column(Modifier.width(280.dp)) {
                                ChallengeCard(tc.card, today, onClick = { onOpenChallenge(tc.groupId, tc.card.challenge.id) }, groupName = tc.groupName)
                            }
                        }
                    }
                }
            }

            if (state.insights.isNotEmpty()) {
                item { Text("Insights", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(state.insights, key = { it.id }) { insight ->
                    InsightCard(insight, onOpen = { insight.deepLink?.let(onDeepLink) }, onDismiss = { viewModel.dismiss(insight) })
                }
            }

            item {
                Text(
                    if (caps.background) "Updated ${formatRelative(state.profile?.lastSyncAt).removePrefix("synced ")}" else "Updated when you open the app · ${formatRelative(state.profile?.lastSyncAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    sheetFor?.let { target ->
        GoalSheet(
            editing = target.goal,
            imperial = imperial,
            onDismiss = { sheetFor = null },
            onSave = { metric, period, value, threshold ->
                val editing = target.goal
                if (editing == null) viewModel.addGoal(metric, period, value, threshold)
                else viewModel.updateGoal(editing.copy(target = value, dailyThreshold = threshold))
                sheetFor = null
            },
            onDelete = {
                viewModel.deleteGoal(it)
                sheetFor = null
            },
        )
    }
}

private data class GoalSheetTarget(val goal: PersonalGoal?)

/** A daily goal for the quick stats' bars; the metric's usual default when there's none. */
private fun dailyTarget(state: HomeState, metric: GoalMetric): Double =
    state.goals.firstOrNull { it.goal.metric == metric && it.goal.period == GoalPeriod.DAILY }?.goal?.target ?: metric.dailyDefault

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/**
 * One short lead line for the hero card: the single most useful thing to act on today. Deliberately
 * distinct from the insight cards below — a challenge closing or the step goal beats a trend note.
 */
private fun todayHeadline(state: HomeState, today: LocalDate): String {
    val ending = state.challenges.firstOrNull { it.card.challenge.spec?.window?.endDate == today }
    if (ending != null) return "${ending.card.challenge.spec?.title ?: "A challenge"} ends today — last push!"
    val goals = state.profile?.goals ?: Goals()
    val steps = state.today.steps
    if (state.capabilities.granted(state.today.stepsMetric.dataType) && steps != null) {
        val left = goals.steps - steps
        return if (left <= 0) "Step goal in the bag 🎉" else "${formatNumber(left)} steps to your goal"
    }
    return "Your day at a glance"
}

private fun recoveryStatus(score: Int?): String = when {
    score == null -> "needs more data"
    score >= 65 -> "Fresh"
    score >= 45 -> "Typical"
    else -> "Low"
}

@Composable
private fun scoreColor(score: DailyScore?): Color {
    val ext = BandwagonTheme.extendedColors
    return when (score?.rating) {
        null -> MaterialTheme.colorScheme.onSurfaceVariant
        ScoreRating.EXCELLENT, ScoreRating.GOOD -> ext.success
        ScoreRating.FAIR -> ext.highlight
        ScoreRating.LOW -> MaterialTheme.colorScheme.error
    }
}

/** "Usual", or above/below the user's own range; a higher value is bad for [higherIsWorse]. */
@Composable
private fun vitalDetail(vital: Vital?, today: LocalDate, higherIsWorse: Boolean, noRange: String): Pair<String, Color> {
    val ext = BandwagonTheme.extendedColors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (vital == null) return "No data yet" to muted
    val prefix = if (vital.date == today) "" else "Yesterday · "
    val range = vital.range ?: return "$prefix$noRange" to muted
    return when (range.position(vital.value)) {
        RangePosition.WITHIN -> "${prefix}Usual" to muted
        RangePosition.ABOVE -> "${prefix}Above usual" to if (higherIsWorse) ext.heart else ext.success
        RangePosition.BELOW -> "${prefix}Below usual" to if (higherIsWorse) ext.success else muted
    }
}

@Composable
private fun TodayCard(
    state: HomeState,
    onOpenMetric: (HealthMetric) -> Unit,
    onOpenSleep: () -> Unit,
    onShare: (Set<HealthDataType>) -> Unit,
) {
    val caps = state.capabilities
    val goals = state.profile?.goals ?: Goals()
    val numbers = state.today
    val ext = BandwagonTheme.extendedColors
    val today = LocalDate.now()
    val imperial = usesImperial(state.profile?.settings?.units ?: fit.bandwagon.core.model.UnitSystem.AUTO)
    val recoveryColor = when {
        numbers.recovery == null -> MaterialTheme.colorScheme.onSurfaceVariant
        numbers.recovery >= 65 -> ext.success
        numbers.recovery >= 45 -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> ext.heart
    }
    AppCard {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(todayHeadline(state, today), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val steps = numbers.steps
                if (caps.granted(numbers.stepsMetric.dataType) && steps != null) {
                    val progress = (steps / goals.steps).toFloat().coerceIn(0f, 1f)
                    GradientProgressTrack(progress, description = "${(progress * 100).roundToInt()} percent of your step goal")
                    Text(
                        "${formatNumber(steps)} of ${formatNumber(goals.steps.toLong())} ${if (numbers.stepsMetric == HealthMetric.STEPS) "steps" else "pushes"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DayNumber(
                    icon = Icons.Filled.Bedtime,
                    color = ext.sleepDeep,
                    label = "Sleep",
                    shared = caps.granted(HealthDataType.SLEEP),
                    value = numbers.sleepScore?.value?.toString() ?: "–",
                    detail = numbers.sleepScore?.rating?.label ?: if (numbers.lastNight == null) "No sleep yet" else "needs more data",
                    detailColor = scoreColor(numbers.sleepScore),
                    modifier = Modifier.weight(1f),
                    onClick = onOpenSleep,
                    onShare = { onShare(setOf(HealthDataType.SLEEP)) },
                )
                DayNumber(
                    icon = Icons.Filled.DirectionsWalk,
                    color = MaterialTheme.colorScheme.secondary,
                    label = "Move",
                    shared = caps.granted(numbers.stepsMetric.dataType) || caps.granted(numbers.activeMetric.dataType),
                    value = numbers.moveScore?.value?.toString() ?: "–",
                    detail = numbers.moveScore?.rating?.label ?: "No activity yet",
                    detailColor = scoreColor(numbers.moveScore),
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenMetric(numbers.stepsMetric) },
                    onShare = { onShare(setOf(numbers.stepsMetric.dataType, numbers.activeMetric.dataType)) },
                )
                DayNumber(
                    icon = Icons.Filled.Favorite,
                    color = ext.heart,
                    label = "Recovery",
                    shared = caps.granted(HealthDataType.RESTING_HEART_RATE) || caps.granted(HealthDataType.HRV),
                    value = numbers.recovery?.toString() ?: "–",
                    detail = recoveryStatus(numbers.recovery),
                    detailColor = recoveryColor,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenMetric(HealthMetric.RESTING_HEART_RATE) },
                    onShare = { onShare(setOf(HealthDataType.RESTING_HEART_RATE, HealthDataType.HRV)) },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val rhr = vitalDetail(numbers.restingHeartRate, today, higherIsWorse = true, noRange = "bpm")
                DayNumber(
                    icon = Icons.Filled.MonitorHeart,
                    color = ext.heart,
                    label = "Resting HR",
                    shared = caps.granted(HealthDataType.RESTING_HEART_RATE),
                    value = numbers.restingHeartRate?.let { formatNumber(it.value) } ?: "–",
                    detail = rhr.first,
                    detailColor = rhr.second,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenMetric(HealthMetric.RESTING_HEART_RATE) },
                    onShare = { onShare(setOf(HealthDataType.RESTING_HEART_RATE)) },
                )
                val breathing = vitalDetail(numbers.respiratoryRate, today, higherIsWorse = true, noRange = "br/min")
                DayNumber(
                    icon = Icons.Filled.Air,
                    color = ext.vitals,
                    label = "Respiratory",
                    shared = caps.granted(HealthDataType.RESPIRATORY_RATE),
                    value = numbers.respiratoryRate?.let { MetricFormat.value(HealthMetric.RESPIRATORY_RATE, it.value, imperial) } ?: "–",
                    detail = breathing.first,
                    detailColor = breathing.second,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenMetric(HealthMetric.RESPIRATORY_RATE) },
                    onShare = { onShare(setOf(HealthDataType.RESPIRATORY_RATE)) },
                )
                val skin = vitalDetail(numbers.skinTemperature, today, higherIsWorse = true, noRange = "vs baseline")
                DayNumber(
                    icon = Icons.Filled.Thermostat,
                    color = ext.vitals,
                    label = "Skin temp",
                    shared = caps.granted(HealthDataType.SKIN_TEMPERATURE),
                    value = numbers.skinTemperature?.let { MetricFormat.full(HealthMetric.SKIN_TEMPERATURE, it.value, imperial) } ?: "–",
                    detail = skin.first,
                    detailColor = skin.second,
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenMetric(HealthMetric.SKIN_TEMPERATURE) },
                    onShare = { onShare(setOf(HealthDataType.SKIN_TEMPERATURE)) },
                )
            }
            if (!caps.anyGranted) {
                Button(onClick = { onShare(emptySet()) }) { Text("Share health data") }
            }
        }
    }
}

@Composable
private fun DayNumber(
    icon: ImageVector,
    color: Color,
    label: String,
    shared: Boolean,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
    detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
    onShare: () -> Unit,
) {
    Column(modifier = modifier.clickable(onClick = if (shared) onClick else onShare)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (shared) color else MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Text(if (shared) value else "–", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        Text(if (shared) detail else "Not shared · Share", style = MaterialTheme.typography.labelSmall, color = if (shared) detailColor else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A quick stat with a bar toward its daily target; it turns success once the target is met. */
@Composable
private fun QuickStat(label: String, value: String, amount: Double, target: Double, modifier: Modifier, onClick: () -> Unit) {
    val progress = if (target > 0) (amount / target).toFloat().coerceIn(0f, 1f) else 0f
    AppCard(onClick = onClick, modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ProgressTrack(
            progress,
            if (progress >= 1f) BandwagonTheme.extendedColors.success else MaterialTheme.colorScheme.secondary,
            height = 6.dp,
            description = "$label ${(progress * 100).roundToInt()} percent of today's target",
        )
    }
}

@Composable
private fun InsightCard(insight: Insight, onOpen: () -> Unit, onDismiss: () -> Unit) {
    var why by remember { mutableStateOf(false) }
    val tint = when (insight.kind) {
        InsightKind.ACTIVITY -> MaterialTheme.colorScheme.secondary
        InsightKind.SLEEP -> BandwagonTheme.extendedColors.sleepDeep
        InsightKind.HEART -> BandwagonTheme.extendedColors.heart
        InsightKind.CHALLENGE -> MaterialTheme.colorScheme.tertiary
    }
    AppCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                when (insight.kind) {
                    InsightKind.SLEEP -> Icons.Filled.Bedtime
                    InsightKind.HEART -> Icons.Filled.FavoriteBorder
                    InsightKind.CHALLENGE -> Icons.Filled.Groups
                    InsightKind.ACTIVITY -> Icons.Filled.Insights
                },
                null,
                tint = tint,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(insight.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(insight.body, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { why = !why }, contentPadding = PaddingValues(0.dp)) { Text(if (why) "Hide" else "Why am I seeing this?") }
                if (why) Text(insight.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss for a week", Modifier.size(18.dp)) }
        }
    }
}

@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package fit.bandwagon.feature.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fit.bandwagon.core.health.rememberShareHealth
import fit.bandwagon.core.model.HealthCategory
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.HealthMath
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.MetricKind
import fit.bandwagon.core.model.SleepStageType
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.BandwagonTheme
import fit.bandwagon.core.ui.LoadingState
import fit.bandwagon.core.ui.MetricFormat
import fit.bandwagon.core.ui.categoryColor
import fit.bandwagon.core.ui.charts.Hypnogram
import fit.bandwagon.core.ui.charts.MiniBars
import fit.bandwagon.core.ui.charts.Sparkline
import fit.bandwagon.core.ui.charts.StageSpan
import fit.bandwagon.core.ui.formatDuration
import fit.bandwagon.core.ui.formatShortDate
import fit.bandwagon.core.ui.formatTime
import fit.bandwagon.core.ui.metricColor
import fit.bandwagon.core.ui.metricIcon
import fit.bandwagon.core.ui.usesImperial
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything Health Connect holds about you, on the phone only. Tiles show today's value, a week
 * of context and the change versus your usual; missing types are offered under "Unlock more".
 */
@Composable
fun HealthScreen(
    onMetric: (HealthMetric) -> Unit,
    onSleep: () -> Unit,
    onWorkouts: () -> Unit,
    viewModel: HealthOverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val share = rememberShareHealth()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val imperial = usesImperial(state.settings.units)
    val caps = state.capabilities

    PullToRefreshBox(isRefreshing = false, onRefresh = { viewModel.refresh(force = true) }, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(164.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            full { Text("Health", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold) }
            full {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Stays on this phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!caps.anyGranted) {
                full {
                    AppCard {
                        Text("Connect your health data", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Share data from Health Connect to see your steps, sleep, heart and more. You choose exactly what to share.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { share.request() }) { Text("Share health data") }
                    }
                }
            } else if (state.loading && state.tiles.isEmpty()) {
                full { LoadingState(Modifier.height(240.dp)) }
            }

            HealthCategory.entries.forEach { category ->
                val tiles = state.tiles[category].orEmpty()
                val showSleep = category == HealthCategory.SLEEP && state.lastNight != null
                val showWorkouts = category == HealthCategory.ACTIVITY && state.workoutsThisWeek.isNotEmpty()
                if (tiles.isEmpty() && !showSleep && !showWorkouts) return@forEach
                full {
                    Text(
                        category.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = categoryColor(category).takeIf { category != HealthCategory.ACTIVITY } ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (showSleep) full { SleepCard(state.lastNight!!, state.goals.sleepMinutes, onClick = onSleep) }
                items(tiles, key = { it.metric.name }) { tile -> MetricTile(tile, imperial, onClick = { onMetric(tile.metric) }) }
                if (showWorkouts) {
                    item(key = "workouts") { WorkoutsTile(state.workoutsThisWeek, onClick = onWorkouts) }
                }
            }

            if (caps.anyGranted && caps.notGranted.isNotEmpty()) {
                full {
                    AppCard {
                        Text("Unlock more", style = MaterialTheme.typography.titleMedium)
                        Text("These aren't shared with Bandwagon yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            caps.notGranted.sortedBy { it.ordinal }.forEach { type ->
                                AssistChip(onClick = { share.request(setOf(type)) }, label = { Text(type.label) })
                            }
                        }
                        Button(onClick = { share.request(caps.notGranted) }, modifier = Modifier.padding(top = 8.dp)) {
                            Text(if (caps.canRequestAgain) "Share all" else "Open Health Connect")
                        }
                    }
                }
            }
            full { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.full(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun MetricTile(tile: Tile, imperial: Boolean, onClick: () -> Unit) {
    val metric = tile.metric
    val color = if (metric.category == HealthCategory.ACTIVITY) MaterialTheme.colorScheme.primary else metricColor(metric)
    val valueText = when {
        tile.value == null -> "–"
        metric == HealthMetric.BLOOD_PRESSURE -> "${tile.value.roundToInt()}/${tile.secondary?.roundToInt() ?: "–"}"
        else -> MetricFormat.value(metric, tile.value, imperial)
    }
    val unit = MetricFormat.unitLabel(metric, imperial)
    val goalHit = tile.goal != null && tile.value != null && tile.value >= tile.goal && metric.kind == MetricKind.CUMULATIVE
    AppCard(onClick = onClick, modifier = Modifier.semantics { contentDescription = "${metric.label}: $valueText $unit" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(metricIcon(metric), null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(metric.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.weight(1f))
            tile.changePct?.takeIf { abs(it) >= 5 }?.let { pct ->
                Icon(if (pct > 0) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${abs(pct).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(valueText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                goalHit -> {
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = BandwagonTheme.extendedColors.success)
                    Text(" Goal", style = MaterialTheme.typography.labelSmall)
                }
                tile.asOf != null -> Text(formatShortDate(tile.asOf), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                metric == HealthMetric.BLOOD_PRESSURE && tile.value != null && tile.secondary != null ->
                    Text(HealthMath.bloodPressureCategory(tile.value, tile.secondary).label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(if (metric.kind == MetricKind.CUMULATIVE) "Today" else "Latest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (metric.kind == MetricKind.CUMULATIVE) MiniBars(tile.last7, color, goal = tile.goal) else Sparkline(tile.last7, color)
    }
}

/** Laid out like [MetricTile] (header, value, status line, 7-day bars) so it sits evenly in the grid. */
@Composable
private fun WorkoutsTile(workouts: List<fit.bandwagon.core.model.WorkoutSummary>, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.primary
    val zone = ZoneId.systemDefault()
    val today = java.time.LocalDate.now()
    val minutesByDay = workouts.groupBy { it.start.atZone(zone).toLocalDate() }.mapValues { (_, w) -> w.sumOf { it.durationMinutes }.toDouble() }
    val last7 = (6L downTo 0L).map { minutesByDay[today.minusDays(it)] ?: 0.0 }
    AppCard(onClick = onClick, modifier = Modifier.semantics { contentDescription = "Workouts: ${workouts.size} in the last 7 days" }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FitnessCenter, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Workouts", style = MaterialTheme.typography.labelLarge, maxLines = 1, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${workouts.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            Text(formatDuration(workouts.sumOf { it.durationMinutes }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text("Last 7 days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        MiniBars(last7, color)
    }
}

@Composable
private fun SleepCard(session: fit.bandwagon.core.model.SleepSession, goalMinutes: Int, onClick: () -> Unit) {
    val zone = ZoneId.of(session.zoneId)
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Last night", style = MaterialTheme.typography.labelLarge)
                Text(formatDuration(session.asleepMinutes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${formatTime(session.start.atZone(zone).toLocalTime())} → ${formatTime(session.end.atZone(zone).toLocalTime())} · goal ${formatDuration(goalMinutes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (session.asleepMinutes >= goalMinutes) Icon(Icons.Filled.CheckCircle, "Goal met", tint = BandwagonTheme.extendedColors.success)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
        if (session.hasStages) {
            Spacer(Modifier.height(8.dp))
            Hypnogram(
                session.stages.map { StageSpan(it.type, it.start, it.end) },
                session.start,
                session.end,
                description = "Sleep stages: deep ${session.stageMinutes(SleepStageType.DEEP)} minutes, REM ${session.stageMinutes(SleepStageType.REM)} minutes",
                height = 72.dp,
            )
        }
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fit.bandwagon.feature.health

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fit.bandwagon.core.health.rememberShareHealth
import fit.bandwagon.core.model.Bucket
import fit.bandwagon.core.model.ChartPeriod
import fit.bandwagon.core.model.HealthMath
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.MetricKind
import fit.bandwagon.core.model.MetricPoint
import fit.bandwagon.core.model.SleepStageType
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.BandwagonTheme
import fit.bandwagon.core.ui.LoadingState
import fit.bandwagon.core.ui.MetricFormat
import fit.bandwagon.core.ui.NotSharedNote
import fit.bandwagon.core.ui.ScreenScaffold
import fit.bandwagon.core.ui.SectionHeader
import fit.bandwagon.core.ui.brandFilterChipColors
import fit.bandwagon.core.ui.charts.BarChart
import fit.bandwagon.core.ui.charts.BarDatum
import fit.bandwagon.core.ui.charts.Hypnogram
import fit.bandwagon.core.ui.charts.LineChart
import fit.bandwagon.core.ui.charts.RangeBarChart
import fit.bandwagon.core.ui.charts.ScheduleChart
import fit.bandwagon.core.ui.charts.ShareBar
import fit.bandwagon.core.ui.charts.StageSpan
import fit.bandwagon.core.ui.formatDistance
import fit.bandwagon.core.ui.formatDuration
import fit.bandwagon.core.ui.formatNumber
import fit.bandwagon.core.ui.formatShortDate
import fit.bandwagon.core.ui.formatTime
import fit.bandwagon.core.ui.metricColor
import fit.bandwagon.core.ui.metricIcon
import fit.bandwagon.core.ui.usesImperial
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
private fun PeriodPicker(selected: ChartPeriod, options: List<ChartPeriod> = ChartPeriod.entries, onSelect: (ChartPeriod) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, period ->
            SegmentedButton(
                selected = period == selected,
                onClick = { onSelect(period) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) { Text(period.label) }
        }
    }
}

/** Axis label for a bucket start. */
private fun label(point: MetricPoint, bucket: Bucket): String {
    val local = point.start.atZone(ZoneId.systemDefault())
    return when (bucket) {
        Bucket.HOUR -> local.hour.toString()
        Bucket.DAY -> local.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
        Bucket.WEEK -> local.format(DateTimeFormatter.ofPattern("d/M"))
        Bucket.MONTH -> local.month.getDisplayName(TextStyle.NARROW, Locale.getDefault())
    }
}

@Composable
fun MetricDetailScreen(onBack: () -> Unit, viewModel: MetricDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val share = rememberShareHealth()
    val metric = state.metric
    val imperial = usesImperial()
    val color = metricColor(metric)
    ScreenScaffold(title = metric.label, onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!state.granted) {
                AppCard { NotSharedNote(metric.dataType.label, onShare = { share.request(setOf(metric.dataType)) }) }
                return@Column
            }
            PeriodPicker(state.period, onSelect = viewModel::select)
            val stats = state.stats
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(metricIcon(metric), null, tint = color)
                Spacer(Modifier.width(8.dp))
                Text(stats?.headlineLabel ?: "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val selectedPoint = state.selected?.let { state.points.getOrNull(it) }
            val headline = selectedPoint?.value ?: stats?.headline
            Text(
                when {
                    headline == null -> "No data"
                    metric == HealthMetric.BLOOD_PRESSURE -> state.readings.lastOrNull()?.let { "${it.value.roundToInt()}/${it.secondary?.roundToInt()} mmHg" } ?: "–"
                    else -> MetricFormat.full(metric, headline, imperial)
                },
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            selectedPoint?.let { Text(formatShortDate(it.start.atZone(ZoneId.systemDefault()).toLocalDate()), color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (state.loading) LoadingState(Modifier.height(200.dp)) else Chart(state, viewModel::selectBar, color, imperial)

            if (state.period.days > 30 && !state.history) {
                Text("Share older history in Health Connect to see more than 30 days.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Highlights(state, imperial)
            if (metric == HealthMetric.ENERGY_EATEN) state.macros?.let { (p, c, f) ->
                AppCard {
                    Text("Where the energy came from", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    val ext = BandwagonTheme.extendedColors
                    val parts = listOf(p * 4 to ext.body, c * 4 to MaterialTheme.colorScheme.secondary, f * 9 to ext.nutrition)
                    ShareBar(parts, description = "Protein ${p.roundToInt()} g, carbohydrates ${c.roundToInt()} g, fat ${f.roundToInt()} g")
                    Spacer(Modifier.height(6.dp))
                    Text("Protein ${p.roundToInt()} g · Carbs ${c.roundToInt()} g · Fat ${f.roundToInt()} g", style = MaterialTheme.typography.labelMedium)
                }
            }

            val info = MetricInfos.of(metric)
            SectionHeader("About ${metric.label.lowercase()}")
            AppCard {
                Text(info.what, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(info.how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                info.reference?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.sources.isNotEmpty()) {
                Text("Sources: ${state.sources.joinToString()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Chart(state: MetricDetailState, onSelect: (Int?) -> Unit, color: Color, imperial: Boolean) {
    val metric = state.metric
    val bucket = state.period.bucket
    val points = state.points
    val labels = points.map { label(it, bucket) }
    val every = when {
        points.size > 24 -> points.size / 6
        points.size > 12 -> 3
        else -> 1
    }
    val summary = "${metric.label}, ${state.period.label} view. " + (state.stats?.average?.let { "Average ${MetricFormat.full(metric, it, imperial)}." } ?: "No data.")
    when {
        metric == HealthMetric.BLOOD_PRESSURE -> {
            val ext = BandwagonTheme.extendedColors
            val readings = state.readings.takeLast(30)
            RangeBarChart(
                lows = readings.map { it.secondary },
                highs = readings.map { it.value },
                marks = readings.map { null },
                labels = readings.map { formatShortDate(it.time.atZone(ZoneId.systemDefault()).toLocalDate()) },
                color = color,
                description = "Blood pressure readings: " + readings.lastOrNull()?.let { "latest ${it.value.roundToInt()} over ${it.secondary?.roundToInt()}" }.orEmpty(),
                bands = listOf(0.0..120.0 to ext.successContainer.copy(alpha = 0.5f), 140.0..300.0 to MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                labelEvery = maxOf(1, readings.size / 5),
            )
            readings.lastOrNull()?.let { r ->
                r.secondary?.let { d ->
                    Text("Category: ${HealthMath.bloodPressureCategory(r.value, d).label} (not a diagnosis)", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        metric == HealthMetric.HEART_RATE -> RangeBarChart(
            lows = points.map { it.min },
            highs = points.map { it.max },
            marks = points.map { it.value },
            labels = labels,
            color = color,
            description = summary,
            labelEvery = every,
        )
        metric == HealthMetric.WEIGHT || metric.kind == MetricKind.READING -> {
            val values = points.map { it.value }
            val trend = if (metric == HealthMetric.WEIGHT) {
                val present = values.filterNotNull()
                val smooth = HealthMath.smoothed(present)
                var k = 0
                values.map { v -> if (v == null) null else smooth[k++] }
            } else null
            LineChart(values, labels, color, summary, dotsOnly = true, trend = trend, labelEvery = every,
                band = if (metric == HealthMetric.BLOOD_GLUCOSE) 70.0..180.0 else state.range?.let { it.low..it.high })
            if (metric == HealthMetric.BLOOD_GLUCOSE) {
                HealthMath.timeInRange(state.readings.map { it.value })?.let {
                    Text("Time in range (70–180 mg/dL): ${(it * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        metric.kind == MetricKind.SAMPLE -> LineChart(
            points.map { it.value },
            labels,
            color,
            summary,
            band = state.range?.let { it.low..it.high },
            labelEvery = every,
            zeroCentered = metric == HealthMetric.SKIN_TEMPERATURE,
        )
        else -> BarChart(
            data = points.mapIndexed { i, p -> BarDatum(p.value, labels[i]) },
            color = color,
            description = summary,
            goal = state.goal,
            selected = state.selected,
            onSelect = onSelect,
            labelEvery = every,
        )
    }
    if (state.range != null && metric.kind != MetricKind.CUMULATIVE) {
        Text(
            "Shaded: your usual range, ${MetricFormat.value(metric, state.range.low, imperial)}–${MetricFormat.full(metric, state.range.high, imperial)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Highlights(state: MetricDetailState, imperial: Boolean) {
    val stats = state.stats ?: return
    val metric = state.metric
    val lines = buildList {
        val avg = stats.average
        val base = state.baseline
        if (avg != null && base != null && base > 0) {
            val change = (avg - base) / base * 100
            if (abs(change) >= 3) add("${abs(change).roundToInt()}% ${if (change > 0) "higher" else "lower"} than the period before")
        }
        if (metric == HealthMetric.ACTIVE_MINUTES && state.period == ChartPeriod.WEEK) {
            val total = stats.total?.roundToInt() ?: 0
            add("$total of 150 weekly active minutes (WHO guidance)")
        }
        if (metric.kind == MetricKind.CUMULATIVE && state.period != ChartPeriod.DAY) {
            stats.max?.let { add("Best: ${MetricFormat.full(metric, it, imperial)}") }
            stats.total?.let { if (state.period.bucket == Bucket.DAY) add("Total: ${MetricFormat.full(metric, it, imperial)}") }
        } else if (metric.kind != MetricKind.CUMULATIVE) {
            if (stats.min != null && stats.max != null) add("Range: ${MetricFormat.value(metric, stats.min, imperial)}–${MetricFormat.full(metric, stats.max, imperial)}")
            val latest = state.points.lastOrNull { it.value != null }?.value
            val range = state.range
            if (latest != null && range != null) {
                add(
                    when (range.position(latest)) {
                        fit.bandwagon.core.model.RangePosition.ABOVE -> "Latest is above your usual range"
                        fit.bandwagon.core.model.RangePosition.BELOW -> "Latest is below your usual range"
                        fit.bandwagon.core.model.RangePosition.WITHIN -> "Latest is within your usual range"
                    },
                )
            }
        }
    }
    if (lines.isEmpty()) return
    AppCard {
        Text("Highlights", style = MaterialTheme.typography.titleSmall)
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
fun SleepDetailScreen(onBack: () -> Unit, viewModel: SleepDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val share = rememberShareHealth()
    val ext = BandwagonTheme.extendedColors
    ScreenScaffold(title = "Sleep", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!state.granted) {
                AppCard { NotSharedNote("Sleep", onShare = { share.request(setOf(fit.bandwagon.core.model.HealthDataType.SLEEP)) }) }
                return@Column
            }
            if (state.loading) return@Column LoadingState(Modifier.height(240.dp))
            val last = state.lastNight
            if (last == null) {
                AppCard {
                    Text("No sleep recorded recently", style = MaterialTheme.typography.titleMedium)
                    Text("Sleep appears here when an app or device on this phone writes it to Health Connect.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            val zone = ZoneId.of(last.zoneId)
            Text("${formatDuration(last.asleepMinutes)} asleep", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            val diff = last.asleepMinutes - state.goalMinutes
            Text(
                "${formatTime(last.start.atZone(zone).toLocalTime())} → ${formatTime(last.end.atZone(zone).toLocalTime())} · in bed ${formatDuration(last.inBedMinutes)} · " +
                    if (diff >= 0) "goal met" else "${formatDuration(-diff)} under goal",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (last.hasStages) {
                AppCard {
                    Hypnogram(
                        last.stages.map { StageSpan(it.type, it.start, it.end) },
                        last.start,
                        last.end,
                        description = "Sleep stages for last night",
                    )
                    Spacer(Modifier.height(12.dp))
                    val shares = HealthMath.stageShares(last)
                    ShareBar(
                        listOf(
                            (shares[SleepStageType.DEEP] ?: 0.0) to ext.sleepDeep,
                            (shares[SleepStageType.REM] ?: 0.0) to ext.sleepRem,
                            (shares[SleepStageType.LIGHT] ?: 0.0) to ext.sleepLight,
                        ),
                        description = "Stage shares",
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Deep ${pct(shares[SleepStageType.DEEP])} · REM ${pct(shares[SleepStageType.REM])} · Light ${pct(shares[SleepStageType.LIGHT])} · Awake ${formatDuration(last.awakeMinutes)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    HealthMath.sleepEfficiency(last)?.let { Text("Efficiency ${(it * 100).roundToInt()}% (asleep ÷ in bed)", style = MaterialTheme.typography.labelMedium) }
                    state.sleepingHeartRate?.let { Text("Lowest heart rate while asleep ${it.roundToInt()} bpm", style = MaterialTheme.typography.labelMedium) }
                }
            } else {
                Text("Your device didn't record sleep stages for this night.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            PeriodPicker(state.period, listOf(ChartPeriod.WEEK, ChartPeriod.MONTH, ChartPeriod.SIX_MONTHS), viewModel::select)
            val nights = state.nights.takeLast(state.period.days.toInt())
            val dayLabel = { d: LocalDate -> if (nights.size <= 7) d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) else d.dayOfMonth.toString() }
            val every = if (nights.size > 14) nights.size / 6 else 1
            BarChart(
                data = nights.map { (date, s) ->
                    BarDatum(
                        s.asleepMinutes.toDouble(),
                        dayLabel(date),
                        stacks = if (s.hasStages) listOf(
                            s.stageMinutes(SleepStageType.DEEP).toDouble() to ext.sleepDeep,
                            s.stageMinutes(SleepStageType.REM).toDouble() to ext.sleepRem,
                            s.stageMinutes(SleepStageType.LIGHT).toDouble() + s.stageMinutes(SleepStageType.ASLEEP) to ext.sleepLight,
                        ) else emptyList(),
                    )
                },
                color = ext.sleepDeep,
                description = "Sleep per night, average ${formatDuration(nights.map { it.second.asleepMinutes }.average().takeIf { !it.isNaN() }?.roundToInt() ?: 0)}",
                goal = state.goalMinutes.toDouble(),
                labelEvery = every,
            )

            val week = state.nights.takeLast(7)
            SectionHeader("Consistency")
            val bedtimes = week.map { it.second.start }
            val spread = HealthMath.clockSpreadMinutes(bedtimes, zone)
            val usualBed = HealthMath.meanClockMinutes(bedtimes, zone)
            val usualWake = HealthMath.meanClockMinutes(week.map { it.second.end }, zone)
            AppCard {
                ScheduleChart(
                    nights = week.map { (_, s) -> minutesAfter18(s.start, zone) to minutesAfter18(s.end, zone) },
                    labels = week.map { dayLabel(it.first) },
                    usual = if (usualBed != null && usualWake != null) minutesAfter18Clock(usualBed) to minutesAfter18Clock(usualWake) else null,
                    description = "Bedtimes and wake times for the last ${week.size} nights",
                )
                Spacer(Modifier.height(8.dp))
                usualBed?.let { Text("Usual bedtime ${fit.bandwagon.core.ui.formatClockMinutes(it)}" + (spread?.let { s -> " ± $s min" } ?: ""), style = MaterialTheme.typography.bodyMedium) }
                usualWake?.let { Text("Usual wake time ${fit.bandwagon.core.ui.formatClockMinutes(it)}", style = MaterialTheme.typography.bodyMedium) }
            }
            val debt = HealthMath.sleepDebtMinutes(week.map { it.second.asleepMinutes }, state.goalMinutes)
            AppCard {
                Text("Sleep debt (7 nights)", style = MaterialTheme.typography.titleSmall)
                Text(if (debt == 0) "None 🎉" else formatDuration(debt), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Minutes short of your goal, added up. Extra sleep doesn't count against it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppCard {
                Text(MetricInfos.of(HealthMetric.SLEEP).how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(MetricInfos.of(HealthMetric.SLEEP).reference.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun pct(v: Double?) = "${((v ?: 0.0) * 100).roundToInt()}%"

private fun minutesAfter18(t: Instant, zone: ZoneId): Int {
    val local = t.atZone(zone).toLocalTime()
    return minutesAfter18Clock(local.hour * 60 + local.minute)
}

private fun minutesAfter18Clock(clock: Int): Int = ((clock - 18 * 60) + 1440) % 1440

@Composable
fun WorkoutsScreen(onBack: () -> Unit, viewModel: WorkoutsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Workouts", onBack = onBack) {
        if (state.loading) return@ScreenScaffold LoadingState()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.workouts.isEmpty()) Text("No workouts in the last 30 days.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val byWeek = state.workouts.groupBy { it.start.atZone(ZoneId.systemDefault()).toLocalDate().with(java.time.DayOfWeek.MONDAY) }
            byWeek.forEach { (week, list) ->
                SectionHeader("Week of ${formatShortDate(week)} · ${list.size} · ${formatDuration(list.sumOf { it.durationMinutes })}")
                list.forEach { w ->
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(metricIcon(HealthMetric.WORKOUT_MINUTES), null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(w.title ?: w.type.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(
                                        formatShortDate(w.start.atZone(ZoneId.systemDefault()).toLocalDate()),
                                        formatDuration(w.durationMinutes),
                                        w.distanceMeters?.takeIf { it > 0 }?.let { formatDistance(it) },
                                        w.activeCalories?.takeIf { it > 0 }?.let { "${formatNumber(it)} kcal" },
                                        w.avgHeartRate?.let { "avg $it bpm" },
                                        "✎ entered by hand".takeIf { w.recordingMethod == fit.bandwagon.core.model.RecordingMethod.MANUAL },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

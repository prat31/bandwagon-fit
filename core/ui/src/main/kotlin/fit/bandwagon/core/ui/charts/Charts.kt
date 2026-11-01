package fit.bandwagon.core.ui.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fit.bandwagon.core.model.SleepStageType
import fit.bandwagon.core.ui.BandwagonTheme
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/*
 * The chart kit. Every chart takes colours from the theme, draws on Canvas, and carries a TalkBack
 * summary passed by the caller, so charts never rely on colour or shape alone.
 */

/** One-time grow-in animation that respects "remove animations". */
@Composable
private fun rememberGrow(key: Any?): Float {
    val context = LocalContext.current
    val animate = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val progress = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key) { if (animate) progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    return progress.value
}

data class Ring(val progress: Float, val color: Color, val label: String)

/** Concentric activity rings. A closed ring (progress ≥ 1) keeps its colour and gets a full sweep. */
@Composable
fun ActivityRings(rings: List<Ring>, modifier: Modifier = Modifier, size: Dp = 132.dp, stroke: Dp = 14.dp, description: String) {
    val track = BandwagonTheme.extendedColors.track
    val grow = rememberGrow(rings.map { it.progress })
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        val strokePx = stroke.toPx()
        val gap = strokePx * 0.35f
        rings.forEachIndexed { index, ring ->
            val inset = strokePx / 2 + index * (strokePx + gap)
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            if (arcSize.width <= 0) return@forEachIndexed
            val topLeft = Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(strokePx))
            val sweep = 360f * min(ring.progress.coerceAtLeast(0f), 1f) * grow
            if (sweep > 0f) {
                drawArc(ring.color, -90f, sweep, false, topLeft, arcSize, style = Stroke(strokePx, cap = StrokeCap.Round))
            }
        }
    }
}

/** A small trend line for tiles. Nulls are gaps. */
@Composable
fun Sparkline(values: List<Double?>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.height(28.dp).fillMaxWidth()) {
        val present = values.filterNotNull()
        if (present.size < 2) return@Canvas
        val lo = present.min()
        val hi = present.max().let { if (it == lo) lo + 1 else it }
        val step = size.width / (values.size - 1).coerceAtLeast(1)
        var path: Path? = null
        values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = i * step
            val y = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
            path = (path ?: Path().apply { moveTo(x, y) }).apply { lineTo(x, y) }
        }
        path?.let { drawPath(it, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
        val last = values.indexOfLast { it != null }
        if (last >= 0) {
            val v = values[last]!!
            drawCircle(color, 3.dp.toPx(), Offset(last * step, size.height - ((v - lo) / (hi - lo)).toFloat() * size.height))
        }
    }
}

/** Mini bars for tiles (7 days), today highlighted. */
@Composable
fun MiniBars(values: List<Double?>, color: Color, modifier: Modifier = Modifier, goal: Double? = null) {
    val track = BandwagonTheme.extendedColors.track
    Canvas(modifier.height(28.dp).fillMaxWidth()) {
        val hi = max(values.filterNotNull().maxOrNull() ?: 0.0, goal ?: 0.0).takeIf { it > 0 } ?: return@Canvas
        val slot = size.width / values.size
        val barWidth = slot * 0.6f
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - barWidth) / 2
            drawRoundRect(track, Offset(x, 0f), Size(barWidth, size.height), CornerRadius(barWidth / 2))
            if (v != null && v > 0) {
                val h = (v / hi).toFloat() * size.height
                val alpha = if (i == values.lastIndex) 1f else 0.55f
                drawRoundRect(color.copy(alpha = alpha), Offset(x, size.height - h), Size(barWidth, h), CornerRadius(barWidth / 2))
            }
        }
    }
}

data class BarDatum(val value: Double?, val label: String, val stacks: List<Pair<Double, Color>> = emptyList())

/**
 * Bar chart with an optional dashed goal line and tap-to-select. Stacked bars (sleep stages,
 * moderate/vigorous minutes) pass [BarDatum.stacks] instead of a single value.
 */
@Composable
fun BarChart(
    data: List<BarDatum>,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    goal: Double? = null,
    selected: Int? = null,
    onSelect: (Int?) -> Unit = {},
    height: Dp = 180.dp,
    labelEvery: Int = 1,
) {
    val grow = rememberGrow(data.map { it.value })
    val outline = MaterialTheme.colorScheme.outlineVariant
    val goalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val success = BandwagonTheme.extendedColors.success
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(data) {
                detectTapGestures { offset ->
                    val slot = size.width / data.size.coerceAtLeast(1)
                    val index = (offset.x / slot).toInt().coerceIn(0, data.lastIndex)
                    onSelect(if (index == selected) null else index)
                }
            },
        ) {
            val totals = data.map { d -> if (d.stacks.isNotEmpty()) d.stacks.sumOf { it.first } else d.value ?: 0.0 }
            val hi = max(totals.maxOrNull() ?: 0.0, goal ?: 0.0).takeIf { it > 0 } ?: 1.0
            val top = hi * 1.1
            val slot = size.width / data.size.coerceAtLeast(1)
            val barWidth = (slot * 0.62f).coerceAtMost(28.dp.toPx())
            drawLine(outline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            data.forEachIndexed { i, d ->
                val x = i * slot + (slot - barWidth) / 2
                val dim = selected != null && selected != i
                if (d.stacks.isNotEmpty()) {
                    var y = size.height
                    d.stacks.forEach { (v, c) ->
                        val h = (v / top).toFloat() * size.height * grow
                        drawRect(c.copy(alpha = if (dim) 0.35f else 1f), Offset(x, y - h), Size(barWidth, h))
                        y -= h
                    }
                } else if ((d.value ?: 0.0) > 0) {
                    val v = d.value!!
                    val h = (v / top).toFloat() * size.height * grow
                    val fill = if (goal != null && v >= goal) success else color
                    drawRoundRect(
                        fill.copy(alpha = if (dim) 0.35f else 1f),
                        Offset(x, size.height - h),
                        Size(barWidth, h),
                        CornerRadius(min(barWidth / 2, 6.dp.toPx())),
                    )
                }
            }
            if (goal != null) {
                val y = size.height - (goal / top).toFloat() * size.height
                drawLine(goalColor, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
        AxisLabels(data.map { it.label }, labelEvery)
    }
}

@Composable
fun AxisLabels(labels: List<String>, every: Int = 1) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        labels.forEachIndexed { i, label ->
            Text(
                if (i % every == 0) label else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Line chart with an optional personal-range band (mean ± 1 SD) and an optional second, smoother
 * line (weight trend, 7-day average). Raw points are dots; nulls are gaps.
 */
@Composable
fun LineChart(
    values: List<Double?>,
    labels: List<String>,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    band: ClosedFloatingPointRange<Double>? = null,
    trend: List<Double?>? = null,
    dotsOnly: Boolean = false,
    height: Dp = 180.dp,
    labelEvery: Int = 1,
    zeroCentered: Boolean = false,
) {
    val bandFill = BandwagonTheme.extendedColors.bandFill
    val outline = MaterialTheme.colorScheme.outlineVariant
    val trendColor = MaterialTheme.colorScheme.onSurface
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val all = values.filterNotNull() + (trend?.filterNotNull() ?: emptyList()) + listOfNotNull(band?.start, band?.endInclusive)
            if (all.isEmpty()) return@Canvas
            var lo = all.min()
            var hi = all.max()
            if (zeroCentered) {
                val m = max(kotlin.math.abs(lo), kotlin.math.abs(hi)).coerceAtLeast(0.5)
                lo = -m; hi = m
            }
            val pad = ((hi - lo) * 0.15).coerceAtLeast(1e-6)
            lo -= pad; hi += pad
            fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
            val step = if (values.size > 1) size.width / (values.size - 1) else size.width
            fun x(i: Int) = if (values.size > 1) i * step else size.width / 2

            if (band != null) {
                drawRect(bandFill, Offset(0f, y(band.endInclusive)), Size(size.width, y(band.start) - y(band.endInclusive)))
            }
            if (zeroCentered) drawLine(outline, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx())
            drawLine(outline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())

            if (!dotsOnly) {
                var path: Path? = null
                values.forEachIndexed { i, v ->
                    if (v == null) { path?.let { drawPath(it, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)) }; path = null; return@forEachIndexed }
                    path = (path ?: Path().apply { moveTo(x(i), y(v)) }).apply { lineTo(x(i), y(v)) }
                }
                path?.let { drawPath(it, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)) }
            }
            values.forEachIndexed { i, v -> if (v != null) drawCircle(color, if (dotsOnly) 3.5.dp.toPx() else 2.5.dp.toPx(), Offset(x(i), y(v))) }
            trend?.let { t ->
                var path: Path? = null
                t.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    path = (path ?: Path().apply { moveTo(x(i), y(v)) }).apply { lineTo(x(i), y(v)) }
                }
                path?.let { drawPath(it, trendColor.copy(alpha = 0.8f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
            }
        }
        AxisLabels(labels, labelEvery)
    }
}

/**
 * Range bars: a vertical bar from min to max with a dot at the average (heart rate per day), or
 * paired values such as diastolic→systolic blood pressure.
 */
@Composable
fun RangeBarChart(
    lows: List<Double?>,
    highs: List<Double?>,
    marks: List<Double?>,
    labels: List<String>,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    bands: List<Pair<ClosedFloatingPointRange<Double>, Color>> = emptyList(),
    height: Dp = 180.dp,
    labelEvery: Int = 1,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val markColor = MaterialTheme.colorScheme.onSurface
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val all = lows.filterNotNull() + highs.filterNotNull()
            if (all.isEmpty()) return@Canvas
            val pad = ((all.max() - all.min()) * 0.15).coerceAtLeast(2.0)
            val lo = all.min() - pad
            val hi = all.max() + pad
            fun y(v: Double) = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
            bands.forEach { (range, c) ->
                val top = y(min(range.endInclusive, hi))
                val bottom = y(max(range.start, lo))
                if (bottom > top) drawRect(c, Offset(0f, top), Size(size.width, bottom - top))
            }
            drawLine(outline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            val slot = size.width / lows.size.coerceAtLeast(1)
            val w = (slot * 0.4f).coerceAtMost(10.dp.toPx())
            lows.indices.forEach { i ->
                val l = lows[i] ?: return@forEach
                val h = highs[i] ?: l
                val cx = i * slot + slot / 2
                drawRoundRect(color, Offset(cx - w / 2, y(h)), Size(w, max(y(l) - y(h), w)), CornerRadius(w / 2))
                marks.getOrNull(i)?.let { drawCircle(markColor, 2.5.dp.toPx(), Offset(cx, y(it))) }
            }
        }
        AxisLabels(labels, labelEvery)
    }
}

data class StageSpan(val type: SleepStageType, val start: Instant, val end: Instant)

/** Sleep stages on four lanes (Awake, REM, Light, Deep), joined by a stepped outline. */
@Composable
fun Hypnogram(stages: List<StageSpan>, start: Instant, end: Instant, description: String, modifier: Modifier = Modifier, height: Dp = 140.dp) {
    val ext = BandwagonTheme.extendedColors
    val lanes = listOf(SleepStageType.AWAKE, SleepStageType.REM, SleepStageType.LIGHT, SleepStageType.DEEP)
    val colors = mapOf(
        SleepStageType.AWAKE to ext.sleepAwake,
        SleepStageType.OUT_OF_BED to ext.sleepAwake,
        SleepStageType.REM to ext.sleepRem,
        SleepStageType.LIGHT to ext.sleepLight,
        SleepStageType.DEEP to ext.sleepDeep,
        SleepStageType.ASLEEP to ext.sleepLight,
    )
    val outline = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.height(height).padding(end = 6.dp)) {
            listOf("Awake", "REM", "Light", "Deep").forEach {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = labelColor)
                }
            }
        }
        Canvas(Modifier.weight(1f).height(height)) {
            val total = (end.toEpochMilli() - start.toEpochMilli()).toFloat().coerceAtLeast(1f)
            val laneH = size.height / lanes.size
            lanes.indices.forEach { i ->
                drawLine(outline, Offset(0f, (i + 0.5f) * laneH), Offset(size.width, (i + 0.5f) * laneH), 0.5.dp.toPx())
            }
            stages.forEach { s ->
                val lane = when (s.type) {
                    SleepStageType.AWAKE, SleepStageType.OUT_OF_BED -> 0
                    SleepStageType.REM -> 1
                    SleepStageType.DEEP -> 3
                    else -> 2
                }
                val x0 = (s.start.toEpochMilli() - start.toEpochMilli()) / total * size.width
                val x1 = (s.end.toEpochMilli() - start.toEpochMilli()) / total * size.width
                drawRoundRect(
                    colors.getValue(s.type),
                    Offset(x0, lane * laneH + laneH * 0.2f),
                    Size(max(x1 - x0, 1.5f), laneH * 0.6f),
                    CornerRadius(3.dp.toPx()),
                )
            }
        }
    }
}

/** A horizontal 100% bar, e.g. stage shares or macro split. */
@Composable
fun ShareBar(parts: List<Pair<Double, Color>>, modifier: Modifier = Modifier, description: String) {
    val track = BandwagonTheme.extendedColors.track
    Canvas(modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = description }) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val total = parts.sumOf { it.first }.takeIf { it > 0 } ?: return@Canvas
        var x = 0f
        parts.forEach { (v, c) ->
            val w = (v / total).toFloat() * size.width
            drawRect(c, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

/**
 * Sleep schedule: one floating bar per night from bedtime to wake, on a 18:00→12:00 axis, with
 * the usual window shaded. Values are minutes after 18:00.
 */
@Composable
fun ScheduleChart(
    nights: List<Pair<Int, Int>?>,
    labels: List<String>,
    usual: Pair<Int, Int>?,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
) {
    val color = BandwagonTheme.extendedColors.sleepDeep
    val band = BandwagonTheme.extendedColors.bandFill
    Column(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val span = 18 * 60f
            fun y(m: Int) = (m / span) * size.height
            usual?.let { (a, b) -> drawRect(band, Offset(0f, y(a)), Size(size.width, y(b) - y(a))) }
            val slot = size.width / nights.size.coerceAtLeast(1)
            val w = (slot * 0.5f).coerceAtMost(14.dp.toPx())
            nights.forEachIndexed { i, n ->
                n ?: return@forEachIndexed
                val cx = i * slot + slot / 2
                drawRoundRect(color, Offset(cx - w / 2, y(n.first)), Size(w, y(n.second) - y(n.first)), CornerRadius(w / 2))
            }
        }
        AxisLabels(labels)
    }
}

/** A thick progress bar used by team goals and boss battles. */
@Composable
fun ProgressTrack(progress: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 16.dp, description: String) {
    val track = BandwagonTheme.extendedColors.track
    val grow = rememberGrow(progress)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val w = size.width * progress.coerceIn(0f, 1f) * grow
        if (w > 0) drawRoundRect(color, size = Size(max(w, size.height), size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}

/**
 * A progress bar whose fill shows how far along the day is: red while little is done, blending to
 * yellow through the middle third and to green over the last third. The gradient spans the whole
 * track, so the tip's colour says where you are.
 */
@Composable
fun GradientProgressTrack(progress: Float, modifier: Modifier = Modifier, height: Dp = 10.dp, description: String) {
    val track = BandwagonTheme.extendedColors.track
    val low = MaterialTheme.colorScheme.error
    val mid = BandwagonTheme.extendedColors.highlight
    val high = BandwagonTheme.extendedColors.success
    val grow = rememberGrow(progress)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = description }) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        val w = size.width * progress.coerceIn(0f, 1f) * grow
        if (w <= 0) return@Canvas
        val brush = Brush.horizontalGradient(0f to low, 1f / 3 to low, 2f / 3 to mid, 1f to high, startX = 0f, endX = size.width)
        drawRoundRect(brush, size = Size(max(w, size.height), size.height), cornerRadius = radius)
    }
}

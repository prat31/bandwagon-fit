package fit.bandwagon.core.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A personal normal range: mean ± one standard deviation of the user's own recent values. */
data class PersonalRange(val mean: Double, val low: Double, val high: Double, val samples: Int) {
    fun position(value: Double): RangePosition = when {
        value < low -> RangePosition.BELOW
        value > high -> RangePosition.ABOVE
        else -> RangePosition.WITHIN
    }
}

enum class RangePosition { BELOW, WITHIN, ABOVE }

/** A value recorded over a time interval (start == end for a point reading). */
data class TimedValue(val interval: Pair<Instant, Instant>, val value: Double)

enum class BloodPressureCategory(val label: String) {
    NORMAL("Normal"),
    ELEVATED("Elevated"),
    STAGE_1("High, stage 1"),
    STAGE_2("High, stage 2"),
    SEVERE("Very high"),
}

/**
 * Calculations behind the Health tab. Standard, explainable values; the two composite scores
 * live in `scores/DailyScores.kt` (decision D2). Pure Kotlin so it is unit tested and reusable on
 * another platform.
 */
object HealthMath {
    /** A band needs this many values before it means anything (two weeks of nights for HRV). */
    const val MIN_RANGE_SAMPLES = 14

    fun personalRange(values: List<Double>, minSamples: Int = MIN_RANGE_SAMPLES): PersonalRange? {
        if (values.size < minSamples) return null
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        return PersonalRange(mean, mean - sd, mean + sd, values.size)
    }

    /**
     * Weight trend: exponentially smoothed average, which removes day-to-day water swings.
     * α = 0.1 is the classic "trend weight" setting.
     */
    fun smoothed(values: List<Double>, alpha: Double = 0.1): List<Double> {
        if (values.isEmpty()) return emptyList()
        val out = ArrayList<Double>(values.size)
        var trend = values.first()
        for (value in values) {
            trend += alpha * (value - trend)
            out += trend
        }
        return out
    }

    /** Percentage change of [current] versus [baseline]; null when there is no baseline. */
    fun changePercent(current: Double, baseline: Double?): Double? =
        if (baseline == null || baseline == 0.0) null else (current - baseline) / baseline * 100

    /** Asleep ÷ in bed. Only meaningful when the source wrote awake stages. */
    fun sleepEfficiency(session: SleepSession): Double? =
        if (!session.hasStages || session.inBedMinutes == 0) null
        else session.asleepMinutes.toDouble() / session.inBedMinutes

    /** Share of asleep time in each stage, only when real stages exist. */
    fun stageShares(session: SleepSession): Map<SleepStageType, Double> {
        if (!session.hasStages || session.asleepMinutes == 0) return emptyMap()
        return listOf(SleepStageType.DEEP, SleepStageType.REM, SleepStageType.LIGHT).associateWith {
            session.stageMinutes(it).toDouble() / session.asleepMinutes
        }
    }

    /**
     * Spread of clock times around midnight, in minutes. Uses a circular mean so 23:50 and 00:10
     * are 20 minutes apart, not 23 hours.
     */
    fun clockSpreadMinutes(times: List<Instant>, zone: ZoneId): Int? {
        if (times.size < 2) return null
        val angles = times.map {
            val local = it.atZone(zone).toLocalTime()
            (local.hour * 60 + local.minute) / 1440.0 * 2 * Math.PI
        }
        val meanSin = angles.map(::sin).average()
        val meanCos = angles.map(::cos).average()
        val mean = atan2(meanSin, meanCos)
        val deviations = angles.map {
            var d = it - mean
            while (d > Math.PI) d -= 2 * Math.PI
            while (d < -Math.PI) d += 2 * Math.PI
            d / (2 * Math.PI) * 1440
        }
        return sqrt(deviations.sumOf { it * it } / deviations.size).roundToInt()
    }

    /** Circular mean clock time in minutes after midnight, for "usual bedtime". */
    fun meanClockMinutes(times: List<Instant>, zone: ZoneId): Int? {
        if (times.isEmpty()) return null
        val angles = times.map {
            val local = it.atZone(zone).toLocalTime()
            (local.hour * 60 + local.minute) / 1440.0 * 2 * Math.PI
        }
        var mean = atan2(angles.map(::sin).average(), angles.map(::cos).average())
        if (mean < 0) mean += 2 * Math.PI
        return (mean / (2 * Math.PI) * 1440).roundToInt() % 1440
    }

    /** Sleep debt: minutes short of the goal, summed over the given nights. Extra sleep doesn't repay it. */
    fun sleepDebtMinutes(asleepMinutes: List<Int>, goalMinutes: Int): Int =
        asleepMinutes.sumOf { (goalMinutes - it).coerceAtLeast(0) }

    /**
     * Home's "Recovery" figure (0–100): how today's resting heart rate and HRV sit against your own
     * recent baseline (mean ± 1 SD). 50 = your usual; a lower resting HR and a higher HRV score higher.
     * Returns null when neither metric has a usable baseline. Explainable and self-referential — the UI
     * labels it "vs your usual", never a readiness score.
     */
    fun recoveryScore(
        restingHeartRate: Double?,
        rhrRange: PersonalRange?,
        hrv: Double?,
        hrvRange: PersonalRange?,
    ): Int? {
        var parts = 0
        var total = 0.0
        restingHeartRate?.let { v -> rhrRange?.let { b ->
            total += recoveryComponent(v, b.mean, b.high - b.mean, higherIsBetter = false)
            parts++
        } }
        hrv?.let { v -> hrvRange?.let { b ->
            total += recoveryComponent(v, b.mean, b.mean - b.low, higherIsBetter = true)
            parts++
        } }
        if (parts == 0) return null
        return (total / parts).roundToInt().coerceIn(0, 100)
    }

    /** Maps a value to 0–100 against a mean and one standard deviation, where 50 is "your usual". */
    private fun recoveryComponent(value: Double, mean: Double, sd: Double, higherIsBetter: Boolean): Double {
        if (sd <= 0) return 50.0
        val t = if (higherIsBetter) (value - mean) / sd else (mean - value) / sd
        return (50 + 50 * t).coerceIn(0.0, 100.0)
    }

    /** AHA adult categories. Informational only; the UI says it isn't a diagnosis. */
    fun bloodPressureCategory(systolic: Double, diastolic: Double): BloodPressureCategory = when {
        systolic > 180 || diastolic > 120 -> BloodPressureCategory.SEVERE
        systolic >= 140 || diastolic >= 90 -> BloodPressureCategory.STAGE_2
        systolic >= 130 || diastolic >= 80 -> BloodPressureCategory.STAGE_1
        systolic >= 120 -> BloodPressureCategory.ELEVATED
        else -> BloodPressureCategory.NORMAL
    }

    /** Share of glucose readings in 70–180 mg/dL, the consensus target band for continuous monitors. */
    fun timeInRange(readingsMgDl: List<Double>, low: Double = 70.0, high: Double = 180.0): Double? =
        if (readingsMgDl.isEmpty()) null else readingsMgDl.count { it in low..high }.toDouble() / readingsMgDl.size

    /**
     * Samples from several devices disagree, so each day uses the single origin that wrote the most
     * samples. Returns that origin, or null when there are none.
     */
    fun <T> dominantOrigin(samples: List<T>, origin: (T) -> String): String? =
        samples.groupingBy(origin).eachCount().maxByOrNull { it.value }?.key

    /**
     * Sum of [values] with overlaps counted once, the way Health Connect's aggregate does for
     * duplicates: records whose intervals overlap form one cluster, and a cluster counts its
     * largest value. Used to take manual entries out of a deduplicated total without taking a
     * duplicated entry away twice.
     */
    fun dedupedTotal(values: List<TimedValue>): Double {
        var total = 0.0
        var cluster: Pair<Instant, Instant>? = null
        var clusterMax = 0.0
        for (v in values.sortedBy { it.interval.first }) {
            val (start, end) = v.interval
            val current = cluster
            // Overlapping, or the very same interval (which also covers duplicate point readings).
            val joins = current != null && (start.isBefore(current.second) || v.interval == current)
            if (joins) {
                clusterMax = maxOf(clusterMax, v.value)
                if (end.isAfter(current!!.second)) cluster = current.first to end
            } else {
                total += clusterMax
                cluster = v.interval
                clusterMax = v.value
            }
        }
        return total + clusterMax
    }

    fun bmi(weightKg: Double, heightMeters: Double): Double? =
        if (heightMeters <= 0) null else weightKg / (heightMeters * heightMeters)

    /** WHO weekly target progress, counting vigorous minutes double when the source splits them. */
    fun weightedActiveMinutes(moderate: Int, vigorous: Int): Int = moderate + 2 * vigorous

    fun minutesBetween(start: Instant, end: Instant): Int = Duration.between(start, end).toMinutes().toInt()
}

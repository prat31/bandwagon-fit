package fit.bandwagon.core.model.challenge

import fit.bandwagon.core.model.DailySummary
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

data class MemberResult(
    val score: Double,
    val tieBreak: Double,
    val progress: Double?,
    val daysHit: Int,
    val daysWithData: Int,
    val longestStreak: Int,
    val finished: Boolean,
    val finishedOn: String?,
    val notShared: Boolean,
    val flagged: Boolean,
)

data class DayOutcome(val date: String, val value: Double?, val status: DayStatus?)

data class MemberEvaluation(val result: MemberResult, val days: List<DayOutcome>)

/**
 * Kotlin port of functions/src/domain/engine.ts. Both run the golden fixtures in /shared, so they
 * cannot drift. The server is authoritative; the app uses this for previews in the create wizard.
 */
object ChallengeEngine {
    const val MAX_WINDOW_DAYS = 366
    val MILESTONES = listOf(25, 50, 75, 100)

    private val CAPS = mapOf(
        "steps" to 100_000.0,
        "distance" to 150_000.0,
        "active_calories" to 8_000.0,
        "active_minutes" to 900.0,
        "workout_minutes" to 900.0,
        "workout_count" to 20.0,
        "floors" to 500.0,
        "sleep_minutes" to 16 * 60.0,
        "mindful_minutes" to 600.0,
    )

    fun requiredMetrics(spec: ChallengeSpec): List<String> =
        if (spec.daily.op == "all") spec.daily.conditions.orEmpty().map { it.metric }.distinct()
        else listOf(spec.metric.id)

    /** `shared` holds metric ids; the clock metrics come with sleep. */
    private fun sharedKey(id: String) = if (id == "bedtime" || id == "wake_time") "sleep_minutes" else id

    fun countedDates(spec: ChallengeSpec): List<LocalDate> {
        val days = spec.window.days?.takeIf { it.isNotEmpty() }?.toSet()
        val out = mutableListOf<LocalDate>()
        var date = spec.window.startDate
        while (!date.isAfter(spec.window.endDate)) {
            if (days == null || date.dayOfWeek.toSpecDay() in days) out += date
            date = date.plusDays(1)
        }
        return out
    }

    /** Minutes from local midnight, wrapped to [-720, 720) so 23:30 is -30 and 00:30 is 30. */
    fun wrappedClockMinutes(instant: java.time.Instant, zoneId: String): Int {
        val local = instant.atZone(ZoneId.of(zoneId)).toLocalTime()
        val minutes = local.hour * 60 + local.minute
        return if (minutes >= 720) minutes - 1440 else minutes
    }

    private fun workoutTypeSum(daily: DailySummary, types: List<String>?, pick: (fit.bandwagon.core.model.WorkoutTypeTotal) -> Double?): Double? {
        if (types.isNullOrEmpty()) return null
        val wanted = types.map { it.uppercase() }.toSet()
        return daily.workoutsByType.entries.filter { it.key.uppercase() in wanted }.sumOf { pick(it.value) ?: 0.0 }
    }

    fun extract(id: String, daily: DailySummary, filter: MetricFilter? = null): Double? = when (id) {
        "steps" -> daily.steps?.toDouble()
        "distance" -> if (!filter?.workoutTypes.isNullOrEmpty()) workoutTypeSum(daily, filter?.workoutTypes) { it.distanceMeters } else daily.distanceMeters
        "active_calories" -> daily.activeCalories
        "active_minutes" -> daily.activeMinutes?.toDouble()
        "workout_minutes" -> if (!filter?.workoutTypes.isNullOrEmpty()) workoutTypeSum(daily, filter?.workoutTypes) { it.minutes.toDouble() } else daily.workoutMinutes?.toDouble()
        "workout_count" -> if (!filter?.workoutTypes.isNullOrEmpty()) workoutTypeSum(daily, filter?.workoutTypes) { it.count.toDouble() } else daily.workoutCount?.toDouble()
        "floors" -> daily.floors
        "sleep_minutes" -> daily.sleep?.durationMinutes?.toDouble()
        "bedtime" -> daily.sleep?.let { wrappedClockMinutes(it.start, it.zoneId).toDouble() }
        "wake_time" -> daily.sleep?.let {
            val local = it.end.atZone(ZoneId.of(it.zoneId)).toLocalTime()
            (local.hour * 60 + local.minute).toDouble()
        }
        "mindful_minutes" -> daily.mindfulMinutes?.toDouble()
        else -> null
    }

    private fun passes(c: Condition, value: Double?): Boolean {
        if (value == null) return false
        val tolerance = c.toleranceMin ?: 0.0
        return if (c.cmp == "<=") value <= c.value + tolerance else value >= c.value - tolerance
    }

    private data class DayValue(
        val value: Double?,
        val raw: Double?,
        val hit: Boolean,
        val shared: Boolean,
        val flagged: Boolean,
    )

    private fun evaluateDay(spec: ChallengeSpec, daily: DailySummary?): DayValue {
        val empty = DayValue(null, null, hit = false, shared = true, flagged = false)
        if (daily == null) return empty
        if (!requiredMetrics(spec).all { sharedKey(it) in daily.shared }) return empty.copy(shared = false)

        fun read(id: String): Pair<Double?, Boolean> {
            val filter = if (id == spec.metric.id) spec.metric.filter else null
            val value = extract(id, daily, filter) ?: return null to false
            val cap = CAPS[id]
            return if (cap != null && value > cap) null to true else value to false
        }

        if (spec.daily.op == "all") {
            val results = spec.daily.conditions.orEmpty().map { it to read(it.metric) }
            val flagged = results.any { it.second.second }
            if (results.all { it.second.first == null }) return empty.copy(flagged = flagged)
            val hit = results.all { (c, r) -> passes(c, r.first) }
            val v = if (hit) 1.0 else 0.0
            return DayValue(v, v, hit, shared = true, flagged = flagged)
        }

        val (raw, flagged) = read(spec.metric.id)
        if (raw == null) return empty.copy(flagged = flagged)
        return when (spec.daily.op) {
            "cap" -> DayValue(min(raw, spec.daily.max ?: raw), raw, raw > 0, true, false)
            "atLeast" -> {
                val hit = raw >= (spec.daily.value ?: 0.0)
                DayValue(if (hit) 1.0 else 0.0, raw, hit, true, false)
            }
            else -> DayValue(raw, raw, raw > 0, true, false)
        }
    }

    fun evaluateMember(spec: ChallengeSpec, summaries: List<DailySummary>): MemberEvaluation {
        val byDate = summaries.associateBy { it.date }
        val dates = countedDates(spec)
        val values = dates.map { it to evaluateDay(spec, byDate[it]) }
        val withData = values.filter { it.second.value != null }
        val anySummary = dates.any { it in byDate }
        val notShared = anySummary && values.all { (date, v) -> !v.shared || date !in byDate }
        val hits = values.map { it.second.hit }

        val score = when (spec.aggregate) {
            Aggregate.SUM -> withData.sumOf { it.second.value ?: 0.0 }
            Aggregate.AVERAGE -> if (withData.isEmpty()) 0.0 else withData.sumOf { it.second.value ?: 0.0 } / withData.size
            Aggregate.DAYS_HIT -> hits.count { it }.toDouble()
            Aggregate.BEST_DAY -> withData.maxOfOrNull { it.second.value ?: 0.0 } ?: 0.0
            Aggregate.LONGEST_STREAK -> longestRun(hits).toDouble()
        }

        val target = spec.goal?.target
        val finishedOn = if (spec.kind == ChallengeKind.TARGET && target != null && target > 0) {
            firstDateReaching(spec, values.map { Triple(it.first, it.second.value, it.second.hit) }, target)
        } else null

        val tieBreak = when (spec.ranking?.tieBreak) {
            "SUM" -> withData.sumOf { it.second.raw ?: 0.0 }
            "EARLIEST_FINISH" -> if (finishedOn != null) -dates.indexOf(LocalDate.parse(finishedOn)).toDouble() else -MAX_WINDOW_DAYS.toDouble()
            else -> 0.0
        }

        val result = MemberResult(
            score = round(score),
            tieBreak = tieBreak,
            progress = if (spec.kind == ChallengeKind.TARGET && target != null && target > 0) min(1.0, score / target) else null,
            daysHit = hits.count { it },
            daysWithData = withData.size,
            longestStreak = longestRun(hits),
            finished = finishedOn != null,
            finishedOn = finishedOn,
            notShared = notShared,
            flagged = values.any { it.second.flagged },
        )
        val days = values.map { (date, v) ->
            val status = when {
                date !in byDate -> null
                !v.shared -> DayStatus.NOT_SHARED
                v.value == null -> null
                v.hit -> DayStatus.HIT
                else -> DayStatus.MISSED
            }
            DayOutcome(date.toString(), v.value, status)
        }
        return MemberEvaluation(result, days)
    }

    private fun round(value: Double): Double = (value * 100).roundToLong() / 100.0

    private fun longestRun(hits: List<Boolean>): Int {
        var best = 0
        var run = 0
        for (hit in hits) {
            run = if (hit) run + 1 else 0
            best = max(best, run)
        }
        return best
    }

    private fun firstDateReaching(spec: ChallengeSpec, values: List<Triple<LocalDate, Double?, Boolean>>, target: Double): String? {
        var running = 0.0
        var run = 0
        var best = 0
        for ((date, value, hit) in values) {
            when (spec.aggregate) {
                Aggregate.SUM -> running += value ?: 0.0
                Aggregate.DAYS_HIT -> running += if (hit) 1.0 else 0.0
                Aggregate.BEST_DAY -> running = max(running, value ?: 0.0)
                Aggregate.LONGEST_STREAK -> {
                    run = if (hit) run + 1 else 0
                    best = max(best, run)
                    running = best.toDouble()
                }
                Aggregate.AVERAGE -> return null
            }
            if (running >= target) return date.toString()
        }
        return null
    }

    data class Ranked(val memberId: String, val score: Double, val tieBreak: Double, val rank: Int)

    /** Competition ranking: equal score and tie-break share a rank ("1, 2, 2, 4"). */
    fun rank(entries: List<Triple<String, Double, Double>>): List<Ranked> {
        val sorted = entries.sortedWith(
            compareByDescending<Triple<String, Double, Double>> { it.second }
                .thenByDescending { it.third }
                .thenBy { it.first },
        )
        val out = mutableListOf<Ranked>()
        sorted.forEachIndexed { index, (id, score, tie) ->
            val previous = out.lastOrNull()
            val tied = previous != null && previous.score == score && previous.tieBreak == tie
            out += Ranked(id, score, tie, if (tied) previous!!.rank else index + 1)
        }
        return out
    }

    fun groupKept(day: DayCounts): Boolean {
        val withData = day.hit + day.missed
        return withData > 0 && withData * 2 >= day.members && day.missed == 0
    }

    /** Mirrors validateSpec in engine.ts for the fields the wizard can get wrong. */
    fun problem(spec: ChallengeSpec): String? {
        if (spec.display.title.isBlank() || spec.display.title.trim().length > 60) return "Give the challenge a name up to 60 characters."
        if (spec.kind != ChallengeKind.COMPETE && (spec.goal == null || spec.goal.target <= 0)) return "Set a goal above zero."
        if (spec.kind == ChallengeKind.TEAM && spec.aggregate !in setOf(Aggregate.SUM, Aggregate.DAYS_HIT)) return "Team goals add up totals or days."
        if (spec.window.endDate.isBefore(spec.window.startDate)) return "The end date is before the start date."
        if (java.time.temporal.ChronoUnit.DAYS.between(spec.window.startDate, spec.window.endDate) + 1 > MAX_WINDOW_DAYS) return "Challenges can last up to a year."
        if (spec.daily.op == "cap" && (spec.daily.max ?: 0.0) <= 0) return "The daily cap must be positive."
        if (spec.daily.op == "atLeast" && (spec.daily.value ?: 0.0) <= 0) return "The daily target must be positive."
        return null
    }
}

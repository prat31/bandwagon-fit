package fit.bandwagon.core.model.insights

import fit.bandwagon.core.model.HealthMath
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

enum class InsightKind { ACTIVITY, SLEEP, HEART, CHALLENGE }

/**
 * One card on Home. [explanation] backs "Why am I seeing this?" with the numbers behind it.
 * Copy is friendly and specific, never medical, never guilt.
 */
data class Insight(
    val id: String,
    val kind: InsightKind,
    val priority: Int,
    val title: String,
    val body: String,
    val explanation: String,
    val deepLink: String? = null,
)

data class Night(val asleepMinutes: Int, val bedtime: Instant)

data class ChallengeContext(
    val groupId: String,
    val challengeId: String,
    val title: String,
    val rank: Long?,
    /** How far behind the next person, in the challenge's own units, when known. */
    val gapToNext: String?,
    val gapRatio: Double?,
    val endsToday: Boolean,
    /** Team goals: amount still needed per day to finish on time, already formatted. */
    val teamPacePerDay: String?,
)

/**
 * Inputs are oldest-first daily series ending today. Missing days are null, never zero, so a
 * rule never mistakes "not shared" or "no data" for a lazy day.
 */
data class InsightInputs(
    val today: LocalDate,
    val zone: ZoneId,
    val steps: List<Double?> = emptyList(),
    val stepsGoal: Int = 8_000,
    val activeMinutes: List<Double?> = emptyList(),
    val activeMinutesWeeklyGoal: Int = 150,
    val nights: List<Night> = emptyList(),
    val sleepGoalMinutes: Int = 450,
    val restingHeartRate: List<Double?> = emptyList(),
    val hrv: List<Double?> = emptyList(),
    val challenges: List<ChallengeContext> = emptyList(),
)

/** Deterministic, on-device rules. Each returns an insight or nothing; Home shows the top few. */
object InsightEngine {
    fun evaluate(inputs: InsightInputs, dismissed: Set<String> = emptySet(), limit: Int = 4): List<Insight> =
        listOfNotNull(
            activityChange(inputs),
            goalStreak(inputs),
            personalBest(inputs),
            whoProgress(inputs),
            bedtimeDrift(inputs),
            sleepDebt(inputs),
            restingHeartRateRaised(inputs),
            hrvBelowRange(inputs),
        ).plus(challengeReminders(inputs))
            .filter { it.id !in dismissed }
            .sortedByDescending { it.priority }
            .take(limit)

    /** This week's daily average vs the previous four weeks, when they differ by 15% or more. */
    fun activityChange(i: InsightInputs): Insight? {
        if (i.steps.size < 35) return null
        val week = i.steps.takeLast(7).filterNotNull()
        val before = i.steps.dropLast(7).takeLast(28).filterNotNull()
        if (week.size < 4 || before.size < 14) return null
        val now = week.average()
        val base = before.average()
        val change = HealthMath.changePercent(now, base) ?: return null
        if (abs(change) < 15) return null
        val pct = abs(change).roundToInt()
        return Insight(
            id = "activity-${i.today}",
            kind = InsightKind.ACTIVITY,
            priority = if (change > 0) 60 else 50,
            title = if (change > 0) "$pct% more steps than usual" else "A quieter week",
            body = if (change > 0) "You're averaging ${fmt(now)} steps a day this week. Keep it rolling!"
            else "You're averaging ${fmt(now)} steps a day, $pct% below your usual. A short walk counts.",
            explanation = "Daily average over the last 7 days (${fmt(now)}) compared with the 4 weeks before (${fmt(base)}).",
            deepLink = "bandwagon://health/metric/STEPS",
        )
    }

    /** Step goal hit three or more days in a row, ending today or yesterday. */
    fun goalStreak(i: InsightInputs): Insight? {
        var run = 0
        val series = i.steps.toMutableList()
        // Today isn't over: count it only if the goal is already hit.
        if (series.lastOrNull()?.let { it < i.stepsGoal } != false) series.removeLastOrNull()
        for (v in series.reversed()) {
            if (v != null && v >= i.stepsGoal) run++ else break
        }
        if (run < 3) return null
        return Insight(
            id = "streak-$run-${i.today}",
            kind = InsightKind.ACTIVITY,
            priority = 55 + run,
            title = "$run days in a row on goal 🔥",
            body = "You've hit ${fmt(i.stepsGoal.toDouble())} steps $run days running.",
            explanation = "Consecutive days with steps at or above your goal of ${fmt(i.stepsGoal.toDouble())}.",
            deepLink = "bandwagon://health/metric/STEPS",
        )
    }

    /** Today or yesterday beat every other day in the last 90. */
    fun personalBest(i: InsightInputs): Insight? {
        if (i.steps.count { it != null } < 21) return null
        val recent = i.steps.takeLast(2)
        val history = i.steps.dropLast(2).filterNotNull()
        val best = recent.filterNotNull().maxOrNull() ?: return null
        val previous = history.maxOrNull() ?: return null
        if (best <= previous) return null
        return Insight(
            id = "pb-${fmt(best)}",
            kind = InsightKind.ACTIVITY,
            priority = 80,
            title = "New personal best!",
            body = "${fmt(best)} steps is your highest in ${i.steps.size} days.",
            explanation = "Highest daily steps in the period shown. Previous best: ${fmt(previous)}.",
            deepLink = "bandwagon://health/metric/STEPS",
        )
    }

    /** WHO weekly target: celebrate reaching it, or show what's left late in the week. */
    fun whoProgress(i: InsightInputs): Insight? {
        if (i.activeMinutes.isEmpty()) return null
        val sinceMonday = i.today.dayOfWeek.value
        val week = i.activeMinutes.takeLast(sinceMonday).filterNotNull()
        if (week.isEmpty()) return null
        val total = week.sum().roundToInt()
        val goal = i.activeMinutesWeeklyGoal
        val daysLeft = 7 - sinceMonday
        return when {
            total >= goal -> Insight(
                id = "who-done-${i.today.with(DayOfWeek.MONDAY)}",
                kind = InsightKind.ACTIVITY,
                priority = 65,
                title = "Weekly active goal reached",
                body = "$total active minutes this week. That's the amount health guidelines recommend.",
                explanation = "Active minutes since Monday ($total) against your weekly goal ($goal). WHO suggests 150–300 moderate minutes a week.",
                deepLink = "bandwagon://health/metric/ACTIVE_MINUTES",
            )
            daysLeft in 1..3 && goal - total <= 90 -> Insight(
                id = "who-left-${i.today}",
                kind = InsightKind.ACTIVITY,
                priority = 58,
                title = "${goal - total} active minutes to go",
                body = "$daysLeft ${if (daysLeft == 1) "day" else "days"} left to reach your weekly goal.",
                explanation = "Active minutes since Monday ($total) against your weekly goal ($goal).",
                deepLink = "bandwagon://health/metric/ACTIVE_MINUTES",
            )
            else -> null
        }
    }

    /** Usual bedtime this week is 45+ minutes later than the three weeks before. */
    fun bedtimeDrift(i: InsightInputs): Insight? {
        if (i.nights.size < 14) return null
        val week = i.nights.takeLast(7).map { it.bedtime }
        val before = i.nights.dropLast(7).takeLast(21).map { it.bedtime }
        val now = HealthMath.meanClockMinutes(week, i.zone) ?: return null
        val base = HealthMath.meanClockMinutes(before, i.zone) ?: return null
        var drift = now - base
        if (drift > 720) drift -= 1440
        if (drift < -720) drift += 1440
        if (drift < 45) return null
        return Insight(
            id = "bedtime-${i.today}",
            kind = InsightKind.SLEEP,
            priority = 62,
            title = "Bedtime has drifted ${drift} min later",
            body = "Your usual bedtime this week is about ${clock(now)}, up from ${clock(base)}.",
            explanation = "Average bedtime over the last 7 nights compared with the 3 weeks before.",
            deepLink = "bandwagon://health/sleep",
        )
    }

    /** Seven-night debt of three hours or more. */
    fun sleepDebt(i: InsightInputs): Insight? {
        if (i.nights.size < 5) return null
        val debt = HealthMath.sleepDebtMinutes(i.nights.takeLast(7).map { it.asleepMinutes }, i.sleepGoalMinutes)
        if (debt < 180) return null
        return Insight(
            id = "debt-${i.today}",
            kind = InsightKind.SLEEP,
            priority = 64,
            title = "${debt / 60}h ${debt % 60}m of sleep debt",
            body = "You've slept less than your goal on several nights this week. An earlier night could help.",
            explanation = "Minutes short of your ${i.sleepGoalMinutes / 60}h ${i.sleepGoalMinutes % 60}m goal, added up over the last 7 nights. Extra sleep doesn't count against it.",
            deepLink = "bandwagon://health/sleep",
        )
    }

    /** Resting heart rate above the personal range for the last three days with data. */
    fun restingHeartRateRaised(i: InsightInputs): Insight? {
        val history = i.restingHeartRate.dropLast(3).filterNotNull()
        val range = HealthMath.personalRange(history) ?: return null
        val recent = i.restingHeartRate.takeLast(3)
        if (recent.any { it == null || it <= range.high }) return null
        return Insight(
            id = "rhr-${i.today}",
            kind = InsightKind.HEART,
            priority = 70,
            title = "Resting heart rate is up",
            body = "It's been above your usual range for 3 days. Might be worth an easy day and an early night.",
            explanation = "Your usual range is ${fmt(range.low)}–${fmt(range.high)} bpm (your average ± one standard deviation over ${range.samples} days). This is not medical advice.",
            deepLink = "bandwagon://health/metric/RESTING_HEART_RATE",
        )
    }

    /** Seven-day HRV average below the personal range. */
    fun hrvBelowRange(i: InsightInputs): Insight? {
        val history = i.hrv.dropLast(7).filterNotNull()
        val range = HealthMath.personalRange(history) ?: return null
        val week = i.hrv.takeLast(7).filterNotNull()
        if (week.size < 4) return null
        val avg = week.average()
        if (avg >= range.low) return null
        return Insight(
            id = "hrv-${i.today}",
            kind = InsightKind.HEART,
            priority = 57,
            title = "HRV below your usual range",
            body = "Your 7-day average is ${fmt(avg)} ms. Rest and sleep tend to bring it back.",
            explanation = "Usual range ${fmt(range.low)}–${fmt(range.high)} ms from your own history. HRV differs between devices, so only compare with yourself.",
            deepLink = "bandwagon://health/metric/HRV",
        )
    }

    fun challengeReminders(i: InsightInputs): List<Insight> = i.challenges.mapNotNull { c ->
        val link = "bandwagon://groups/${c.groupId}/challenges/${c.challengeId}"
        when {
            c.teamPacePerDay != null -> Insight(
                id = "pace-${c.challengeId}-${i.today}",
                kind = InsightKind.CHALLENGE,
                priority = 54,
                title = "${c.title}: on pace?",
                body = "The group needs ${c.teamPacePerDay} a day to finish on time.",
                explanation = "What's left of the team goal divided by the days remaining.",
                deepLink = link,
            )
            c.rank != null && c.rank > 1 && c.gapToNext != null && (c.gapRatio ?: 1.0) <= 0.05 -> Insight(
                id = "gap-${c.challengeId}-${i.today}",
                kind = InsightKind.CHALLENGE,
                priority = 75,
                title = "So close in ${c.title}",
                body = "Just ${c.gapToNext} behind #${c.rank - 1}.",
                explanation = "You're within 5% of the next person on the leaderboard.",
                deepLink = link,
            )
            c.endsToday -> Insight(
                id = "ends-${c.challengeId}-${i.today}",
                kind = InsightKind.CHALLENGE,
                priority = 72,
                title = "${c.title} ends today",
                body = c.rank?.let { "You're #$it. Last chance to move up!" } ?: "Last chance to add to it!",
                explanation = "Today is the challenge's last day in your time zone.",
                deepLink = link,
            )
            else -> null
        }
    }

    private fun fmt(v: Double): String = java.text.NumberFormat.getIntegerInstance().format(v.roundToInt())

    private fun clock(minutes: Int): String = "%d:%02d".format(((minutes / 60) % 24), minutes % 60)
}

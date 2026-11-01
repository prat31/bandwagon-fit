package fit.bandwagon.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fit.bandwagon.core.data.local.GoalDao
import fit.bandwagon.core.data.local.GoalOutcomeEntity
import fit.bandwagon.core.data.local.PersonalGoalEntity
import fit.bandwagon.core.model.LocalDateRange
import fit.bandwagon.core.model.goals.GoalEngine
import fit.bandwagon.core.model.goals.GoalMetric
import fit.bandwagon.core.model.goals.GoalOutcome
import fit.bandwagon.core.model.goals.GoalPeriod
import fit.bandwagon.core.model.goals.OutcomeResult
import fit.bandwagon.core.model.goals.PersonalGoal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Personal goals and their recorded outcomes, kept on this phone. Every change also updates the
 * profile's four core targets (steps, active minutes, sleep, water) so the Health tab and insights
 * follow the goals set on Home.
 */
@Singleton
class GoalRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val dao: GoalDao,
    private val profiles: ProfileRepository,
    private val clock: Clock,
) {
    private val prefs = context.getSharedPreferences("goals", Context.MODE_PRIVATE)

    fun observeGoals(): Flow<List<PersonalGoal>> = dao.observeGoals().map { rows -> rows.mapNotNull(::toGoal) }

    fun observeOutcomes(): Flow<Map<String, List<GoalOutcome>>> =
        dao.observeOutcomes().map { rows -> rows.mapNotNull(::toOutcome).groupBy { it.goalId } }

    suspend fun outcomes(goalId: String): List<GoalOutcome> = dao.outcomes(goalId).mapNotNull(::toOutcome)

    suspend fun saveOutcomes(outcomes: List<GoalOutcome>) {
        if (outcomes.isNotEmpty()) dao.upsertOutcomes(outcomes.map(::toEntity))
    }

    suspend fun add(metric: GoalMetric, period: GoalPeriod, target: Double, dailyThreshold: Double?) {
        val goal = PersonalGoal(UUID.randomUUID().toString(), metric, period, target, dailyThreshold?.takeIf { period != GoalPeriod.DAILY }, LocalDate.now(clock))
        dao.upsertGoal(toEntity(goal, clock.millis()))
        syncCoreTargets()
    }

    /** Only the target and threshold change; closed periods keep the target they were judged on. */
    suspend fun update(goal: PersonalGoal) {
        val existing = dao.goals().firstOrNull { it.id == goal.id } ?: return
        dao.upsertGoal(existing.copy(target = goal.target, dailyThreshold = goal.dailyThreshold?.takeIf { goal.period != GoalPeriod.DAILY }))
        syncCoreTargets()
    }

    suspend fun delete(id: String) {
        dao.deleteGoal(id)
        syncCoreTargets()
    }

    /**
     * Targets set in You → Goals before goals moved to Home become goals once, so nothing the user
     * chose is lost. Defaults are not copied: an empty list shows the "add a goal" prompt.
     */
    suspend fun seedFromProfileOnce() {
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        val profile = runCatching { profiles.profile() }.getOrNull() ?: return
        if (dao.goals().isEmpty()) {
            val now = clock.millis()
            val seeded = GoalEngine.fromCoreTargets(profile.goals, LocalDate.now(clock)) { UUID.randomUUID().toString() }
            dao.upsertGoals(seeded.mapIndexed { i, g -> toEntity(g, now + i) })
        }
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    private suspend fun syncCoreTargets() {
        val targets = GoalEngine.coreTargets(dao.goals().mapNotNull(::toGoal))
        runCatching { if (profiles.profile()?.goals != targets) profiles.updateGoals(targets) }
    }

    /** Sign-out and account deletion wipe everything local. */
    suspend fun clear() {
        dao.clearOutcomes()
        dao.clearGoals()
        prefs.edit().clear().apply()
    }

    private fun toGoal(row: PersonalGoalEntity): PersonalGoal? {
        val metric = runCatching { GoalMetric.valueOf(row.metric) }.getOrNull() ?: return null
        val period = runCatching { GoalPeriod.valueOf(row.period) }.getOrNull() ?: return null
        return PersonalGoal(row.id, metric, period, row.target, row.dailyThreshold, LocalDate.parse(row.createdOn))
    }

    private fun toEntity(goal: PersonalGoal, createdAt: Long) = PersonalGoalEntity(
        id = goal.id,
        metric = goal.metric.name,
        period = goal.period.name,
        target = goal.target,
        dailyThreshold = goal.dailyThreshold,
        createdOn = goal.createdOn.toString(),
        createdAt = createdAt,
    )

    private fun toOutcome(row: GoalOutcomeEntity): GoalOutcome? {
        val result = runCatching { OutcomeResult.valueOf(row.result) }.getOrNull() ?: return null
        return GoalOutcome(
            goalId = row.goalId,
            window = LocalDateRange(LocalDate.parse(row.periodStart), LocalDate.parse(row.periodEnd)),
            target = row.target,
            dailyThreshold = row.dailyThreshold,
            achieved = row.achieved,
            result = result,
            finalized = row.finalized,
        )
    }

    private fun toEntity(o: GoalOutcome) = GoalOutcomeEntity(
        goalId = o.goalId,
        periodStart = o.window.start.toString(),
        periodEnd = o.window.endInclusive.toString(),
        target = o.target,
        dailyThreshold = o.dailyThreshold,
        achieved = o.achieved,
        result = o.result.name,
        finalized = o.finalized,
    )

    private companion object {
        const val KEY_SEEDED = "seeded_from_profile"
    }
}

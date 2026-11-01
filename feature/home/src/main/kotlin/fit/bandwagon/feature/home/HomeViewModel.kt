package fit.bandwagon.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fit.bandwagon.core.data.repository.ChallengeRepository
import fit.bandwagon.core.data.repository.GoalRepository
import fit.bandwagon.core.data.repository.GroupRepository
import fit.bandwagon.core.data.repository.ProfileRepository
import fit.bandwagon.core.data.repository.ScoreRepository
import fit.bandwagon.core.health.HealthCapabilitiesRepository
import fit.bandwagon.core.health.store.DayValue
import fit.bandwagon.core.health.store.HealthRepository
import fit.bandwagon.core.model.Goals
import fit.bandwagon.core.model.HealthCapabilities
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.HealthMath
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.LocalDateRange
import fit.bandwagon.core.model.MyProfile
import fit.bandwagon.core.model.PersonalRange
import fit.bandwagon.core.model.SleepSelection
import fit.bandwagon.core.model.SleepSession
import fit.bandwagon.core.model.challenge.ChallengeKind
import fit.bandwagon.core.model.challenge.ChallengePhase
import fit.bandwagon.core.model.goals.GoalEngine
import fit.bandwagon.core.model.goals.GoalMetric
import fit.bandwagon.core.model.goals.GoalOutcome
import fit.bandwagon.core.model.goals.GoalPeriod
import fit.bandwagon.core.model.goals.PersonalGoal
import fit.bandwagon.core.model.insights.ChallengeContext
import fit.bandwagon.core.model.insights.Insight
import fit.bandwagon.core.model.insights.InsightEngine
import fit.bandwagon.core.model.insights.InsightInputs
import fit.bandwagon.core.model.insights.Night
import fit.bandwagon.core.model.scores.ActivityDay
import fit.bandwagon.core.model.scores.DailyScore
import fit.bandwagon.core.model.scores.MoveScoreCalculator
import fit.bandwagon.core.model.scores.SleepScoreCalculator
import fit.bandwagon.core.sync.SyncEngine
import fit.bandwagon.feature.challenges.ChallengeCardState
import fit.bandwagon.feature.challenges.formatScore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class HomeChallenge(val groupId: String, val groupName: String, val card: ChallengeCardState)

/** The latest daily value of a vital and how it sits against the user's own recent range. */
data class Vital(val value: Double, val date: LocalDate, val range: PersonalRange?)

data class TodayNumbers(
    val steps: Double? = null,
    val stepsMetric: HealthMetric = HealthMetric.STEPS,
    val activeToday: Double? = null,
    val activeWeek: Double? = null,
    val activeMetric: HealthMetric = HealthMetric.ACTIVE_MINUTES,
    val lastNight: SleepSession? = null,
    val distance: Double? = null,
    val activeCalories: Double? = null,
    val floors: Double? = null,
    val recovery: Int? = null,
    val sleepScore: DailyScore? = null,
    val moveScore: DailyScore? = null,
    val restingHeartRate: Vital? = null,
    val respiratoryRate: Vital? = null,
    val skinTemperature: Vital? = null,
)

data class HomeState(
    val profile: MyProfile? = null,
    val capabilities: HealthCapabilities = HealthCapabilities.NONE,
    val today: TodayNumbers = TodayNumbers(),
    val challenges: List<HomeChallenge> = emptyList(),
    val insights: List<Insight> = emptyList(),
    val goals: List<GoalCard> = emptyList(),
    val goalsLoaded: Boolean = false,
    val goalSources: GoalSources = GoalSources(HealthMetric.STEPS, HealthMetric.ACTIVE_MINUTES),
    val invites: Int = 0,
    val groupCount: Int = 0,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val health: HealthRepository,
    private val capabilities: HealthCapabilitiesRepository,
    private val profiles: ProfileRepository,
    private val groups: GroupRepository,
    private val challenges: ChallengeRepository,
    private val sync: SyncEngine,
    private val goalRepository: GoalRepository,
    private val scores: ScoreRepository,
    private val goalValues: GoalValues,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("insights", Context.MODE_PRIVATE)
    private val watchers = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            var seeded = false
            profiles.observeProfile().catch { }.collect { p ->
                val goalsChanged = p?.goals != _state.value.profile?.goals
                _state.update { it.copy(profile = p) }
                if (p != null && !seeded) {
                    seeded = true
                    runCatching { goalRepository.seedFromProfileOnce() }
                }
                // Score targets come from the profile's goals; recompute when they change.
                if (goalsChanged && _state.value.loaded) loadHealth()
            }
        }
        viewModelScope.launch {
            goalRepository.observeGoals().combine(goalRepository.observeOutcomes()) { g, o -> g to o }.catch { }.collect { (g, o) ->
                goals = g
                outcomes = o
                trackGoals()
            }
        }
        viewModelScope.launch {
            capabilities.capabilities.collect { caps ->
                val changed = caps != _state.value.capabilities
                _state.update { it.copy(capabilities = caps) }
                if (changed) loadHealth()
            }
        }
        viewModelScope.launch { groups.observeInbox().catch { }.collect { inbox -> _state.update { it.copy(invites = inbox.size) } } }
        viewModelScope.launch {
            groups.observeMemberships().catch { }.collect { memberships ->
                _state.update { it.copy(groupCount = memberships.size) }
                memberships.forEach { m -> watchGroup(m.groupId, m.groupName, m.memberId) }
            }
        }
    }

    /** Live challenges of one group, each with the member's score and the summary. */
    private fun watchGroup(groupId: String, groupName: String, memberId: String) {
        if (watchers.containsKey(groupId)) return
        watchers[groupId] = viewModelScope.launch {
            challenges.observeChallenges(groupId).catch { }.collect { list ->
                val today = LocalDate.now()
                val live = list.filter { it.phase(today) == ChallengePhase.LIVE && it.spec != null }
                _state.update { s ->
                    val others = s.challenges.filter { it.groupId != groupId }
                    val existing = s.challenges.filter { it.groupId == groupId }.associateBy { it.card.challenge.id }
                    s.copy(challenges = (others + live.map { c -> existing[c.id]?.let { it.copy(card = it.card.copy(challenge = c)) } ?: HomeChallenge(groupId, groupName, ChallengeCardState(c)) }).sortedBy { it.card.challenge.spec?.window?.end })
                }
                live.forEach { c ->
                    launch {
                        challenges.observeScore(groupId, c.id, memberId).catch { }.collect { score ->
                            updateCard(c.id) { it.copy(myScore = score) }
                        }
                    }
                    launch {
                        challenges.observeSummary(groupId, c.id).catch { }.collect { summary ->
                            updateCard(c.id) { it.copy(summary = summary) }
                            computeInsights()
                        }
                    }
                }
            }
        }
    }

    private fun updateCard(challengeId: String, transform: (ChallengeCardState) -> ChallengeCardState) =
        _state.update { s -> s.copy(challenges = s.challenges.map { if (it.card.challenge.id == challengeId) it.copy(card = transform(it.card)) else it }) }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            runCatching { sync.sync() }
            capabilities.refresh()
            loadHealth(force = true)
            _state.update { it.copy(refreshing = false) }
        }
    }

    private var steps35: List<Double?> = emptyList()
    private var active14: List<Double?> = emptyList()
    private var nights: List<SleepSession> = emptyList()
    private var rhr: List<Double?> = emptyList()
    private var hrv: List<Double?> = emptyList()
    private var goals: List<PersonalGoal> = emptyList()
    private var outcomes: Map<String, List<GoalOutcome>> = emptyMap()
    private var trackJob: Job? = null

    private suspend fun days(metric: HealthMetric, days: Long, force: Boolean): List<DayValue> =
        runCatching { health.daily(metric, days, force) }.getOrDefault(emptyList())

    private suspend fun daily(metric: HealthMetric, days: Long, force: Boolean): List<Double?> = days(metric, days, force).map { it.value }

    /** Latest value in the last two days, with a personal range from the days before it. */
    private suspend fun vital(metric: HealthMetric, force: Boolean): Vital? {
        val values = days(metric, 60, force)
        val latest = values.takeLast(2).lastOrNull { it.value != null } ?: return null
        val range = HealthMath.personalRange(values.filter { it.date < latest.date }.mapNotNull { it.value })
        return Vital(latest.value!!, latest.date, range)
    }

    private fun loadHealth(force: Boolean = false) {
        viewModelScope.launch {
            val caps = _state.value.capabilities
            val settings = _state.value.profile?.settings
            val stepsMetric = if (settings?.pushesInsteadOfSteps == true) HealthMetric.WHEELCHAIR_PUSHES else HealthMetric.STEPS
            val activeMetric = if (caps.granted(HealthDataType.ACTIVITY_INTENSITY)) HealthMetric.ACTIVE_MINUTES else HealthMetric.WORKOUT_MINUTES
            val stepDays = days(stepsMetric, 35, force)
            val activeDays = days(activeMetric, 14, force)
            steps35 = stepDays.map { it.value }
            active14 = activeDays.map { it.value }
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            nights = if (caps.granted(HealthDataType.SLEEP)) {
                runCatching { health.sleep(LocalDateRange.lastDays(today, 28)) }.getOrDefault(emptyList())
                    .groupBy { it.end.atZone(zone).toLocalDate() }.values.mapNotNull { SleepSelection.mainSession(it) }.sortedBy { it.end }
            } else emptyList()
            rhr = daily(HealthMetric.RESTING_HEART_RATE, 60, force)
            hrv = daily(HealthMetric.HRV, 60, force)
            val recovery = HealthMath.recoveryScore(
                restingHeartRate = rhr.takeLast(7).filterNotNull().takeIf { it.isNotEmpty() }?.average(),
                rhrRange = HealthMath.personalRange(rhr.dropLast(7).filterNotNull()),
                hrv = hrv.takeLast(7).filterNotNull().takeIf { it.isNotEmpty() }?.average(),
                hrvRange = HealthMath.personalRange(hrv.dropLast(7).filterNotNull()),
            )
            val targets = _state.value.profile?.goals ?: Goals()
            val sleepScores = nights.mapIndexedNotNull { i, night ->
                SleepScoreCalculator.score(night, targets.sleepMinutes, nights.subList(0, i), zone)?.let { night.end.atZone(zone).toLocalDate() to it }
            }.toMap()
            val moveScores = moveScores(stepDays, activeDays, targets, caps, stepsMetric, activeMetric)
            runCatching { scores.record(sleepScores) }
            runCatching { scores.record(moveScores) }
            val sinceMonday = today.dayOfWeek.value
            val numbers = TodayNumbers(
                steps = steps35.lastOrNull(),
                stepsMetric = stepsMetric,
                activeToday = active14.lastOrNull(),
                activeWeek = active14.takeLast(sinceMonday).filterNotNull().takeIf { it.isNotEmpty() }?.sum(),
                activeMetric = activeMetric,
                lastNight = nights.lastOrNull()?.takeIf { it.end.atZone(zone).toLocalDate() == today },
                distance = daily(HealthMetric.DISTANCE, 1, force).lastOrNull(),
                activeCalories = daily(HealthMetric.ACTIVE_CALORIES, 1, force).lastOrNull(),
                floors = daily(HealthMetric.FLOORS, 1, force).lastOrNull(),
                recovery = recovery,
                sleepScore = sleepScores[today],
                moveScore = moveScores[today],
                restingHeartRate = vital(HealthMetric.RESTING_HEART_RATE, force),
                respiratoryRate = vital(HealthMetric.RESPIRATORY_RATE, force),
                skinTemperature = vital(HealthMetric.SKIN_TEMPERATURE, force),
            )
            _state.update { it.copy(today = numbers, loaded = true, goalSources = GoalSources(stepsMetric, activeMetric)) }
            computeInsights()
            trackGoals(force)
        }
    }

    /** Move score for each of the last 14 days, each judged against the 7 days before it. */
    private fun moveScores(
        stepDays: List<DayValue>,
        activeDays: List<DayValue>,
        targets: Goals,
        caps: HealthCapabilities,
        stepsMetric: HealthMetric,
        activeMetric: HealthMetric,
    ): Map<LocalDate, DailyScore> {
        val stepsShared = caps.granted(stepsMetric.dataType)
        val activeShared = caps.granted(activeMetric.dataType)
        if (!stepsShared && !activeShared) return emptyMap()
        // A shared metric with no record that day is a real zero for the score.
        val steps = stepDays.associate { it.date to it.value }
        val active = activeDays.associate { it.date to it.value }
        fun day(date: LocalDate) = ActivityDay(
            steps = if (stepsShared) steps[date] ?: 0.0 else null,
            activeMinutes = if (activeShared) active[date] ?: 0.0 else null,
        )
        val today = LocalDate.now()
        return (0L until 14L).map { today.minusDays(it) }.mapNotNull { date ->
            val previous = (7L downTo 1L).map { day(date.minusDays(it)) }
            MoveScoreCalculator.score(day(date), previous, targets.steps, targets.activeMinutesWeekly)?.let { date to it }
        }.toMap()
    }

    /**
     * Live progress for every goal, plus reconciliation: closed periods get an outcome, and ones
     * still inside the settle window are recomputed in case data arrived late.
     */
    private fun trackGoals(force: Boolean = false) {
        trackJob?.cancel()
        trackJob = viewModelScope.launch {
            val list = goals
            if (list.isEmpty()) {
                _state.update { it.copy(goals = emptyList(), goalsLoaded = true) }
                return@launch
            }
            val s = _state.value
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val days = goalValues.daysNeeded(list)
            val values = list.map { it.metric }.distinct().associateWith { metric ->
                goalValues.load(metric, days, today, zone, s.goalSources, force)
            }
            val fraction = GoalValues.dayFraction(LocalTime.now())
            val cards = list.map { goal ->
                val data = values[goal.metric].orEmpty()
                val history = outcomes[goal.id].orEmpty()
                val changed = GoalEngine.reconcile(goal, history, data, today)
                if (changed.isNotEmpty()) runCatching { goalRepository.saveOutcomes(changed) }
                val merged = (history.associateBy { it.window.start } + changed.associateBy { it.window.start }).values.sortedBy { it.window.start }
                val progress = GoalEngine.progress(goal, data, today, fraction)
                GoalCard(
                    goal = goal,
                    progress = progress,
                    streak = GoalEngine.streak(merged, GoalValues.isDone(progress)),
                    recent = merged.takeLast(6),
                    shared = goalValues.shared(goal.metric, s.capabilities, s.goalSources),
                )
            }
            _state.update { it.copy(goals = cards, goalsLoaded = true) }
        }
    }

    fun addGoal(metric: GoalMetric, period: GoalPeriod, target: Double, dailyThreshold: Double?) {
        viewModelScope.launch { runCatching { goalRepository.add(metric, period, target, dailyThreshold) } }
    }

    fun updateGoal(goal: PersonalGoal) {
        viewModelScope.launch { runCatching { goalRepository.update(goal) } }
    }

    fun deleteGoal(goal: PersonalGoal) {
        viewModelScope.launch { runCatching { goalRepository.delete(goal.id) } }
    }

    private fun computeInsights() {
        val s = _state.value
        val today = LocalDate.now()
        val targets = s.profile?.goals ?: Goals()
        val contexts = s.challenges.mapNotNull { tc ->
            val spec = tc.card.challenge.spec ?: return@mapNotNull null
            val score = tc.card.myScore
            val summary = tc.card.summary
            val top = summary?.top.orEmpty()
            val myRow = top.firstOrNull { it.memberId == score?.memberId }
            val ahead = myRow?.let { me -> top.lastOrNull { it.rank < me.rank } }
            val endsToday = spec.window.endDate == today
            val daysLeft = ChronoUnit.DAYS.between(today, spec.window.endDate) + 1
            val pace = if (spec.kind == ChallengeKind.TEAM && summary?.total != null && spec.goal != null && daysLeft > 0) {
                val left = spec.goal!!.target - summary.total!!
                if (left > 0) formatScore(spec, left / daysLeft) else null
            } else null
            ChallengeContext(
                groupId = tc.groupId,
                challengeId = tc.card.challenge.id,
                title = spec.title,
                rank = myRow?.rank?.toLong(),
                gapToNext = if (ahead != null && score != null) formatScore(spec, ahead.score - score.score) else null,
                gapRatio = if (ahead != null && score != null && ahead.score > 0) (ahead.score - score.score) / ahead.score else null,
                endsToday = endsToday,
                teamPacePerDay = pace,
            )
        }
        val inputs = InsightInputs(
            today = today,
            zone = ZoneId.systemDefault(),
            steps = steps35,
            stepsGoal = targets.steps,
            activeMinutes = active14,
            activeMinutesWeeklyGoal = targets.activeMinutesWeekly,
            nights = nights.map { Night(it.asleepMinutes, it.start) },
            sleepGoalMinutes = targets.sleepMinutes,
            restingHeartRate = rhr,
            hrv = hrv,
            challenges = contexts,
        )
        _state.update { it.copy(insights = InsightEngine.evaluate(inputs, dismissed())) }
    }

    /** Dismissed insights stay hidden for 7 days. */
    private fun dismissed(): Set<String> {
        val cutoff = System.currentTimeMillis() - 7 * 86_400_000L
        return prefs.all.filter { (_, v) -> (v as? Long ?: 0L) > cutoff }.keys
    }

    fun dismiss(insight: Insight) {
        prefs.edit().putLong(insight.id, System.currentTimeMillis()).apply()
        _state.update { it.copy(insights = it.insights.filterNot { i -> i.id == insight.id }) }
    }
}

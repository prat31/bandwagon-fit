package fit.bandwagon.feature.challenges

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fit.bandwagon.core.data.remote.RemoteError
import fit.bandwagon.core.data.repository.ChallengeRepository
import fit.bandwagon.core.data.repository.DailySummaryRepository
import fit.bandwagon.core.data.repository.GroupRepository
import fit.bandwagon.core.health.HealthCapabilitiesRepository
import fit.bandwagon.core.model.HealthCapabilities
import fit.bandwagon.core.model.Member
import fit.bandwagon.core.model.Membership
import fit.bandwagon.core.model.challenge.Aggregate
import fit.bandwagon.core.model.challenge.Challenge
import fit.bandwagon.core.model.challenge.ChallengeEngine
import fit.bandwagon.core.model.challenge.ChallengeKind
import fit.bandwagon.core.model.challenge.ChallengeMetric
import fit.bandwagon.core.model.challenge.ChallengeSpec
import fit.bandwagon.core.model.challenge.ChallengeSummary
import fit.bandwagon.core.model.challenge.ChallengeTemplate
import fit.bandwagon.core.model.challenge.Condition
import fit.bandwagon.core.model.challenge.DailyRule
import fit.bandwagon.core.model.challenge.DayCounts
import fit.bandwagon.core.model.challenge.DayRow
import fit.bandwagon.core.model.challenge.Goal
import fit.bandwagon.core.model.challenge.MemberResult
import fit.bandwagon.core.model.challenge.MemberScore
import fit.bandwagon.core.model.challenge.MetricRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

object ChallengeRoutes {
    const val NEW = "groups/{groupId}/challenges/new"
    const val DETAIL = "groups/{groupId}/challenges/{challengeId}"

    fun new(groupId: String) = "groups/$groupId/challenges/new"
    fun detail(groupId: String, challengeId: String) = "groups/$groupId/challenges/$challengeId"
}

data class ChallengeCardState(val challenge: Challenge, val myScore: MemberScore? = null, val summary: ChallengeSummary? = null)

data class ChallengeListState(val cards: List<ChallengeCardState> = emptyList(), val loaded: Boolean = false)

/** Challenges of one group with the member's own score on each live card. */
@HiltViewModel
class ChallengeListViewModel @Inject constructor(
    private val challenges: ChallengeRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ChallengeListState())
    val state: StateFlow<ChallengeListState> = _state.asStateFlow()
    private var job: Job? = null
    private val watching = mutableMapOf<String, Job>()

    fun bind(groupId: String, memberId: String?) {
        if (job != null) return
        job = viewModelScope.launch {
            challenges.observeChallenges(groupId).catch { emit(emptyList()) }.collect { list ->
                _state.update { s ->
                    val old = s.cards.associateBy { it.challenge.id }
                    s.copy(cards = list.map { c -> old[c.id]?.copy(challenge = c) ?: ChallengeCardState(c) }, loaded = true)
                }
                list.forEach { c -> watch(groupId, c.id, memberId) }
            }
        }
    }

    private fun watch(groupId: String, challengeId: String, memberId: String?) {
        if (watching.containsKey(challengeId)) return
        watching[challengeId] = viewModelScope.launch {
            launch {
                challenges.observeSummary(groupId, challengeId).catch { }.collect { summary ->
                    _state.update { s -> s.copy(cards = s.cards.map { if (it.challenge.id == challengeId) it.copy(summary = summary) else it }) }
                }
            }
            if (memberId != null) {
                challenges.observeScore(groupId, challengeId, memberId).catch { }.collect { score ->
                    _state.update { s -> s.copy(cards = s.cards.map { if (it.challenge.id == challengeId) it.copy(myScore = score) else it }) }
                }
            }
        }
    }
}

data class ChallengeDetailState(
    val challenge: Challenge? = null,
    val summary: ChallengeSummary? = null,
    val membership: Membership? = null,
    val myScore: MemberScore? = null,
    val myRank: Long? = null,
    val leaderboard: List<MemberScore> = emptyList(),
    val members: Map<String, Member> = emptyMap(),
    val today: List<DayRow> = emptyList(),
    val history: List<DayCounts> = emptyList(),
    val boardDate: LocalDate = LocalDate.now(),
    val capabilities: HealthCapabilities = HealthCapabilities.NONE,
    val pageSize: Long = 25,
    val message: String? = null,
    val closed: Boolean = false,
)

@HiltViewModel
class ChallengeDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val challenges: ChallengeRepository,
    private val groups: GroupRepository,
    capabilities: HealthCapabilitiesRepository,
) : ViewModel() {
    val groupId: String = checkNotNull(savedState["groupId"])
    val challengeId: String = checkNotNull(savedState["challengeId"])
    private val _state = MutableStateFlow(ChallengeDetailState())
    val state: StateFlow<ChallengeDetailState> = _state.asStateFlow()
    private val pageSize = MutableStateFlow(25L)

    init {
        viewModelScope.launch { capabilities.capabilities.collect { c -> _state.update { it.copy(capabilities = c) } } }
        viewModelScope.launch {
            challenges.observeChallenge(groupId, challengeId).catch { }.collect { c ->
                _state.update { it.copy(challenge = c) }
                if (c?.spec?.display?.renderer == fit.bandwagon.core.model.challenge.Renderer.BOARD || c?.spec?.groupStreak == true) loadHistory()
            }
        }
        viewModelScope.launch { challenges.observeSummary(groupId, challengeId).catch { }.collect { s -> _state.update { it.copy(summary = s) }; loadHistory() } }
        viewModelScope.launch { groups.observeMembership(groupId).catch { }.collect { m -> _state.update { it.copy(membership = m) } } }
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            groups.observeMembership(groupId).map { it?.memberId }.filterNotNull()
                .flatMapLatest { id -> challenges.observeScore(groupId, challengeId, id) }
                .catch { }
                .collectLatest { score ->
                    _state.update { it.copy(myScore = score) }
                    if (score != null) {
                        val rank = runCatching { challenges.rankOf(groupId, challengeId, score.score) }.getOrNull()
                        _state.update { it.copy(myRank = rank) }
                    }
                }
        }
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            pageSize.flatMapLatest { size -> challenges.observeLeaderboard(groupId, challengeId, size) }.catch { }.collect { rows ->
                _state.update { it.copy(leaderboard = rows, pageSize = pageSize.value) }
                resolveMembers(rows.map { r -> r.memberId })
            }
        }
        viewModelScope.launch {
            // Last night's board: rows are keyed by each member's local wake date, which is today.
            challenges.observeDayRows(groupId, challengeId, LocalDate.now().toString(), 200)
                .catch { }
                .collect { rows ->
                    _state.update { it.copy(today = rows) }
                    resolveMembers(rows.map { r -> r.memberId })
                }
        }
    }

    private suspend fun resolveMembers(ids: List<String>) {
        val missing = ids.filter { it !in _state.value.members }
        if (missing.isEmpty()) return
        val found = runCatching { groups.members(groupId, missing) }.getOrDefault(emptyMap())
        _state.update { it.copy(members = it.members + found) }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val history = runCatching { challenges.dayHistory(groupId, challengeId, 14) }.getOrDefault(emptyList())
            _state.update { it.copy(history = history) }
        }
    }

    fun loadMore() {
        pageSize.value = pageSize.value + 25
    }

    fun cancel() {
        viewModelScope.launch {
            val error = runCatching { challenges.cancel(groupId, challengeId) }.exceptionOrNull()
            _state.update { it.copy(message = error?.message, closed = error == null) }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}

/** Clock minutes to the engine's wrapped form: 23:00 → -60, 00:30 → 30. */
fun wrapClock(minutes: Int): Int {
    val m = ((minutes % 1440) + 1440) % 1440
    return if (m >= 720) m - 1440 else m
}

/** Every editable knob of the wizard; [toSpec] turns it into a ChallengeSpec. */
data class WizardDraft(
    val template: ChallengeTemplate,
    val title: String,
    val start: LocalDate,
    val durationDays: Int,
    val weekly: Boolean,
    val dailyTarget: Double?,
    val dailyCap: Double?,
    val goal: Double?,
    val bedtimeMinutes: Int,
    val minSleepMinutes: Int,
    val days: Set<Int>,
    val kind: ChallengeKind,
    val metricId: String,
    val dailyOp: String,
    val aggregate: Aggregate,
) {
    fun toSpec(): ChallengeSpec {
        val base = template.toSpec(start, durationDays, title.trim(), if (weekly) "WEEKLY" else null, days.takeIf { it.isNotEmpty() && it.size < 7 }?.sorted())
        if (template.id == "custom") {
            return base.copy(
                kind = kind,
                metric = MetricRef(metricId),
                daily = when (dailyOp) {
                    "atLeast" -> DailyRule.atLeast(dailyTarget ?: 1.0)
                    "cap" -> DailyRule.cap(dailyCap ?: 1.0)
                    else -> DailyRule.VALUE
                },
                aggregate = if (kind == ChallengeKind.TEAM && aggregate !in setOf(Aggregate.SUM, Aggregate.DAYS_HIT)) Aggregate.SUM else aggregate,
                goal = if (kind == ChallengeKind.COMPETE) null else Goal(goal ?: 1.0),
                display = base.display.copy(
                    renderer = when (kind) {
                        ChallengeKind.COMPETE -> fit.bandwagon.core.model.challenge.Renderer.LEADERBOARD
                        else -> fit.bandwagon.core.model.challenge.Renderer.PROGRESS
                    },
                ),
            )
        }
        var spec = base
        if (template.spec.daily.op == "all") {
            spec = spec.copy(
                daily = DailyRule(
                    "all",
                    conditions = listOf(
                        Condition("bedtime", "<=", wrapClock(bedtimeMinutes - 30).toDouble(), 30.0),
                        Condition("sleep_minutes", ">=", minSleepMinutes.toDouble()),
                    ),
                ),
                goal = Goal(ChallengeEngine.countedDates(spec).size.toDouble()),
            )
        }
        if (template.spec.daily.op == "atLeast" && dailyTarget != null) spec = spec.copy(daily = DailyRule.atLeast(dailyTarget))
        if (template.spec.daily.op == "cap" && dailyCap != null) spec = spec.copy(daily = DailyRule.cap(dailyCap))
        if (goal != null && template.spec.goal != null && template.spec.daily.op != "all") spec = spec.copy(goal = Goal(goal))
        return spec
    }
}

data class CreateChallengeState(
    val templates: List<ChallengeTemplate> = emptyList(),
    val draft: WizardDraft? = null,
    val preview: MemberResult? = null,
    val problem: String? = null,
    val working: Boolean = false,
    val error: String? = null,
    val createdId: String? = null,
)

@HiltViewModel
class CreateChallengeViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val challenges: ChallengeRepository,
    private val daily: DailySummaryRepository,
) : ViewModel() {
    private val groupId: String = checkNotNull(savedState["groupId"])
    private val _state = MutableStateFlow(CreateChallengeState(templates = challenges.templates))
    val state: StateFlow<CreateChallengeState> = _state.asStateFlow()

    fun pick(template: ChallengeTemplate) {
        val today = LocalDate.now()
        val conditions = template.spec.daily.conditions.orEmpty()
        val draft = WizardDraft(
            template = template,
            title = template.name,
            start = template.defaultStart(today),
            durationDays = template.durationDays,
            weekly = template.spec.window?.recurrence == "WEEKLY",
            dailyTarget = template.spec.daily.value,
            dailyCap = template.spec.daily.max,
            goal = template.spec.goal?.target,
            bedtimeMinutes = conditions.firstOrNull { it.metric == "bedtime" }?.let { ((it.value + (it.toleranceMin ?: 0.0)).toInt() + 1440) % 1440 } ?: (23 * 60),
            minSleepMinutes = conditions.firstOrNull { it.metric == "sleep_minutes" }?.value?.toInt() ?: 420,
            days = template.spec.window?.days?.toSet() ?: emptySet(),
            kind = template.spec.kind,
            metricId = template.spec.metric.id,
            dailyOp = template.spec.daily.op.takeIf { it != "all" } ?: "value",
            aggregate = template.spec.aggregate,
        )
        recompute(draft)
    }

    fun back() = _state.update { it.copy(draft = null, preview = null, problem = null) }

    fun update(transform: (WizardDraft) -> WizardDraft) {
        recompute(transform(_state.value.draft ?: return))
    }

    private fun recompute(draft: WizardDraft) {
        val spec = runCatching { draft.toSpec() }.getOrNull()
        // A valid spec has no problem (null); only a draft that can't become a spec at all does.
        _state.update { it.copy(draft = draft, problem = if (spec == null) "Check the settings." else ChallengeEngine.problem(spec)) }
        if (spec != null) preview(spec)
    }

    /** "With last week's data you'd have…": the same rules run on the user's own recent days. */
    private fun preview(spec: ChallengeSpec) {
        viewModelScope.launch {
            val days = ChronoUnit.DAYS.between(spec.window.startDate, spec.window.endDate) + 1
            val end = LocalDate.now().minusDays(1)
            val start = end.minusDays(days - 1)
            val shifted = spec.copy(window = spec.window.copy(start = start.toString(), end = end.toString()))
            val summaries = runCatching { daily.recent(days + 1) }.getOrDefault(emptyList())
            val result = ChallengeEngine.evaluateMember(shifted, summaries).result
            _state.update { it.copy(preview = if (summaries.isEmpty()) null else result) }
        }
    }

    fun create() {
        val spec = _state.value.draft?.toSpec() ?: return
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            try {
                val id = challenges.create(groupId, spec)
                _state.update { it.copy(createdId = id) }
            } catch (e: Exception) {
                _state.update { it.copy(error = (e as? RemoteError)?.message ?: e.message ?: "Couldn't create the challenge") }
            }
            _state.update { it.copy(working = false) }
        }
    }

    val metrics = ChallengeMetric.PICKABLE
}

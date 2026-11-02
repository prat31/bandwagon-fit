package fit.bandwagon.feature.health

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fit.bandwagon.core.data.repository.ProfileRepository
import fit.bandwagon.core.health.HealthCapabilitiesRepository
import fit.bandwagon.core.health.store.DayValue
import fit.bandwagon.core.health.store.HealthRepository
import fit.bandwagon.core.model.Bucket
import fit.bandwagon.core.model.ChartPeriod
import fit.bandwagon.core.model.Goals
import fit.bandwagon.core.model.HealthCapabilities
import fit.bandwagon.core.model.HealthCategory
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.HealthMath
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.LocalDateRange
import fit.bandwagon.core.model.MetricKind
import fit.bandwagon.core.model.MetricPoint
import fit.bandwagon.core.model.PersonalRange
import fit.bandwagon.core.model.Reading
import fit.bandwagon.core.model.SleepSelection
import fit.bandwagon.core.model.SleepSession
import fit.bandwagon.core.model.UserSettings
import fit.bandwagon.core.model.WorkoutSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

object HealthRoutes {
    const val HOME = "health"
    const val METRIC = "health/metric/{metric}"
    const val SLEEP = "health/sleep"
    const val WORKOUTS = "health/workouts"

    fun metric(metric: HealthMetric) = "health/metric/${metric.name}"
}

/** Metrics shown in the overview, per category, in display order. */
internal fun overviewMetrics(category: HealthCategory, settings: UserSettings, caps: HealthCapabilities): List<HealthMetric> = when (category) {
    HealthCategory.ACTIVITY -> buildList {
        add(if (settings.pushesInsteadOfSteps) HealthMetric.WHEELCHAIR_PUSHES else HealthMetric.STEPS)
        add(if (caps.granted(HealthDataType.ACTIVITY_INTENSITY)) HealthMetric.ACTIVE_MINUTES else HealthMetric.WORKOUT_MINUTES)
        addAll(listOf(HealthMetric.DISTANCE, HealthMetric.ACTIVE_CALORIES, HealthMetric.TOTAL_CALORIES, HealthMetric.FLOORS, HealthMetric.ELEVATION, HealthMetric.VO2_MAX, HealthMetric.POWER))
        if (!settings.pushesInsteadOfSteps) add(HealthMetric.WHEELCHAIR_PUSHES)
    }
    HealthCategory.SLEEP -> emptyList()
    HealthCategory.HEART -> listOf(
        HealthMetric.RESTING_HEART_RATE, HealthMetric.HEART_RATE, HealthMetric.HRV, HealthMetric.SPO2, HealthMetric.RESPIRATORY_RATE,
        HealthMetric.SKIN_TEMPERATURE, HealthMetric.BODY_TEMPERATURE, HealthMetric.BLOOD_PRESSURE, HealthMetric.BLOOD_GLUCOSE,
    )
    HealthCategory.BODY -> listOf(HealthMetric.WEIGHT, HealthMetric.BODY_FAT, HealthMetric.LEAN_BODY_MASS, HealthMetric.BODY_WATER, HealthMetric.BONE_MASS, HealthMetric.BMR, HealthMetric.HEIGHT)
    HealthCategory.NUTRITION -> listOf(HealthMetric.ENERGY_EATEN, HealthMetric.HYDRATION)
    HealthCategory.WELLNESS -> listOf(HealthMetric.MINDFULNESS)
}

data class Tile(
    val metric: HealthMetric,
    /** Today's total, today's average, or the latest reading. */
    val value: Double?,
    val secondary: Double? = null,
    /** Date of the latest reading when it isn't today. */
    val asOf: LocalDate? = null,
    val last7: List<Double?> = emptyList(),
    val changePct: Double? = null,
    val goal: Double? = null,
)

data class HealthOverviewState(
    val loading: Boolean = true,
    val capabilities: HealthCapabilities = HealthCapabilities.NONE,
    val settings: UserSettings = UserSettings(),
    val goals: Goals = Goals(),
    val tiles: Map<HealthCategory, List<Tile>> = emptyMap(),
    val lastNight: SleepSession? = null,
    val workoutsThisWeek: List<WorkoutSummary> = emptyList(),
)

@HiltViewModel
class HealthOverviewViewModel @Inject constructor(
    private val health: HealthRepository,
    private val capabilities: HealthCapabilitiesRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HealthOverviewState())
    val state: StateFlow<HealthOverviewState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            profiles.observeProfile().catch { }.collect { p ->
                if (p != null) _state.update { it.copy(settings = p.settings, goals = p.goals) }
            }
        }
        viewModelScope.launch {
            capabilities.capabilities.collect { caps ->
                val changed = caps != _state.value.capabilities
                _state.update { it.copy(capabilities = caps) }
                if (changed) load()
            }
        }
    }

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            capabilities.refresh()
            load(force)
        }
    }

    private suspend fun load(force: Boolean = false) {
        val caps = _state.value.capabilities
        if (!caps.anyGranted) {
            _state.update { it.copy(loading = false, tiles = emptyMap()) }
            return
        }
        val settings = _state.value.settings
        val goals = _state.value.goals
        val limit = Semaphore(4)
        val tiles = coroutineScope {
            HealthCategory.entries.associateWith { category ->
                overviewMetrics(category, settings, caps).filter { caps.granted(it.dataType) }.map { metric ->
                    async { limit.withPermit { tileFor(metric, goals, force) } }
                }
            }.mapValues { (_, jobs) -> jobs.awaitAll().filterNotNull() }
        }
        val today = LocalDate.now()
        val lastNight = if (caps.granted(HealthDataType.SLEEP)) {
            SleepSelection.mainSession(runCatching { health.sleep(LocalDateRange(today.minusDays(1), today)) }.getOrDefault(emptyList())
                .filter { it.end.atZone(ZoneId.systemDefault()).toLocalDate() == today })
        } else null
        val workouts = if (caps.granted(HealthDataType.EXERCISE)) {
            runCatching { health.workouts(LocalDateRange.lastDays(today, 7)) }.getOrDefault(emptyList())
        } else emptyList()
        _state.update { it.copy(loading = false, tiles = tiles, lastNight = lastNight, workoutsThisWeek = workouts) }
    }

    private suspend fun tileFor(metric: HealthMetric, goals: Goals, force: Boolean): Tile? {
        val days = runCatching { health.daily(metric, 35, force) }.getOrDefault(emptyList())
        if (days.all { it.value == null }) return null
        val last7 = days.takeLast(7).map { it.value }
        if (metric == HealthMetric.BLOOD_PRESSURE) {
            val latest = runCatching { health.readings(metric, LocalDateRange.lastDays(LocalDate.now(), 35)) }.getOrDefault(emptyList()).lastOrNull()
                ?: return null
            return Tile(metric, latest.value, latest.secondary, latest.time.atZone(ZoneId.systemDefault()).toLocalDate(), last7)
        }
        return if (metric.kind == MetricKind.READING) {
            val latest = days.last { it.value != null }
            Tile(metric, latest.value, asOf = latest.date.takeIf { it != LocalDate.now() }, last7 = last7)
        } else {
            val week = last7.filterNotNull()
            val before = days.dropLast(7).mapNotNull { it.value }
            val today = days.last().value
            val shown = if (metric.kind == MetricKind.SAMPLE && today == null) days.lastOrNull { it.value != null }?.value else today
            val goal = when (metric) {
                HealthMetric.ACTIVE_MINUTES, HealthMetric.WORKOUT_MINUTES -> goals.activeMinutesWeekly / 7.0
                else -> goals.forKey(metric.goalKey)
            }
            Tile(
                metric = metric,
                value = shown,
                asOf = if (today == null && shown != null) days.lastOrNull { it.value != null }?.date else null,
                last7 = last7,
                changePct = if (week.size >= 3 && before.size >= 7) HealthMath.changePercent(week.average(), before.average()) else null,
                goal = goal,
            )
        }
    }
}

data class DetailStats(val headline: Double?, val headlineLabel: String, val min: Double?, val max: Double?, val total: Double?, val average: Double?)

data class MetricDetailState(
    val metric: HealthMetric,
    val period: ChartPeriod = ChartPeriod.WEEK,
    val points: List<MetricPoint> = emptyList(),
    val readings: List<Reading> = emptyList(),
    val range: PersonalRange? = null,
    val baseline: Double? = null,
    val goal: Double? = null,
    val stats: DetailStats? = null,
    val sources: List<String> = emptyList(),
    val selected: Int? = null,
    val loading: Boolean = true,
    val granted: Boolean = true,
    val macros: Triple<Double, Double, Double>? = null,
)

@HiltViewModel
class MetricDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val health: HealthRepository,
    private val capabilities: HealthCapabilitiesRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    val metric: HealthMetric = HealthMetric.valueOf(checkNotNull(savedState["metric"]))
    private val _state = MutableStateFlow(MetricDetailState(metric))
    val state: StateFlow<MetricDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun select(period: ChartPeriod) {
        _state.update { it.copy(period = period, selected = null) }
        load()
    }

    fun selectBar(index: Int?) = _state.update { it.copy(selected = index) }

    private fun load() {
        viewModelScope.launch {
            val caps = capabilities.capabilities.value
            val granted = caps.granted(metric.dataType)
            _state.update { it.copy(loading = true, granted = granted) }
            if (!granted) return@launch _state.update { it.copy(loading = false) }
            val period = _state.value.period
            val today = LocalDate.now()
            val range = LocalDateRange.lastDays(today, period.days)
            val goals = runCatching { profiles.profile()?.goals }.getOrNull() ?: Goals()

            val points = runCatching { health.series(metric, range, period.bucket) }.getOrDefault(emptyList())
            val readings = if (metric.kind == MetricKind.READING || metric == HealthMetric.HRV) {
                runCatching { health.readings(metric, range) }.getOrDefault(emptyList())
            } else emptyList()

            // Personal range from the last 60 days of daily values, for sample and reading metrics.
            val personal = if (metric.kind != MetricKind.CUMULATIVE || metric == HealthMetric.SLEEP) {
                val daily = runCatching { health.daily(metric, 60) }.getOrDefault(emptyList()).mapNotNull(DayValue::value)
                HealthMath.personalRange(daily)
            } else null

            // Baseline for "vs usual": previous periods of the same length.
            val previous = LocalDateRange(range.start.minusDays(period.days * if (period == ChartPeriod.WEEK) 4 else 1), range.start.minusDays(1))
            val baselinePoints = runCatching { health.series(metric, previous, if (period.bucket == Bucket.HOUR) Bucket.DAY else period.bucket) }.getOrDefault(emptyList())
            val baseline = baselinePoints.mapNotNull { it.value }.takeIf { it.isNotEmpty() }?.average()

            val values = points.mapNotNull { it.value }
            val cumulative = metric.kind == MetricKind.CUMULATIVE && metric != HealthMetric.SLEEP
            val stats = DetailStats(
                headline = when {
                    values.isEmpty() -> null
                    period == ChartPeriod.DAY && cumulative -> values.sum()
                    metric.kind == MetricKind.READING -> readings.lastOrNull()?.value ?: values.last()
                    else -> values.average()
                },
                headlineLabel = when {
                    period == ChartPeriod.DAY && cumulative -> "Today"
                    metric.kind == MetricKind.READING -> "Latest"
                    cumulative && period.bucket != Bucket.DAY -> "Weekly / monthly average"
                    cumulative -> "Daily average"
                    else -> "Average"
                },
                min = values.minOrNull(),
                max = values.maxOrNull(),
                total = if (cumulative) values.sum() else null,
                average = values.takeIf { it.isNotEmpty() }?.average(),
            )

            val macros = if (metric == HealthMetric.ENERGY_EATEN) {
                val p = runCatching { health.series(HealthMetric.PROTEIN, range, Bucket.DAY) }.getOrDefault(emptyList()).mapNotNull { it.value }.sum()
                val c = runCatching { health.series(HealthMetric.CARBS, range, Bucket.DAY) }.getOrDefault(emptyList()).mapNotNull { it.value }.sum()
                val f = runCatching { health.series(HealthMetric.FAT, range, Bucket.DAY) }.getOrDefault(emptyList()).mapNotNull { it.value }.sum()
                Triple(p, c, f).takeIf { p + c + f > 0 }
            } else null

            val sources = (points.flatMap { it.origins } + readings.map { it.origin }).filter { it.isNotBlank() }.distinct().map(::appLabel)
            val goal = when (metric) {
                HealthMetric.ACTIVE_MINUTES, HealthMetric.WORKOUT_MINUTES -> if (period.bucket == Bucket.DAY) goals.activeMinutesWeekly / 7.0 else null
                else -> if (period.bucket == Bucket.DAY || period.bucket == Bucket.WEEK) goals.forKey(metric.goalKey) else null
            }
            _state.update {
                it.copy(
                    points = points,
                    readings = readings,
                    range = personal,
                    baseline = baseline,
                    goal = goal,
                    stats = stats,
                    sources = sources,
                    macros = macros,
                    loading = false,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { pkg }
}

data class SleepDetailState(
    val period: ChartPeriod = ChartPeriod.WEEK,
    val nights: List<Pair<LocalDate, SleepSession>> = emptyList(),
    val lastNight: SleepSession? = null,
    val goalMinutes: Int = Goals().sleepMinutes,
    val loading: Boolean = true,
    val granted: Boolean = true,
    val sleepingHeartRate: Double? = null,
)

@HiltViewModel
class SleepDetailViewModel @Inject constructor(
    private val health: HealthRepository,
    private val capabilities: HealthCapabilitiesRepository,
    private val profiles: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SleepDetailState())
    val state: StateFlow<SleepDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun select(period: ChartPeriod) {
        _state.update { it.copy(period = period) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val caps = capabilities.capabilities.value
            if (!caps.granted(HealthDataType.SLEEP)) return@launch _state.update { it.copy(loading = false, granted = false) }
            val zone = ZoneId.systemDefault()
            val days = maxOf(_state.value.period.days, 7)
            val today = LocalDate.now()
            val sessions = runCatching { health.sleep(LocalDateRange.lastDays(today, days)) }.getOrDefault(emptyList())
            val nights = sessions.groupBy { it.end.atZone(zone).toLocalDate() }
                .mapValues { (_, list) -> SleepSelection.mainSession(list)!! }
                .toList().sortedBy { it.first }
            val last = nights.lastOrNull()?.second
            val hr = if (last != null && caps.granted(HealthDataType.HEART_RATE)) {
                runCatching {
                    health.readings(HealthMetric.HEART_RATE, LocalDateRange(last.start.atZone(zone).toLocalDate(), last.end.atZone(zone).toLocalDate()))
                        .filter { it.time in last.start..last.end }.map { it.value }.minOrNull()
                }.getOrNull()
            } else null
            val goal = runCatching { profiles.profile()?.goals?.sleepMinutes }.getOrNull() ?: Goals().sleepMinutes
            _state.update { it.copy(nights = nights, lastNight = last, goalMinutes = goal, loading = false, sleepingHeartRate = hr) }
        }
    }
}

data class WorkoutsState(val workouts: List<WorkoutSummary> = emptyList(), val loading: Boolean = true)

@HiltViewModel
class WorkoutsViewModel @Inject constructor(private val health: HealthRepository) : ViewModel() {
    private val _state = MutableStateFlow(WorkoutsState())
    val state: StateFlow<WorkoutsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val list = runCatching { health.workouts(LocalDateRange.lastDays(LocalDate.now(), 30)) }.getOrDefault(emptyList())
            _state.update { it.copy(workouts = list, loading = false) }
        }
    }
}

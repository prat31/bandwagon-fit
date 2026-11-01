package fit.bandwagon.tools.hcseeder

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ActivityIntensityRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import androidx.health.connect.client.units.Volume
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sin
import kotlin.random.Random
import kotlin.reflect.KClass

/**
 * Debug-only seeder. Writes realistic data for every type the Health tab shows, so the app can be
 * tested on an emulator with no wearable: varied days, sleep with stages, vitals around a stable
 * baseline, and a manual step entry that competitive features must exclude.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SeederScreen() } }
    }
}

private val ALWAYS = listOf(
    StepsRecord::class, DistanceRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class,
    FloorsClimbedRecord::class, ElevationGainedRecord::class, ExerciseSessionRecord::class, SleepSessionRecord::class,
    HeartRateRecord::class, RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
    OxygenSaturationRecord::class, RespiratoryRateRecord::class, WeightRecord::class, HeightRecord::class,
    BodyFatRecord::class, LeanBodyMassRecord::class, BasalMetabolicRateRecord::class, BloodPressureRecord::class,
    BloodGlucoseRecord::class, HydrationRecord::class, NutritionRecord::class, Vo2MaxRecord::class,
)

private fun available(client: HealthConnectClient, feature: Int) =
    client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

private fun recordTypes(client: HealthConnectClient): List<KClass<out Record>> = buildList {
    addAll(ALWAYS)
    if (available(client, HealthConnectFeatures.FEATURE_ACTIVITY_INTENSITY)) add(ActivityIntensityRecord::class)
    if (available(client, HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE)) add(SkinTemperatureRecord::class)
    if (available(client, HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION)) add(MindfulnessSessionRecord::class)
}

private enum class Profile(val label: String) { FULL("Seed 45 days, every type"), TODAY("Seed today only"), SLEEP("Seed 14 nights of sleep only") }

@Composable
private fun SeederScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { HealthConnectClient.getOrCreate(context) }
    var status by remember { mutableStateOf("Ready") }
    var pending by remember { mutableStateOf<Profile?>(null) }
    val writePermissions = remember { recordTypes(client).map { HealthPermission.getWritePermission(it) }.toSet() }

    fun run(profile: Profile) {
        scope.launch { status = runCatching { seed(context, client, profile) }.getOrElse { "Failed: ${it.message}" } }
    }

    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val profile = pending
        if (profile != null && granted.isNotEmpty()) run(profile) else status = "Write permissions were not granted"
    }

    fun start(profile: Profile) {
        scope.launch {
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(writePermissions)) run(profile) else {
                pending = profile
                launcher.launch(writePermissions)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Health Connect seeder", style = MaterialTheme.typography.headlineSmall)
        Text(status)
        Profile.entries.forEach { profile -> Button(onClick = { start(profile) }) { Text(profile.label) } }
        OutlinedButton(onClick = {
            scope.launch { status = runCatching { clear(client) }.getOrElse { "Failed: ${it.message}" } }
        }) { Text("Delete everything this seeder wrote") }
    }
}

private suspend fun clear(client: HealthConnectClient): String {
    val range = TimeRangeFilter.between(Instant.now().minus(Duration.ofDays(400)), Instant.now().plus(Duration.ofDays(1)))
    recordTypes(client).forEach { runCatching { client.deleteRecords(it, range) } }
    return "Deleted seeded records"
}

private suspend fun seed(context: Context, client: HealthConnectClient, profile: Profile): String {
    val zone = ZoneId.systemDefault()
    val now = Instant.now()
    val types = recordTypes(client).toSet()
    val device = Device(type = Device.TYPE_WATCH)
    val auto = { Metadata.autoRecorded(device) }
    val records = mutableListOf<Record>()
    // Health Connect validates when a record is built and rejects future times, so skip those.
    fun add(build: () -> Record) {
        runCatching(build).getOrNull()?.let { records += it }
    }
    val days = when (profile) {
        Profile.FULL -> 45
        Profile.TODAY -> 1
        Profile.SLEEP -> 14
    }
    val offsetOf = { t: Instant -> zone.rules.getOffset(t) }

    for (index in 0 until days) {
        val date = LocalDate.now(zone).minusDays(index.toLong())
        val rnd = Random(date.toEpochDay())
        val dayStart = date.atStartOfDay(zone).toInstant()
        fun at(hours: Double): Instant = dayStart.plusSeconds((hours * 3600).toLong())
        val weekend = date.dayOfWeek.value >= 6
        val wave = sin(index / 5.0)

        // Sleep ending on this date: bedtime 22:30-00:30, 6h-8h30, with stage cycles.
        val bedtime = at(-1.5 + rnd.nextDouble() * 2.0 + if (weekend) 0.5 else 0.0)
        val wake = bedtime.plusSeconds(((6.0 + rnd.nextDouble() * 2.5) * 3600).toLong())
        val stages = mutableListOf<SleepSessionRecord.Stage>()
        var t = bedtime
        var cycle = 0
        while (t < wake) {
            val plan = listOf(
                SleepSessionRecord.STAGE_TYPE_LIGHT to 25 + rnd.nextInt(15),
                SleepSessionRecord.STAGE_TYPE_DEEP to (if (cycle < 2) 30 else 10) + rnd.nextInt(10),
                SleepSessionRecord.STAGE_TYPE_LIGHT to 15 + rnd.nextInt(10),
                SleepSessionRecord.STAGE_TYPE_REM to (if (cycle < 2) 12 else 28) + rnd.nextInt(10),
                SleepSessionRecord.STAGE_TYPE_AWAKE to 2 + rnd.nextInt(5),
            )
            for ((stage, minutes) in plan) {
                val end = minOf(t.plusSeconds(minutes * 60L), wake)
                if (end > t) stages += SleepSessionRecord.Stage(t, end, stage)
                t = end
            }
            cycle++
        }
        add { SleepSessionRecord(bedtime, offsetOf(bedtime), wake, offsetOf(wake), auto(), stages = stages) }
        if (profile == Profile.SLEEP) continue

        // Steps in hourly blocks, more on active days; one manual entry competitive features drop.
        val dailySteps = (7_500 + wave * 2_500 + rnd.nextInt(-1500, 2500) + if (weekend) 1500 else 0).toInt().coerceAtLeast(1500)
        val hours = (7..21).toList()
        val weights = hours.map { h -> if (h in 8..9 || h in 12..13 || h in 18..19) 3.0 else 1.0 }
        hours.forEachIndexed { i, h ->
            val count = (dailySteps * weights[i] / weights.sum()).toLong()
            val start = at(h.toDouble())
            val end = at(h + 0.9)
            add { StepsRecord(start, offsetOf(start), end, offsetOf(end), count, auto()) }
            add { DistanceRecord(start, offsetOf(start), end, offsetOf(end), Length.meters(count * 0.76), auto()) }
            add { ActiveCaloriesBurnedRecord(start, offsetOf(start), end, offsetOf(end), Energy.kilocalories(count * 0.045), auto()) }
            if (h % 3 == 0) {
                val floors = rnd.nextInt(0, 4).toDouble()
                add { FloorsClimbedRecord(start, offsetOf(start), end, offsetOf(end), floors, auto()) }
                add { ElevationGainedRecord(start, offsetOf(start), end, offsetOf(end), Length.meters(floors * 3), auto()) }
            }
        }
        add { StepsRecord(at(21.0), offsetOf(at(21.0)), at(21.5), offsetOf(at(21.5)), 9_999, Metadata.manualEntry(device)) }
        add { TotalCaloriesBurnedRecord(at(0.0), offsetOf(at(0.0)), at(23.99), offsetOf(at(23.99)), Energy.kilocalories(2100.0 + dailySteps * 0.045), auto()) }

        // A workout most days: run, walk or yoga.
        if (rnd.nextDouble() < 0.7) {
            val kind = listOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_YOGA)[rnd.nextInt(3)]
            val start = at(6.5 + rnd.nextDouble())
            val end = start.plusSeconds((20 + rnd.nextInt(40)) * 60L)
            add { ExerciseSessionRecord(start, offsetOf(start), end, offsetOf(end), auto(), kind, title = null) }
            if (ActivityIntensityRecord::class in types) {
                val vigorous = kind == ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                add { ActivityIntensityRecord(
                    start, offsetOf(start), end, offsetOf(end), auto(),
                    if (vigorous) ActivityIntensityRecord.ACTIVITY_INTENSITY_TYPE_VIGOROUS else ActivityIntensityRecord.ACTIVITY_INTENSITY_TYPE_MODERATE,
                ) }
            }
        }
        if (ActivityIntensityRecord::class in types) {
            val start = at(17.5)
            val end = start.plusSeconds((10 + rnd.nextInt(25)) * 60L)
            add { ActivityIntensityRecord(start, offsetOf(start), end, offsetOf(end), auto(), ActivityIntensityRecord.ACTIVITY_INTENSITY_TYPE_MODERATE) }
        }

        // Heart rate every 10 minutes while awake, lower overnight.
        val samples = mutableListOf<HeartRateRecord.Sample>()
        var s = dayStart
        while (s < dayStart.plus(Duration.ofDays(1))) {
            val hour = s.atZone(zone).hour
            val base = if (hour < 7) 54 else if (hour in 8..9 || hour in 18..19) 92 else 72
            samples += HeartRateRecord.Sample(s, (base + rnd.nextInt(-6, 8)).toLong())
            s = s.plus(Duration.ofMinutes(10))
        }
        add { HeartRateRecord(dayStart, offsetOf(dayStart), dayStart.plus(Duration.ofDays(1)).minusSeconds(1), offsetOf(dayStart), samples, auto()) }
        val rhr = (56 + wave * 2 + rnd.nextInt(-2, 3) + if (index in 2..4) 6 else 0).toLong()
        add { RestingHeartRateRecord(wake, offsetOf(wake), rhr, auto()) }
        add { HeartRateVariabilityRmssdRecord(wake.minusSeconds(1800), offsetOf(wake), 48 + wave * 6 + rnd.nextDouble(-5.0, 5.0), auto()) }
        add { OxygenSaturationRecord(wake.minusSeconds(3600), offsetOf(wake), Percentage(96.0 + rnd.nextDouble(-1.0, 2.0)), auto()) }
        add { RespiratoryRateRecord(wake.minusSeconds(3600), offsetOf(wake), 14.5 + rnd.nextDouble(-1.0, 1.0), auto()) }
        if (SkinTemperatureRecord::class in types) {
            add { SkinTemperatureRecord(
                bedtime, offsetOf(bedtime), wake, offsetOf(wake), auto(),
                deltas = listOf(SkinTemperatureRecord.Delta(bedtime.plusSeconds(3600), TemperatureDelta.celsius(rnd.nextDouble(-0.4, 0.4)))),
                baseline = Temperature.celsius(33.5),
            ) }
        }

        // Body: daily weight with water noise and a slow downward trend.
        val weigh = at(7.3)
        add { WeightRecord(weigh, offsetOf(weigh), Mass.kilograms(76.0 + index * 0.02 + rnd.nextDouble(-0.6, 0.6)), auto()) }
        if (index % 7 == 0) {
            add { BodyFatRecord(weigh, offsetOf(weigh), Percentage(21.0 + rnd.nextDouble(-0.5, 0.5)), auto()) }
            add { LeanBodyMassRecord(weigh, offsetOf(weigh), Mass.kilograms(59.5), auto()) }
            add { BasalMetabolicRateRecord(weigh, offsetOf(weigh), Power.kilocaloriesPerDay(1680.0), auto()) }
            add { Vo2MaxRecord(weigh, offsetOf(weigh), auto(), 42.0 + rnd.nextDouble(-1.0, 1.0), Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO) }
        }
        if (index == days - 1) add { HeightRecord(weigh, offsetOf(weigh), Length.meters(1.75), Metadata.manualEntry(device)) }
        if (index % 3 == 0) {
            val bp = at(8.0)
            add { BloodPressureRecord(
                bp, offsetOf(bp), Metadata.manualEntry(device),
                Pressure.millimetersOfMercury(118.0 + rnd.nextInt(-6, 12)), Pressure.millimetersOfMercury(77.0 + rnd.nextInt(-4, 8)),
            ) }
        }
        listOf(7.5, 13.5, 20.0).forEach { h ->
            val g = at(h)
            add { BloodGlucoseRecord(
                g, offsetOf(g), Metadata.manualEntry(device), BloodGlucose.milligramsPerDeciliter(95.0 + rnd.nextInt(-15, 60)),
                relationToMeal = if (h < 8) BloodGlucoseRecord.RELATION_TO_MEAL_FASTING else BloodGlucoseRecord.RELATION_TO_MEAL_AFTER_MEAL,
            ) }
        }

        // Nutrition and hydration.
        listOf(8.5 to 450.0, 13.0 to 700.0, 19.5 to 750.0).forEach { (h, kcal) ->
            val start = at(h)
            val end = start.plusSeconds(1800)
            add { NutritionRecord(
                start, offsetOf(start), end, offsetOf(end), Metadata.manualEntry(device),
                energy = Energy.kilocalories(kcal + rnd.nextInt(-80, 120)),
                protein = Mass.grams(kcal * 0.05), totalCarbohydrate = Mass.grams(kcal * 0.12), totalFat = Mass.grams(kcal * 0.035),
            ) }
        }
        repeat(6) { i ->
            val start = at(8.0 + i * 2)
            add { HydrationRecord(start, offsetOf(start), start.plusSeconds(60), offsetOf(start), Volume.milliliters(250.0 + rnd.nextInt(0, 150)), Metadata.manualEntry(device)) }
        }
        if (MindfulnessSessionRecord::class in types && rnd.nextDouble() < 0.6) {
            val start = at(21.5)
            val end = start.plusSeconds((5 + rnd.nextInt(15)) * 60L)
            add { MindfulnessSessionRecord(start, offsetOf(start), end, offsetOf(end), auto(), MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION) }
        }
    }

    // Health Connect rejects records that haven't started or ended yet.
    val past = records.filter { timeOf(it, "getStartTime") <= now && timeOf(it, "getEndTime") <= now }
    past.chunked(500).forEach { client.insertRecords(it) }
    return "Inserted ${past.size} records for $days day(s)"
}

/** A record's start or end; instantaneous records use their single time. Unknown means "future". */
private fun timeOf(record: Record, getter: String): Instant {
    fun get(name: String) = runCatching { record.javaClass.getMethod(name).invoke(record) as? Instant }.getOrNull()
    return get(getter) ?: get("getTime") ?: Instant.MAX
}

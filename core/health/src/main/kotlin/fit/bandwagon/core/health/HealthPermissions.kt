package fit.bandwagon.core.health

import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ActivityIntensityRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
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
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import fit.bandwagon.core.model.HealthDataType
import kotlin.reflect.KClass

/**
 * The single map from platform-agnostic data types to Health Connect. [ENTRIES] holds only the
 * types this release requests ([HealthDataType.REQUESTED]); each must also be declared in the app
 * manifest and in the Play Console health declaration.
 */
object HealthPermissions {
    data class Entry(
        val record: KClass<out Record>,
        /** Health Connect feature that must be available, or null when always present. */
        val feature: Int? = null,
    ) {
        val permission: String get() = HealthPermission.getReadPermission(record)
    }

    private val ALL: Map<HealthDataType, Entry> = mapOf(
        HealthDataType.STEPS to Entry(StepsRecord::class),
        HealthDataType.DISTANCE to Entry(DistanceRecord::class),
        HealthDataType.ACTIVE_CALORIES to Entry(ActiveCaloriesBurnedRecord::class),
        HealthDataType.TOTAL_CALORIES to Entry(TotalCaloriesBurnedRecord::class),
        HealthDataType.EXERCISE to Entry(ExerciseSessionRecord::class),
        HealthDataType.FLOORS to Entry(FloorsClimbedRecord::class),
        HealthDataType.ELEVATION to Entry(ElevationGainedRecord::class),
        HealthDataType.ACTIVITY_INTENSITY to Entry(ActivityIntensityRecord::class, HealthConnectFeatures.FEATURE_ACTIVITY_INTENSITY),
        HealthDataType.VO2_MAX to Entry(Vo2MaxRecord::class),
        HealthDataType.WHEELCHAIR_PUSHES to Entry(WheelchairPushesRecord::class),
        HealthDataType.SPEED to Entry(SpeedRecord::class),
        HealthDataType.POWER to Entry(PowerRecord::class),
        HealthDataType.SLEEP to Entry(SleepSessionRecord::class),
        HealthDataType.HEART_RATE to Entry(HeartRateRecord::class),
        HealthDataType.RESTING_HEART_RATE to Entry(RestingHeartRateRecord::class),
        HealthDataType.HRV to Entry(HeartRateVariabilityRmssdRecord::class),
        HealthDataType.SPO2 to Entry(OxygenSaturationRecord::class),
        HealthDataType.RESPIRATORY_RATE to Entry(RespiratoryRateRecord::class),
        HealthDataType.SKIN_TEMPERATURE to Entry(SkinTemperatureRecord::class, HealthConnectFeatures.FEATURE_SKIN_TEMPERATURE),
        HealthDataType.BODY_TEMPERATURE to Entry(BodyTemperatureRecord::class),
        HealthDataType.BLOOD_PRESSURE to Entry(BloodPressureRecord::class),
        HealthDataType.BLOOD_GLUCOSE to Entry(BloodGlucoseRecord::class),
        HealthDataType.WEIGHT to Entry(WeightRecord::class),
        HealthDataType.HEIGHT to Entry(HeightRecord::class),
        HealthDataType.BODY_FAT to Entry(BodyFatRecord::class),
        HealthDataType.LEAN_BODY_MASS to Entry(LeanBodyMassRecord::class),
        HealthDataType.BMR to Entry(BasalMetabolicRateRecord::class),
        HealthDataType.BODY_WATER to Entry(BodyWaterMassRecord::class),
        HealthDataType.BONE_MASS to Entry(BoneMassRecord::class),
        HealthDataType.NUTRITION to Entry(NutritionRecord::class),
        HealthDataType.HYDRATION to Entry(HydrationRecord::class),
        HealthDataType.MINDFULNESS to Entry(MindfulnessSessionRecord::class, HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION),
    )

    val ENTRIES: Map<HealthDataType, Entry> = ALL.filterKeys { it in HealthDataType.REQUESTED }

    const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun permissionOf(type: HealthDataType): String = ENTRIES.getValue(type).permission

    fun typeOf(record: KClass<out Record>): HealthDataType? = ENTRIES.entries.firstOrNull { it.value.record == record }?.key

    /**
     * Permission strings for a request. Unsupported types are left out: asking for them makes the
     * Health Connect dialog fail on older versions.
     */
    fun request(types: Collection<HealthDataType>, supported: Set<HealthDataType>, special: Boolean = true): Set<String> =
        buildSet {
            types.filter { it in supported && it in ENTRIES }.forEach { add(permissionOf(it)) }
            if (special) add(BACKGROUND)
        }
}

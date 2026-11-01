package fit.bandwagon.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import fit.bandwagon.core.model.HealthCategory
import fit.bandwagon.core.model.HealthMetric
import fit.bandwagon.core.model.MetricUnit
import kotlin.math.roundToInt

/** Colour by meaning (AGENTS.md): activity and progress are Mango; other categories use chart tokens. */
@Composable
fun categoryColor(category: HealthCategory): Color {
    val ext = BandwagonTheme.extendedColors
    return when (category) {
        HealthCategory.ACTIVITY -> MaterialTheme.colorScheme.secondary
        HealthCategory.SLEEP -> ext.sleepDeep
        HealthCategory.HEART -> ext.heart
        HealthCategory.BODY -> ext.body
        HealthCategory.NUTRITION -> ext.nutrition
        HealthCategory.WELLNESS -> ext.mindful
    }
}

@Composable
fun metricColor(metric: HealthMetric): Color = when (metric) {
    HealthMetric.SPO2, HealthMetric.RESPIRATORY_RATE, HealthMetric.SKIN_TEMPERATURE, HealthMetric.BODY_TEMPERATURE,
    HealthMetric.BLOOD_GLUCOSE -> BandwagonTheme.extendedColors.vitals
    HealthMetric.HYDRATION -> BandwagonTheme.extendedColors.vitals
    else -> categoryColor(metric.category)
}

fun metricIcon(metric: HealthMetric): ImageVector = when (metric) {
    HealthMetric.STEPS -> Icons.Filled.DirectionsWalk
    HealthMetric.WHEELCHAIR_PUSHES -> Icons.Filled.Accessible
    HealthMetric.DISTANCE -> Icons.Filled.Straighten
    HealthMetric.ACTIVE_MINUTES -> Icons.Filled.Bolt
    HealthMetric.WORKOUT_MINUTES -> Icons.Filled.FitnessCenter
    HealthMetric.ACTIVE_CALORIES, HealthMetric.TOTAL_CALORIES -> Icons.Filled.LocalFireDepartment
    HealthMetric.FLOORS -> Icons.Filled.Stairs
    HealthMetric.ELEVATION -> Icons.Filled.Terrain
    HealthMetric.VO2_MAX -> Icons.Filled.DirectionsRun
    HealthMetric.SPEED, HealthMetric.POWER -> Icons.Filled.Speed
    HealthMetric.SLEEP -> Icons.Filled.Bedtime
    HealthMetric.HEART_RATE, HealthMetric.RESTING_HEART_RATE -> Icons.Filled.Favorite
    HealthMetric.HRV -> Icons.Filled.MonitorHeart
    HealthMetric.SPO2 -> Icons.Filled.Air
    HealthMetric.RESPIRATORY_RATE -> Icons.Filled.Air
    HealthMetric.SKIN_TEMPERATURE, HealthMetric.BODY_TEMPERATURE -> Icons.Filled.Thermostat
    HealthMetric.BLOOD_PRESSURE -> Icons.Filled.MonitorHeart
    HealthMetric.BLOOD_GLUCOSE -> Icons.Filled.Bloodtype
    HealthMetric.WEIGHT, HealthMetric.BODY_FAT, HealthMetric.LEAN_BODY_MASS, HealthMetric.BODY_WATER,
    HealthMetric.BONE_MASS, HealthMetric.HEIGHT -> Icons.Filled.MonitorWeight
    HealthMetric.BMR -> Icons.Filled.LocalFireDepartment
    HealthMetric.HYDRATION -> Icons.Filled.LocalDrink
    HealthMetric.ENERGY_EATEN, HealthMetric.PROTEIN, HealthMetric.CARBS, HealthMetric.FAT -> Icons.Filled.Restaurant
    HealthMetric.MINDFULNESS -> Icons.Filled.SelfImprovement
}

/** Values are stored in SI-ish units (meters, kg, °C, ml, minutes); this formats for the user. */
object MetricFormat {
    fun unitLabel(metric: HealthMetric, imperial: Boolean = usesImperial()): String = when (metric.unit) {
        MetricUnit.COUNT -> if (metric == HealthMetric.STEPS) "steps" else if (metric == HealthMetric.WHEELCHAIR_PUSHES) "pushes" else ""
        MetricUnit.METERS -> if (metric == HealthMetric.ELEVATION) (if (imperial) "ft" else "m") else if (imperial) "mi" else "km"
        MetricUnit.KCAL -> "kcal"
        MetricUnit.MINUTES -> if (metric == HealthMetric.SLEEP) "" else "min"
        MetricUnit.BPM -> "bpm"
        MetricUnit.MILLISECONDS -> "ms"
        MetricUnit.PERCENT -> "%"
        MetricUnit.BREATHS_PER_MIN -> "br/min"
        MetricUnit.CELSIUS_DELTA, MetricUnit.CELSIUS -> if (imperial) "°F" else "°C"
        MetricUnit.KILOGRAMS -> if (imperial) "lb" else "kg"
        MetricUnit.METERS_HEIGHT -> if (imperial) "in" else "cm"
        MetricUnit.MMHG -> "mmHg"
        MetricUnit.MG_DL -> "mg/dL"
        MetricUnit.MILLILITERS -> if (imperial) "fl oz" else "L"
        MetricUnit.GRAMS -> "g"
        MetricUnit.ML_KG_MIN -> "ml/kg/min"
        MetricUnit.FLOORS -> "floors"
        MetricUnit.KCAL_PER_DAY -> "kcal/day"
    }

    /** The number alone, already converted to display units. */
    fun value(metric: HealthMetric, value: Double, imperial: Boolean = usesImperial()): String = when (metric.unit) {
        MetricUnit.METERS -> if (metric == HealthMetric.ELEVATION) {
            formatNumber(if (imperial) value * 3.28084 else value)
        } else {
            formatNumber(if (imperial) value / 1609.344 else value / 1000, 1)
        }
        MetricUnit.MINUTES -> if (metric == HealthMetric.SLEEP) formatDuration(value.roundToInt()) else formatNumber(value)
        MetricUnit.CELSIUS_DELTA -> (if (value >= 0) "+" else "") + formatNumber(if (imperial) value * 1.8 else value, 1)
        MetricUnit.CELSIUS -> formatNumber(if (imperial) value * 1.8 + 32 else value, 1)
        MetricUnit.KILOGRAMS -> formatNumber(if (imperial) value * 2.20462 else value, 1)
        MetricUnit.METERS_HEIGHT -> formatNumber(if (imperial) value * 39.3701 else value * 100)
        MetricUnit.MILLILITERS -> if (imperial) formatNumber(value / 29.5735) else formatNumber(value / 1000, 1)
        MetricUnit.PERCENT, MetricUnit.BREATHS_PER_MIN, MetricUnit.ML_KG_MIN -> formatNumber(value, 1)
        else -> formatNumber(value)
    }

    fun full(metric: HealthMetric, value: Double, imperial: Boolean = usesImperial()): String {
        val unit = unitLabel(metric, imperial)
        return if (unit.isEmpty()) value(metric, value, imperial) else "${value(metric, value, imperial)} $unit"
    }
}

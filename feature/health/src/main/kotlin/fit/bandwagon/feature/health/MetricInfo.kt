package fit.bandwagon.feature.health

import fit.bandwagon.core.model.HealthMetric

/** "About this metric": what it is, how Bandwagon counts it, and the reference it uses. */
data class MetricInfo(val what: String, val how: String, val reference: String? = null)

object MetricInfos {
    fun of(metric: HealthMetric): MetricInfo = when (metric) {
        HealthMetric.STEPS -> MetricInfo(
            "Steps counted by your phone, watch or other device.",
            "Health Connect combines every source and removes overlaps by your source priority, so a phone and a watch never double-count.",
            "Large studies show health benefits rising to about 8,000–10,000 steps a day for adults under 60, and 6,000–8,000 above 60.",
        )
        HealthMetric.WHEELCHAIR_PUSHES -> MetricInfo("Wheelchair pushes recorded by your devices.", "Totals combined across sources by Health Connect.")
        HealthMetric.DISTANCE -> MetricInfo("Distance you walked, ran or cycled.", "The total your devices recorded, deduplicated by Health Connect. It isn't estimated from steps.")
        HealthMetric.ACTIVE_MINUTES -> MetricInfo(
            "Minutes of moderate or vigorous activity.",
            "Vigorous minutes count double, like heart points. When your devices don't record intensity, workout minutes are used instead.",
            "The WHO recommends 150–300 minutes of moderate (or 75–150 vigorous) activity a week.",
        )
        HealthMetric.WORKOUT_MINUTES -> MetricInfo("Time in recorded workouts.", "Workout sessions from Health Connect, clipped to each day.")
        HealthMetric.ACTIVE_CALORIES -> MetricInfo("Energy burned by movement, above what your body uses at rest.", "Combined across sources by Health Connect.")
        HealthMetric.TOTAL_CALORIES -> MetricInfo(
            "All energy burned, including at rest.",
            "Health Connect's total, which can already include your resting energy, so Bandwagon never adds it again.",
        )
        HealthMetric.FLOORS -> MetricInfo("Flights of stairs climbed.", "Combined across sources by Health Connect.")
        HealthMetric.ELEVATION -> MetricInfo("Height climbed.", "Combined across sources by Health Connect.")
        HealthMetric.VO2_MAX -> MetricInfo(
            "An estimate of cardio fitness: how much oxygen your body can use.",
            "The latest estimate your device wrote. It changes slowly over weeks.",
            "Typical ranges depend on age and sex; compare with your own trend.",
        )
        HealthMetric.SPEED -> MetricInfo("Speed during activities.", "Average of recorded speed samples.")
        HealthMetric.POWER -> MetricInfo("Power output, usually from cycling.", "Average of recorded power samples.")
        HealthMetric.SLEEP -> MetricInfo(
            "Time asleep each night.",
            "The longest sleep ending between noon the day before and 6 pm. Awake time inside it is left out. Naps don't count as the night.",
            "Adults need 7 hours or more.",
        )
        HealthMetric.HEART_RATE -> MetricInfo("Beats per minute through the day.", "Each bar runs from the lowest to the highest reading, with a dot at the average.")
        HealthMetric.RESTING_HEART_RATE -> MetricInfo(
            "Your heart rate fully at rest, usually measured overnight.",
            "The value your device calculates. The band is your usual range: your average ± one standard deviation over recent weeks.",
            "Lower usually means fitter. A few days above your range can follow illness, stress, alcohol or poor sleep.",
        )
        HealthMetric.HRV -> MetricInfo(
            "Variation between heartbeats (RMSSD), measured during sleep.",
            "Each night uses one device. The band is your usual range from at least 14 nights.",
            "Values differ a lot between people and devices, so only compare with yourself.",
        )
        HealthMetric.SPO2 -> MetricInfo("How much oxygen your blood carries.", "Overnight readings, one device per night.", "Typical readings are 95–100%. This isn't a medical test.")
        HealthMetric.RESPIRATORY_RATE -> MetricInfo("Breaths per minute, usually while asleep.", "Overnight readings, one device per night.", "A typical resting adult rate is 12–20 breaths a minute.")
        HealthMetric.SKIN_TEMPERATURE -> MetricInfo("How your skin temperature differs from your baseline.", "Your device's own baseline and nightly change. Zero is your normal.")
        HealthMetric.BODY_TEMPERATURE -> MetricInfo("Body temperature readings.", "Readings as recorded.")
        HealthMetric.BLOOD_PRESSURE -> MetricInfo(
            "Systolic over diastolic pressure.",
            "Each bar runs from diastolic up to systolic.",
            "Categories follow American Heart Association guidance. A category is not a diagnosis; talk to a doctor about your readings.",
        )
        HealthMetric.BLOOD_GLUCOSE -> MetricInfo(
            "Blood sugar readings.",
            "Readings as recorded, with meal timing when available. The band shows 70–180 mg/dL.",
            "Time in range uses the international consensus target for continuous monitors. Not medical advice.",
        )
        HealthMetric.WEIGHT -> MetricInfo(
            "Weight readings.",
            "Dots are readings. The line is a smoothed trend that filters out day-to-day water swings.",
        )
        HealthMetric.BODY_FAT -> MetricInfo("Body fat percentage.", "Latest readings.")
        HealthMetric.LEAN_BODY_MASS -> MetricInfo("Everything except fat.", "Latest readings.")
        HealthMetric.BODY_WATER -> MetricInfo("Water mass in the body.", "Latest readings.")
        HealthMetric.BONE_MASS -> MetricInfo("Bone mass.", "Latest readings.")
        HealthMetric.BMR -> MetricInfo("Energy your body uses at rest.", "Latest value your device or app recorded.")
        HealthMetric.HEIGHT -> MetricInfo("Height.", "Latest reading.")
        HealthMetric.HYDRATION -> MetricInfo("Water and drinks logged.", "Daily total from Health Connect.")
        HealthMetric.ENERGY_EATEN -> MetricInfo("Food energy logged.", "Daily total of nutrition entries.")
        HealthMetric.PROTEIN, HealthMetric.CARBS, HealthMetric.FAT -> MetricInfo("Macronutrients logged.", "Daily totals of nutrition entries.")
        HealthMetric.MINDFULNESS -> MetricInfo("Time spent in meditation and mindfulness sessions.", "Daily total from Health Connect.")
    }
}

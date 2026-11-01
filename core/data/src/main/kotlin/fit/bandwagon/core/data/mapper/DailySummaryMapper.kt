package fit.bandwagon.core.data.mapper

import fit.bandwagon.core.data.local.DailySummaryEntity
import fit.bandwagon.core.model.DailySummary
import fit.bandwagon.core.model.SleepSummary
import fit.bandwagon.core.model.WorkoutTypeTotal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate

object DailySummaryMapper {
    /**
     * The Firestore document. Exactly these fields leave the phone; a privacy test
     * checks no other health field is ever added here.
     */
    fun toFirestore(summary: DailySummary, timestamp: (Instant) -> Any): Map<String, Any?> = mapOf(
        "userId" to summary.userId,
        "date" to summary.date.toString(),
        "timeZone" to summary.timeZone,
        "steps" to summary.steps,
        "distanceMeters" to summary.distanceMeters,
        "activeCalories" to summary.activeCalories,
        "activeMinutes" to summary.activeMinutes,
        "workoutMinutes" to summary.workoutMinutes,
        "workoutCount" to summary.workoutCount,
        "workoutsByType" to summary.workoutsByType.mapValues { (_, v) ->
            mapOf("minutes" to v.minutes, "count" to v.count, "distanceMeters" to v.distanceMeters)
        },
        "floors" to summary.floors,
        "mindfulMinutes" to summary.mindfulMinutes,
        "sleep" to summary.sleep?.let {
            mapOf(
                "start" to it.start.toString(),
                "end" to it.end.toString(),
                "durationMinutes" to it.durationMinutes,
                "zoneId" to it.zoneId,
            )
        },
        "shared" to summary.shared.sorted(),
        "sources" to summary.sources,
        "manualExcluded" to summary.manualExcluded,
        "computedAt" to timestamp(summary.computedAt),
    )

    fun toEntity(summary: DailySummary): DailySummaryEntity = DailySummaryEntity(
        date = summary.date.toString(),
        userId = summary.userId,
        payload = Json.encodeToString(JsonElement.serializer(), toFirestore(summary) { it.toString() }.toJson()),
        computedAt = summary.computedAt.toEpochMilli(),
    )

    fun fromEntity(entity: DailySummaryEntity): DailySummary? = runCatching {
        val map = Json.parseToJsonElement(entity.payload).jsonObject.toPlain()
        fromMap(map, entity.userId)
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    fun fromMap(map: Map<String, Any?>, userId: String): DailySummary {
        fun num(key: String) = (map[key] as? Number)?.toDouble()
        val sleep = (map["sleep"] as? Map<String, Any?>)?.let {
            SleepSummary(
                start = Instant.parse(it["start"] as String),
                end = Instant.parse(it["end"] as String),
                durationMinutes = (it["durationMinutes"] as Number).toInt(),
                zoneId = it["zoneId"] as String,
            )
        }
        val workouts = (map["workoutsByType"] as? Map<String, Map<String, Any?>>)?.mapValues { (_, v) ->
            WorkoutTypeTotal(
                minutes = (v["minutes"] as? Number)?.toInt() ?: 0,
                count = (v["count"] as? Number)?.toInt() ?: 0,
                distanceMeters = (v["distanceMeters"] as? Number)?.toDouble(),
            )
        } ?: emptyMap()
        return DailySummary(
            userId = userId,
            date = LocalDate.parse(map["date"] as String),
            timeZone = map["timeZone"] as? String ?: "UTC",
            steps = num("steps")?.toLong(),
            distanceMeters = num("distanceMeters"),
            activeCalories = num("activeCalories"),
            activeMinutes = num("activeMinutes")?.toInt(),
            workoutMinutes = num("workoutMinutes")?.toInt(),
            workoutCount = num("workoutCount")?.toInt(),
            workoutsByType = workouts,
            floors = num("floors"),
            mindfulMinutes = num("mindfulMinutes")?.toInt(),
            sleep = sleep,
            shared = (map["shared"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet(),
            sources = (map["sources"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            manualExcluded = map["manualExcluded"] == true,
            computedAt = (map["computedAt"] as? String)?.let(Instant::parse) ?: Instant.EPOCH,
        )
    }
}

/** Values-only comparison, used to avoid uploading unchanged summaries. */
fun DailySummary.sameValuesAs(other: DailySummary?): Boolean =
    other != null && copy(computedAt = Instant.EPOCH) == other.copy(computedAt = Instant.EPOCH)

private fun JsonObject.toPlain(): Map<String, Any?> = mapValues { (_, v) -> v.toPlainValue() }

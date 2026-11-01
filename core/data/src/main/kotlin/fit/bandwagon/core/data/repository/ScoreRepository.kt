package fit.bandwagon.core.data.repository

import fit.bandwagon.core.data.local.DailyScoreDao
import fit.bandwagon.core.data.local.DailyScoreEntity
import fit.bandwagon.core.model.scores.DailyScore
import fit.bandwagon.core.model.scores.ScoreContributor
import fit.bandwagon.core.model.scores.ScoreKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Sleep and move scores, recorded per date on this phone so history and goals can use them. */
@Singleton
class ScoreRepository @Inject constructor(
    private val dao: DailyScoreDao,
    private val clock: Clock,
) {
    suspend fun record(scores: Map<LocalDate, DailyScore>) {
        if (scores.isEmpty()) return
        val now = clock.millis()
        dao.upsert(
            scores.map { (date, score) ->
                DailyScoreEntity(score.kind.name, date.toString(), score.value, encode(score.contributors), score.partial, now)
            },
        )
    }

    suspend fun range(kind: ScoreKind, start: LocalDate, end: LocalDate): Map<LocalDate, DailyScore> =
        dao.range(kind.name, start.toString(), end.toString()).associate { row ->
            LocalDate.parse(row.date) to DailyScore(kind, row.score, decode(row.contributors), row.partial)
        }

    suspend fun clear() = dao.clear()

    private fun encode(parts: List<ScoreContributor>): String = JsonArray(
        parts.map {
            JsonObject(mapOf("key" to JsonPrimitive(it.key), "label" to JsonPrimitive(it.label), "points" to JsonPrimitive(it.points), "max" to JsonPrimitive(it.max)))
        },
    ).toString()

    private fun decode(json: String): List<ScoreContributor> = runCatching {
        Json.parseToJsonElement(json).jsonArray.map { e ->
            val o = e.jsonObject
            ScoreContributor(o.getValue("key").jsonPrimitive.content, o.getValue("label").jsonPrimitive.content, o.getValue("points").jsonPrimitive.double, o.getValue("max").jsonPrimitive.double)
        }
    }.getOrDefault(emptyList())
}

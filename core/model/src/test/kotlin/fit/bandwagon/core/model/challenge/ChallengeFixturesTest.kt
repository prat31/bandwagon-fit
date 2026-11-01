package fit.bandwagon.core.model.challenge

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import fit.bandwagon.core.model.DailySummary
import fit.bandwagon.core.model.SleepSummary
import fit.bandwagon.core.model.WorkoutTypeTotal
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Runs the golden fixtures in /shared/fixtures/challenges, the same files the Cloud Functions tests
 * run, so the Kotlin and TypeScript engines can't drift apart.
 */
class ChallengeFixturesTest {
    private val shared = File(System.getProperty("bandwagon.shared") ?: "../../shared")
    private val json = ChallengeTemplates.json

    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.doubleOrNull

    private fun daily(obj: JsonObject): DailySummary {
        val sleep = (obj["sleep"] as? JsonObject)?.let {
            SleepSummary(
                start = Instant.parse(it["start"]!!.jsonPrimitive.content),
                end = Instant.parse(it["end"]!!.jsonPrimitive.content),
                durationMinutes = it["durationMinutes"]!!.jsonPrimitive.intOrNull!!,
                zoneId = it["zoneId"]!!.jsonPrimitive.content,
            )
        }
        val workouts = (obj["workoutsByType"] as? JsonObject)?.mapValues { (_, v) ->
            val o = v.jsonObject
            WorkoutTypeTotal(o["minutes"].num()!!.toInt(), o["count"].num()!!.toInt(), o["distanceMeters"].num())
        } ?: emptyMap()
        return DailySummary(
            userId = "u",
            date = LocalDate.parse(obj["date"]!!.jsonPrimitive.content),
            timeZone = "UTC",
            steps = obj["steps"].num()?.toLong(),
            distanceMeters = obj["distanceMeters"].num(),
            activeCalories = obj["activeCalories"].num(),
            activeMinutes = obj["activeMinutes"].num()?.toInt(),
            workoutMinutes = obj["workoutMinutes"].num()?.toInt(),
            workoutCount = obj["workoutCount"].num()?.toInt(),
            workoutsByType = workouts,
            floors = obj["floors"].num(),
            mindfulMinutes = obj["mindfulMinutes"].num()?.toInt(),
            sleep = sleep,
            shared = obj["shared"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
        )
    }

    private fun actual(result: MemberResult, key: String): Any? = when (key) {
        "score" -> result.score
        "tieBreak" -> result.tieBreak
        "progress" -> result.progress
        "daysHit" -> result.daysHit.toDouble()
        "daysWithData" -> result.daysWithData.toDouble()
        "longestStreak" -> result.longestStreak.toDouble()
        "finished" -> result.finished
        "finishedOn" -> result.finishedOn
        "notShared" -> result.notShared
        "flagged" -> result.flagged
        else -> error("Unknown expectation $key")
    }

    private fun expected(element: JsonElement): Any? = when {
        element is JsonNull -> null
        element is JsonPrimitive && element.isString -> element.content
        element is JsonPrimitive && element.booleanOrNull != null -> element.booleanOrNull
        element is JsonPrimitive -> element.doubleOrNull
        else -> error("Unsupported expectation $element")
    }

    @Test
    fun goldenFixtures() {
        val files = File(shared, "fixtures/challenges").listFiles { f -> f.extension == "json" }!!.sorted()
        assertThat(files).isNotEmpty()
        for (file in files) {
            val fixture = json.parseToJsonElement(file.readText()).jsonObject
            val spec = json.decodeFromJsonElement(ChallengeSpec.serializer(), fixture["spec"]!!)
            assertThat(ChallengeEngine.problem(spec)).isNull()

            val results = fixture["members"]!!.jsonObject.mapValues { (_, days) ->
                ChallengeEngine.evaluateMember(spec, days.jsonArray.map { daily(it.jsonObject) })
            }
            for ((memberId, expectations) in fixture["expected"]!!.jsonObject) {
                for ((key, value) in expectations.jsonObject) {
                    val got = actual(results.getValue(memberId).result, key)
                    val want = expected(value)
                    if (got is Double && want is Double) {
                        assertWithMessage("${file.name} $memberId.$key").that(got).isWithin(1e-9).of(want)
                    } else {
                        assertWithMessage("${file.name} $memberId.$key").that(got).isEqualTo(want)
                    }
                }
            }
            fixture["ranks"]?.jsonObject?.let { ranks ->
                val ranked = ChallengeEngine.rank(results.map { (id, r) -> Triple(id, r.result.score, r.result.tieBreak) })
                for ((memberId, rank) in ranks) {
                    assertWithMessage("${file.name} rank of $memberId").that(ranked.first { it.memberId == memberId }.rank).isEqualTo(rank.jsonPrimitive.intOrNull)
                }
            }
            fixture["days"]?.jsonObject?.let { days ->
                for ((memberId, byDate) in days) {
                    for ((date, status) in byDate.jsonObject) {
                        val got = results.getValue(memberId).days.firstOrNull { it.date == date }?.status?.name
                        assertWithMessage("${file.name} $memberId $date").that(got).isEqualTo(expected(status))
                    }
                }
            }
            fixture["teamTotal"]?.let { total ->
                assertThat(results.values.sumOf { it.result.score }).isWithin(1e-9).of(total.num()!!)
            }
        }
    }

    @Test
    fun everyTemplateBuildsAValidSpec() {
        val templates = ChallengeTemplates.parse(File(shared, "challenge-templates.json").readText())
        assertThat(templates.map { it.id }).contains("sleep_pact")
        for (template in templates) {
            val spec = template.toSpec(LocalDate.of(2026, 11, 2))
            assertWithMessage(template.id).that(ChallengeEngine.problem(spec)).isNull()
            assertThat(spec.display.templateId).isEqualTo(template.id)
        }
    }

    @Test
    fun weekendTemplateStartsOnTheNextSaturday() {
        val templates = ChallengeTemplates.parse(File(shared, "challenge-templates.json").readText())
        val weekend = templates.first { it.id == "weekend_warrior" }
        // 2026-11-01 is a Sunday.
        assertThat(weekend.defaultStart(LocalDate.of(2026, 11, 1))).isEqualTo(LocalDate.of(2026, 11, 7))
        assertThat(weekend.toSpec(LocalDate.of(2026, 11, 7)).window.end).isEqualTo("2026-11-08")
    }
}

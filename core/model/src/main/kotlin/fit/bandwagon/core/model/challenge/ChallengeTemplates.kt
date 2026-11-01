package fit.bandwagon.core.model.challenge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@Serializable
data class TemplateWindow(val days: List<Int>? = null, val recurrence: String? = null)

@Serializable
data class TemplateDisplay(val renderer: Renderer, val theme: Map<String, String>? = null)

/** A spec without dates or a title; the wizard fills those in. */
@Serializable
data class TemplateSpec(
    val kind: ChallengeKind,
    val metric: MetricRef,
    val daily: DailyRule,
    val aggregate: Aggregate,
    val goal: Goal? = null,
    val window: TemplateWindow? = null,
    val ranking: Ranking? = null,
    val groupStreak: Boolean = false,
    val display: TemplateDisplay,
)

/**
 * A preset from shared/challenge-templates.json. [fields] lists what the wizard lets the creator
 * change: duration, recurrence, dailyTarget, dailyCap, goal, bedtime, minSleep, days, and for
 * Custom also kind, metric, dailyRule and aggregate.
 */
@Serializable
data class ChallengeTemplate(
    val id: String,
    val name: String,
    val tagline: String,
    val icon: String,
    val durationDays: Int,
    /** Spec day (0 = Sunday) the challenge should start on, e.g. 6 for weekends. */
    val startOn: Int? = null,
    val fields: List<String> = emptyList(),
    val spec: TemplateSpec,
) {
    /** The next sensible start date: tomorrow, or the next matching weekday. */
    fun defaultStart(today: LocalDate): LocalDate {
        val tomorrow = today.plusDays(1)
        val day = startOn ?: return tomorrow
        val dow: DayOfWeek = day.toDayOfWeek()
        return tomorrow.with(TemporalAdjusters.nextOrSame(dow))
    }

    fun toSpec(
        start: LocalDate,
        durationDays: Int = this.durationDays,
        title: String = name,
        recurrence: String? = spec.window?.recurrence,
        days: List<Int>? = spec.window?.days,
    ): ChallengeSpec = ChallengeSpec(
        kind = spec.kind,
        metric = spec.metric,
        daily = spec.daily,
        aggregate = spec.aggregate,
        goal = spec.goal,
        window = ChallengeWindow(
            start = start.toString(),
            end = start.plusDays((durationDays - 1).toLong()).toString(),
            days = days,
            recurrence = recurrence,
        ),
        ranking = spec.ranking,
        groupStreak = spec.groupStreak,
        display = Display(templateId = id, renderer = spec.display.renderer, title = title, theme = spec.display.theme),
    )
}

@Serializable
data class TemplateCatalog(val schemaVersion: Int, val templates: List<ChallengeTemplate>)

object ChallengeTemplates {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    fun parse(text: String): List<ChallengeTemplate> = json.decodeFromString<TemplateCatalog>(text).templates
}

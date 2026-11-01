package fit.bandwagon.feature.challenges

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fit.bandwagon.core.model.Membership
import fit.bandwagon.core.model.canManageChallenges
import fit.bandwagon.core.model.challenge.ChallengeKind
import fit.bandwagon.core.model.challenge.ChallengePhase
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.BadgeChip
import fit.bandwagon.core.ui.BandwagonTheme
import fit.bandwagon.core.ui.SectionHeader
import fit.bandwagon.core.ui.charts.ProgressTrack
import fit.bandwagon.core.ui.formatShortDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The Challenges tab of a group: live, upcoming and finished, each with your own standing. */
@Composable
fun ChallengeListTab(
    groupId: String,
    membership: Membership?,
    onOpen: (String) -> Unit,
    viewModel: ChallengeListViewModel = hiltViewModel(key = "challenges-$groupId"),
) {
    LaunchedEffect(groupId, membership?.memberId) { viewModel.bind(groupId, membership?.memberId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val byPhase = state.cards.groupBy { it.challenge.phase(today) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.loaded && state.cards.isEmpty()) {
            AppCard {
                Text("No challenges yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (membership?.role?.canManageChallenges == true) "Start one with New challenge. Everyone in the group takes part."
                    else "The owner or a mod can start one. Everyone in the group takes part.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        listOf(ChallengePhase.LIVE to "Live", ChallengePhase.UPCOMING to "Upcoming", ChallengePhase.ENDED to "Finished").forEach { (phase, title) ->
            val cards = byPhase[phase].orEmpty()
            if (cards.isEmpty()) return@forEach
            SectionHeader(title)
            cards.forEach { card -> ChallengeCard(card, today, onClick = { onOpen(card.challenge.id) }) }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun ChallengeCard(card: ChallengeCardState, today: LocalDate, onClick: () -> Unit, groupName: String? = null) {
    val spec = card.challenge.spec
    AppCard(onClick = onClick) {
        if (spec == null) {
            Text("Update Bandwagon to see this challenge", style = MaterialTheme.typography.titleSmall)
            return@AppCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val template = spec.display.templateId
            Icon(templateIcon(iconFor(template)), contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(groupName, timing(card.challenge.phase(today), spec.window.startDate, spec.window.endDate, today)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (spec.window.recurrence == "WEEKLY") BadgeChip("Weekly")
        }
        Spacer(Modifier.height(10.dp))
        val score = card.myScore
        val summary = card.summary
        when {
            score?.notShared == true -> Text("Your data for this isn't shared.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            spec.kind == ChallengeKind.TEAM -> {
                val pct = ((summary?.pct ?: 0.0) / 100).toFloat()
                ProgressTrack(pct, MaterialTheme.colorScheme.secondary, description = "Team progress ${(pct * 100).toInt()} percent")
                Text("${formatScore(spec, summary?.total ?: 0.0)} of ${formatScore(spec, spec.goal?.target ?: 0.0)}", style = MaterialTheme.typography.labelMedium)
            }
            spec.kind == ChallengeKind.TARGET -> {
                val progress = (score?.progress ?: 0.0).toFloat()
                ProgressTrack(progress, if (score?.finished == true) BandwagonTheme.extendedColors.success else MaterialTheme.colorScheme.secondary, description = "Your progress ${(progress * 100).toInt()} percent")
                Text("You: ${formatScore(spec, score?.score ?: 0.0)} · ${summary?.finishedCount ?: 0} of ${summary?.participants ?: 0} done", style = MaterialTheme.typography.labelMedium)
            }
            else -> {
                val top = summary?.top?.firstOrNull { it.memberId == score?.memberId }
                Text(
                    listOfNotNull(
                        top?.let { "#${it.rank}" },
                        score?.let { "You: ${formatScore(spec, it.score)}" },
                        summary?.participants?.let { "$it taking part" },
                    ).joinToString(" · ").ifBlank { "No data yet" },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

internal fun iconFor(templateId: String): String = when (templateId) {
    "step_battle" -> "steps"
    "daily_showdown" -> "bolt"
    "weekend_warrior" -> "weekend"
    "workweek_hustle" -> "work"
    "consistency" -> "calendar"
    "active_league" -> "heart"
    "club_150" -> "target"
    "workout_streak" -> "fire"
    "run_club" -> "run"
    "floors_frenzy" -> "stairs"
    "distance_together" -> "route"
    "boss_battle" -> "boss"
    "sleep_bank" -> "sleep"
    "sleep_pact" -> "moon"
    "mindful_month" -> "spa"
    else -> "tune"
}

internal fun timing(phase: ChallengePhase, start: LocalDate, end: LocalDate, today: LocalDate): String = when (phase) {
    ChallengePhase.UPCOMING -> "Starts ${formatShortDate(start)}"
    ChallengePhase.LIVE -> {
        val left = ChronoUnit.DAYS.between(today, end)
        when {
            left <= 0L -> "Last day"
            left == 1L -> "1 day left"
            else -> "$left days left"
        }
    }
    ChallengePhase.ENDED -> "Ended ${formatShortDate(end)}"
    ChallengePhase.CANCELLED -> "Cancelled"
}

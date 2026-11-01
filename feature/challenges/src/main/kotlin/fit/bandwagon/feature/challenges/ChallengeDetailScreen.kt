package fit.bandwagon.feature.challenges

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fit.bandwagon.core.health.rememberShareHealth
import fit.bandwagon.core.model.Member
import fit.bandwagon.core.model.canManageChallenges
import fit.bandwagon.core.model.challenge.ChallengeEngine
import fit.bandwagon.core.model.challenge.ChallengeKind
import fit.bandwagon.core.model.challenge.ChallengeMetric
import fit.bandwagon.core.model.challenge.ChallengePhase
import fit.bandwagon.core.model.challenge.ChallengeSpec
import fit.bandwagon.core.model.challenge.DayStatus
import fit.bandwagon.core.model.challenge.MemberScore
import fit.bandwagon.core.model.challenge.Renderer
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.BandwagonTheme
import fit.bandwagon.core.ui.LoadingState
import fit.bandwagon.core.ui.MemberBadge
import fit.bandwagon.core.ui.NotSharedNote
import fit.bandwagon.core.ui.ScreenScaffold
import fit.bandwagon.core.ui.SectionHeader
import fit.bandwagon.core.ui.StaleLabel
import fit.bandwagon.core.ui.charts.ProgressTrack
import fit.bandwagon.core.ui.formatShortDate
import fit.bandwagon.core.ui.formatTime
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun ChallengeDetailScreen(onBack: () -> Unit, viewModel: ChallengeDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val share = rememberShareHealth()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }

    val challenge = state.challenge
    val spec = challenge?.spec
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { outer ->
        ScreenScaffold(
            title = spec?.title ?: "Challenge",
            onBack = onBack,
            actions = {
                if (spec != null) {
                    IconButton(onClick = {
                        val text = buildString {
                            append("${spec.title} on Bandwagon")
                            state.myRank?.let { append(": I'm #$it") }
                            state.myScore?.let { append(" with ${formatScore(spec, it.score)}") }
                        }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
                    }) { Icon(Icons.Filled.Share, "Share") }
                }
                if (state.membership?.role?.canManageChallenges == true && challenge?.phase(LocalDate.now()) in setOf(ChallengePhase.LIVE, ChallengePhase.UPCOMING)) {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Cancel challenge") }, onClick = { menu = false; confirmCancel = true })
                    }
                }
            },
        ) {
            if (challenge == null) return@ScreenScaffold LoadingState()
            if (spec == null) {
                Text("This challenge needs a newer version of Bandwagon. Update the app to see it.", modifier = Modifier.padding(24.dp))
                return@ScreenScaffold
            }
            val today = LocalDate.now()
            val phase = challenge.phase(today)
            Column(Modifier.padding(outer).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(describeRules(spec), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${formatShortDate(spec.window.startDate)} – ${formatShortDate(spec.window.endDate)} · ${timing(phase, spec.window.startDate, spec.window.endDate, today)}" +
                        if (spec.window.recurrence == "WEEKLY") " · repeats weekly" else "",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (state.myScore?.notShared == true) {
                    val types = ChallengeEngine.requiredMetrics(spec).mapNotNull { ChallengeMetric.of(it)?.dataType }.toSet()
                    AppCard { NotSharedNote(types.joinToString { it.label }, onShare = { share.request(types) }) }
                }
                if (state.summary?.finalised == true || phase == ChallengePhase.ENDED) ResultBanner(spec, state)

                when (spec.display.renderer) {
                    Renderer.BOSS -> BossView(spec, state)
                    Renderer.ROUTE -> RouteView(spec, state)
                    Renderer.PROGRESS -> if (spec.kind == ChallengeKind.TEAM) TeamProgress(spec, state) else TargetProgress(spec, state)
                    Renderer.BOARD -> BoardView(spec, state)
                    Renderer.LEADERBOARD -> Unit
                }

                SectionHeader(if (spec.kind == ChallengeKind.COMPETE) "Leaderboard" else "Everyone")
                MyRow(spec, state)
                Leaderboard(spec, state)
                if (state.leaderboard.size >= state.pageSize) {
                    TextButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth()) { Text("Show more") }
                }
                state.summary?.refreshedAt?.let {
                    Text("Updated ${fit.bandwagon.core.ui.formatAgo(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this challenge?") },
            text = { Text("It ends now for everyone, without results.") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; viewModel.cancel() }) { Text("Cancel challenge", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun ResultBanner(spec: ChallengeSpec, state: ChallengeDetailState) {
    val summary = state.summary ?: return
    Surface(color = BandwagonTheme.extendedColors.successContainer, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.EmojiEvents, null, tint = BandwagonTheme.extendedColors.onSuccessContainer, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            val text = when (spec.kind) {
                ChallengeKind.COMPETE -> {
                    val winners = summary.top.filter { it.rank == 1 }.mapNotNull { state.members[it.memberId]?.label }
                    if (winners.isEmpty()) "Challenge complete" else "Winner: ${winners.joinToString()}"
                }
                ChallengeKind.TEAM -> if ((summary.pct ?: 0.0) >= 100) "Goal reached together! 🎉" else "Got to ${(summary.pct ?: 0.0).roundToInt()}%. Try again?"
                ChallengeKind.TARGET -> "${summary.finishedCount} of ${summary.participants} reached the goal"
            }
            Text(text, style = MaterialTheme.typography.titleMedium, color = BandwagonTheme.extendedColors.onSuccessContainer)
        }
    }
}

@Composable
private fun TeamProgress(spec: ChallengeSpec, state: ChallengeDetailState) {
    val summary = state.summary
    val pct = ((summary?.pct ?: 0.0) / 100).toFloat()
    AppCard {
        Text("${(summary?.pct ?: 0.0).roundToInt()}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("${formatScore(spec, summary?.total ?: 0.0)} of ${formatScore(spec, spec.goal?.target ?: 0.0)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        ProgressTrack(pct, MaterialTheme.colorScheme.secondary, height = 20.dp, description = "Team at ${(pct * 100).roundToInt()} percent")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ChallengeEngine.MILESTONES.forEach { m ->
                val hit = m in (summary?.milestonesHit ?: emptyList())
                Text(
                    "$m%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hit) BandwagonTheme.extendedColors.onSuccessContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (hit) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        state.myScore?.let { Text("Your contribution: ${formatScore(spec, it.score)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun TargetProgress(spec: ChallengeSpec, state: ChallengeDetailState) {
    val score = state.myScore
    val progress = (score?.progress ?: 0.0).toFloat()
    AppCard {
        Text("Your progress", style = MaterialTheme.typography.labelLarge)
        Text(formatScore(spec, score?.score ?: 0.0), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("of ${formatScore(spec, spec.goal?.target ?: 0.0)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        ProgressTrack(progress, if (score?.finished == true) BandwagonTheme.extendedColors.success else MaterialTheme.colorScheme.secondary, height = 18.dp, description = "You're at ${(progress * 100).roundToInt()} percent")
        Text("${state.summary?.finishedCount ?: 0} of ${state.summary?.participants ?: 0} have reached the goal", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun BossView(spec: ChallengeSpec, state: ChallengeDetailState) {
    val summary = state.summary
    val target = spec.goal?.target ?: 1.0
    val left = (target - (summary?.total ?: 0.0)).coerceAtLeast(0.0)
    val hp = (left / target).toFloat()
    val boss = spec.display.theme?.get("boss") ?: "The Boss"
    AppCard {
        Text("🐉", style = MaterialTheme.typography.displayMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(boss, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(10.dp))
        ProgressTrack(hp, BandwagonTheme.extendedColors.heart, height = 20.dp, description = "$boss has ${(hp * 100).roundToInt()} percent health left")
        Text(
            if (left <= 0) "Defeated! 🎉" else "${fit.bandwagon.core.ui.formatNumber(left)} HP left · every step is a hit",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun RouteView(spec: ChallengeSpec, state: ChallengeDetailState) {
    val summary = state.summary
    val pct = ((summary?.pct ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
    val track = BandwagonTheme.extendedColors.track
    val color = MaterialTheme.colorScheme.secondary
    AppCard {
        Text("${formatScore(spec, summary?.total ?: 0.0)} of ${formatScore(spec, spec.goal?.target ?: 0.0)}", style = MaterialTheme.typography.titleMedium)
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, size.height * 0.8f)
                cubicTo(size.width * 0.25f, size.height * 0.1f, size.width * 0.5f, size.height * 1.1f, size.width * 0.75f, size.height * 0.3f)
                cubicTo(size.width * 0.85f, size.height * 0.05f, size.width * 0.95f, size.height * 0.3f, size.width, size.height * 0.2f)
            }
            drawPath(path, track, style = androidx.compose.ui.graphics.drawscope.Stroke(10.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            val measure = androidx.compose.ui.graphics.PathMeasure().apply { setPath(path, false) }
            val done = androidx.compose.ui.graphics.Path()
            measure.getSegment(0f, measure.length * pct, done, true)
            drawPath(done, color, style = androidx.compose.ui.graphics.drawscope.Stroke(10.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            val pos = measure.getPosition(measure.length * pct)
            drawCircle(color, 10.dp.toPx(), pos)
        }
        Text("Approximate distance from steps.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Sleep-pact style ✅/❌ board for last night, plus the group streak. "No data" is never a fail. */
@Composable
private fun BoardView(spec: ChallengeSpec, state: ChallengeDetailState) {
    val rows = state.today
    val hit = rows.count { it.status == DayStatus.HIT }
    val withData = rows.count { it.status != DayStatus.NOT_SHARED }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Last night", style = MaterialTheme.typography.labelLarge)
                Text("$hit of ${state.summary?.participants ?: withData} kept it", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            state.summary?.streak?.let {
                Column(horizontalAlignment = Alignment.End) {
                    Text("🔥 ${it.current}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary)
                    Text("group streak · best ${it.best}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (state.history.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                state.history.takeLast(14).forEach { day ->
                    val kept = ChallengeEngine.groupKept(day)
                    val withAny = day.hit + day.missed > 0
                    Box(
                        Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(
                            when {
                                kept -> BandwagonTheme.extendedColors.success
                                withAny -> MaterialTheme.colorScheme.secondaryContainer
                                else -> BandwagonTheme.extendedColors.track
                            },
                        ),
                    )
                }
            }
            Text("Last 14 nights: green when everyone with data kept it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        rows.sortedBy { state.members[it.memberId]?.label?.lowercase() }.forEach { row ->
            val member = state.members[row.memberId]
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MemberBadge(member, size = 32)
                Spacer(Modifier.width(10.dp))
                Text(member?.label ?: "Member", modifier = Modifier.weight(1f))
                row.bedtime?.let { t ->
                    Text("in bed ${formatTime(t.atZone(ZoneId.of(row.zoneId ?: "UTC")).toLocalTime())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when (row.status) {
                        DayStatus.HIT -> "✅"
                        DayStatus.MISSED -> "❌"
                        DayStatus.NOT_SHARED -> "—"
                    },
                )
            }
        }
        if (rows.isEmpty()) Text("No one has synced last night's sleep yet ⏳", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MyRow(spec: ChallengeSpec, state: ChallengeDetailState) {
    val score = state.myScore ?: return
    if (state.membership == null) return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(state.myRank?.let { "#$it" } ?: "–", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
            Text("You", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(formatScore(spec, score.score), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
    state.summary?.participants?.let {
        Text("of $it in the group", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Leaderboard(spec: ChallengeSpec, state: ChallengeDetailState) {
    val ranked = ChallengeEngine.rank(state.leaderboard.map { Triple(it.memberId, it.score, it.tieBreak) })
    val byId = state.leaderboard.associateBy { it.memberId }
    if (ranked.isEmpty()) {
        Text("No one has synced data for this yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ranked.forEach { row ->
        val member = state.members[row.memberId]
        val score: MemberScore = byId.getValue(row.memberId)
        val isMe = row.memberId == state.membership?.memberId
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(40.dp), contentAlignment = Alignment.CenterStart) {
                if (spec.kind == ChallengeKind.COMPETE && row.rank <= 3 && !score.notShared) {
                    Text(listOf("🥇", "🥈", "🥉")[row.rank - 1], style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(if (spec.kind == ChallengeKind.COMPETE) "${row.rank}" else "", style = MaterialTheme.typography.titleSmall)
                }
            }
            MemberBadge(member, size = 36)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text((member?.label ?: "Member") + if (isMe) " (you)" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal)
                StaleLabel(score.lastSyncAt)
            }
            Column(horizontalAlignment = Alignment.End) {
                when {
                    score.notShared -> Text("Not shared", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text(formatScore(spec, score.score), style = MaterialTheme.typography.titleSmall)
                }
                if (score.finished) Text("✓ goal", style = MaterialTheme.typography.labelSmall, color = BandwagonTheme.extendedColors.onSuccessContainer)
                if (score.flagged) Text("Some days left out", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

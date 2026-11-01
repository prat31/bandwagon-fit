@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package fit.bandwagon.feature.challenges

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fit.bandwagon.core.model.challenge.Aggregate
import fit.bandwagon.core.model.challenge.ChallengeKind
import fit.bandwagon.core.model.challenge.ChallengeMetric
import fit.bandwagon.core.model.challenge.toDayOfWeek
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.ErrorBanner
import fit.bandwagon.core.ui.ScreenScaffold
import fit.bandwagon.core.ui.SectionHeader
import fit.bandwagon.core.ui.brandFilterChipColors
import fit.bandwagon.core.ui.formatClockMinutes
import fit.bandwagon.core.ui.formatDate
import fit.bandwagon.core.ui.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Step 1: pick a template. Step 2: settings generated from its fields, with a live preview. */
@Composable
fun CreateChallengeScreen(onBack: () -> Unit, onCreated: (String) -> Unit, viewModel: CreateChallengeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdId) { state.createdId?.let(onCreated) }
    BackHandler(enabled = state.draft != null) { viewModel.back() }
    val draft = state.draft
    ScreenScaffold(title = if (draft == null) "New challenge" else draft.template.name, onBack = { if (draft != null) viewModel.back() else onBack() }) {
        if (draft == null) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Everyone in the group takes part automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.templates, key = { it.id }) { template ->
                    AppCard(onClick = { viewModel.pick(template) }) {
                        Icon(templateIcon(template.icon), contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(template.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(template.tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            return@ScreenScaffold
        }

        var pickDate by remember { mutableStateOf(false) }
        val fields = draft.template.fields.toSet()
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            state.error?.let { ErrorBanner(it) }
            OutlinedTextField(draft.title, { v -> viewModel.update { it.copy(title = v.take(60)) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            if ("kind" in fields) {
                SectionHeader("How it works")
                ChipRow(
                    listOf(ChallengeKind.COMPETE to "Compete", ChallengeKind.TARGET to "Everyone hits a target", ChallengeKind.TEAM to "One team goal"),
                    draft.kind,
                ) { k -> viewModel.update { it.copy(kind = k) } }
            }
            if ("metric" in fields) {
                SectionHeader("Metric")
                ChipRow(viewModel.metrics.map { it.id to it.label }, draft.metricId) { id -> viewModel.update { it.copy(metricId = id) } }
            }
            if ("dailyRule" in fields) {
                SectionHeader("Each day counts")
                ChipRow(listOf("value" to "Everything", "cap" to "Up to a cap", "atLeast" to "Days over a target"), draft.dailyOp) { op ->
                    viewModel.update { it.copy(dailyOp = op, aggregate = if (op == "atLeast") Aggregate.DAYS_HIT else if (it.aggregate == Aggregate.DAYS_HIT) Aggregate.SUM else it.aggregate) }
                }
            }
            if ("aggregate" in fields && draft.dailyOp != "atLeast") {
                SectionHeader("Score")
                ChipRow(
                    listOf(Aggregate.SUM to "Total", Aggregate.AVERAGE to "Daily average", Aggregate.BEST_DAY to "Best day"),
                    draft.aggregate,
                ) { a -> viewModel.update { it.copy(aggregate = a) } }
            }

            SectionHeader("When")
            OutlinedButton(onClick = { pickDate = true }) { Text("Starts ${formatDate(draft.start)}") }
            if ("duration" in fields) {
                Text("Length: ${draft.durationDays} ${if (draft.durationDays == 1) "day" else "days"}", style = MaterialTheme.typography.labelLarge)
                ChipRow(listOf(1 to "1 day", 3 to "3 days", 7 to "1 week", 14 to "2 weeks", 30 to "30 days"), draft.durationDays) { d -> viewModel.update { it.copy(durationDays = d) } }
            }
            if ("recurrence" in fields) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Repeat every week", modifier = Modifier.weight(1f))
                    Switch(checked = draft.weekly, onCheckedChange = { v -> viewModel.update { it.copy(weekly = v) } })
                }
            }
            if ("days" in fields) {
                Text("Which days count", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).map { it % 7 }.forEach { day ->
                        val on = draft.days.isEmpty() || day in draft.days
                        FilterChip(
                            selected = on,
                            onClick = {
                                viewModel.update { d ->
                                    val current = if (d.days.isEmpty()) (0..6).toSet() else d.days
                                    val next = if (day in current) current - day else current + day
                                    d.copy(days = if (next.size == 7) emptySet() else next.ifEmpty { current })
                                }
                            },
                            label = { Text(day.toDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                            colors = brandFilterChipColors(),
                        )
                    }
                }
            }

            if ("dailyTarget" in fields || ("dailyRule" in fields && draft.dailyOp == "atLeast")) {
                NumberSetting("Daily target (${unitOf(draft.metricId)})", draft.dailyTarget) { v -> viewModel.update { it.copy(dailyTarget = v) } }
            }
            if ("dailyCap" in fields || ("dailyRule" in fields && draft.dailyOp == "cap")) {
                NumberSetting("Daily cap (${unitOf(draft.metricId)})", draft.dailyCap) { v -> viewModel.update { it.copy(dailyCap = v) } }
            }
            if ("goal" in fields && (draft.template.id != "custom" || draft.kind != ChallengeKind.COMPETE)) {
                val unit = if (draft.aggregate == Aggregate.DAYS_HIT || draft.dailyOp == "atLeast") "days" else unitOf(draft.metricId)
                NumberSetting(if (draft.kind == ChallengeKind.TEAM) "Team goal ($unit)" else "Goal for each person ($unit)", draft.goal) { v -> viewModel.update { it.copy(goal = v) } }
            }
            if ("bedtime" in fields) {
                Text("In bed by ${formatClockMinutes(draft.bedtimeMinutes)}", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = ((draft.bedtimeMinutes - 20 * 60 + 1440) % 1440).toFloat(),
                    onValueChange = { v -> viewModel.update { it.copy(bedtimeMinutes = (20 * 60 + (v / 15).roundToInt() * 15) % 1440) } },
                    valueRange = 0f..(6 * 60f),
                )
            }
            if ("minSleep" in fields) {
                Text("Sleep at least ${formatDuration(draft.minSleepMinutes)}", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = draft.minSleepMinutes.toFloat(),
                    onValueChange = { v -> viewModel.update { it.copy(minSleepMinutes = (v / 15).roundToInt() * 15) } },
                    valueRange = 300f..600f,
                )
            }

            val spec = runCatching { draft.toSpec() }.getOrNull()
            if (spec != null) {
                AppCard {
                    Text("Rules", style = MaterialTheme.typography.titleSmall)
                    Text(describeRules(spec), style = MaterialTheme.typography.bodyMedium)
                    state.preview?.let { preview ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "With your last ${java.time.temporal.ChronoUnit.DAYS.between(spec.window.startDate, spec.window.endDate) + 1} days you'd have ${formatScore(spec, preview.score)}" +
                                if (preview.finished) ", goal reached." else ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(onClick = viewModel::create, enabled = state.problem == null && !state.working, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.working) "Starting…" else "Start challenge")
            }
        }

        if (pickDate) {
            val picker = rememberDatePickerState(initialSelectedDateMillis = draft.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { pickDate = false },
                confirmButton = {
                    TextButton(onClick = {
                        picker.selectedDateMillis?.let { ms ->
                            val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                            if (!date.isBefore(LocalDate.now().minusDays(3))) viewModel.update { it.copy(start = date) }
                        }
                        pickDate = false
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
            ) { DatePicker(picker) }
        }
    }
}

private fun unitOf(metricId: String): String = when (metricId) {
    "distance" -> "meters"
    "sleep_minutes" -> "minutes"
    else -> ChallengeMetric.of(metricId)?.unitLabel ?: ""
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) }, colors = brandFilterChipColors())
        }
    }
}

@Composable
private fun NumberSetting(label: String, value: Double?, onChange: (Double?) -> Unit) {
    var text by remember(label) { mutableStateOf(value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it.filter { c -> c.isDigit() || c == '.' }.take(9)
            onChange(text.toDoubleOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

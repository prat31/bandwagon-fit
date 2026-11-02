package fit.bandwagon.feature.you

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fit.bandwagon.core.health.openHealthConnectSettings
import fit.bandwagon.core.health.rememberShareHealth
import fit.bandwagon.core.model.Access
import fit.bandwagon.core.data.repository.ABOUT_MAX
import fit.bandwagon.core.model.Gender
import fit.bandwagon.core.model.HealthCategory
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.NotificationSection
import fit.bandwagon.core.model.NotificationType
import fit.bandwagon.core.model.UnitSystem
import fit.bandwagon.core.model.UserSettings
import fit.bandwagon.core.ui.AliasAvatarView
import fit.bandwagon.core.ui.AliasEditor
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.HandleField
import fit.bandwagon.core.ui.HandleStatus
import fit.bandwagon.core.ui.MemberAvatar
import fit.bandwagon.core.ui.ScreenScaffold
import fit.bandwagon.core.ui.SectionHeader
import fit.bandwagon.core.ui.brandFilterChipColors
import fit.bandwagon.core.ui.categoryColor
import fit.bandwagon.core.ui.formatAgo
import fit.bandwagon.core.ui.formatRelative
import java.time.LocalDate

object YouRoutes {
    const val HOME = "you"
    const val ALIAS = "you/alias"
    const val USERNAME = "you/username"
    const val HEALTH_DATA = "you/health-data"
    const val NOTIFICATIONS = "you/notifications"
}

@Composable
fun YouScreen(
    onSignedOut: () -> Unit,
    onEditAlias: () -> Unit,
    onEditUsername: () -> Unit,
    onHealthData: () -> Unit,
    onNotifications: () -> Unit,
    viewModel: YouViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editName by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var showShared by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::download)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val profile = state.profile
    androidx.compose.material3.Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("You", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MemberAvatar(profile?.displayName.orEmpty().ifBlank { "?" }, profile?.photoUrl, size = 64)
                    Column(Modifier.weight(1f)) {
                        Text(profile?.displayName.orEmpty().ifBlank { "Friend" }, style = MaterialTheme.typography.titleLarge)
                        Text(
                            profile?.username?.let { "@$it" } ?: "No username yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(onClick = onEditUsername),
                        )
                    }
                    IconButton(onClick = { editName = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit profile") }
                }
                val about = profile?.about
                if (about != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(about, style = MaterialTheme.typography.bodyMedium)
                } else if (profile != null) {
                    TextButton(onClick = { editName = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Add an About me") }
                }
            }

            AppCard(onClick = onEditAlias) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val alias = profile?.alias
                    if (alias != null) AliasAvatarView(alias.name, alias.avatar, 48) else Icon(Icons.Outlined.TheaterComedy, null, Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Anonymous identity", style = MaterialTheme.typography.titleMedium)
                        Text(
                            alias?.let { "@${it.name} · shown instead of your name in anonymous groups" }
                                ?: "Create an alias to join anonymous groups",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }

            SectionHeader("Health data")
            AppCard(onClick = onHealthData) {
                val caps = state.capabilities
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${caps.grantedTypes.size} of ${caps.supported.size} types shared", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (caps.background) "Syncs in the background" else "Updates when you open the app",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }

            SectionHeader("Preferences")
            AppCard {
                val settings = profile?.settings ?: UserSettings()
                val gender = Gender.of(settings.sex)
                Text("Gender", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Gender.entries.forEach { g ->
                        FilterChip(
                            selected = gender == g,
                            // Tapping the selected one clears it: gender is optional.
                            onClick = { viewModel.setGender(if (gender == g) null else g) },
                            label = { Text(g.label) },
                            colors = brandFilterChipColors(),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Units", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnitSystem.entries.forEach { unit ->
                        FilterChip(
                            selected = settings.units == unit,
                            onClick = { viewModel.setUnits(unit) },
                            label = { Text(unit.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            colors = brandFilterChipColors(),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().clickable(onClick = onNotifications).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notifications", style = MaterialTheme.typography.titleSmall)
                        val on = NotificationType.entries.count { settings.notificationEnabled(it) }
                        Text(
                            when (on) {
                                NotificationType.entries.size -> "All on"
                                0 -> "All off"
                                else -> "$on of ${NotificationType.entries.size} on"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
            }

            SectionHeader("Privacy")
            AppCard {
                Text("Your health data stays on this phone.", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { showShared = true }) { Text("What leaves this phone?") }
                TextButton(onClick = { download.launch("bandwagon-data-${LocalDate.now()}.json") }, enabled = !state.working) { Text("Download my data") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://bandwagon.fit/privacy"))) }) { Text("Privacy policy") }
            }

            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync", style = MaterialTheme.typography.titleSmall)
                        Text(formatRelative(profile?.lastSyncAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = viewModel::syncNow, enabled = !state.working) { Text("Sync now") }
                }
            }

            Text(
                "Signed in with Google",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete account", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editName && profile != null) {
        EditProfileDialog(
            displayName = profile.displayName,
            about = profile.about.orEmpty(),
            photoUrl = profile.photoUrl,
            accountPhotoUrl = viewModel.accountPhotoUrl,
            onDismiss = { editName = false },
            onSave = { name, about, photo ->
                viewModel.saveProfile(name, about, photo)
                editName = false
            },
        )
    }

    if (showShared) {
        AlertDialog(
            onDismissRequest = { showShared = false },
            title = { Text("What leaves this phone") },
            text = {
                Text(
                    "Only one daily total per day for: steps, distance, active calories, active and workout minutes, workouts by type, floors, mindful minutes, and last night's sleep time and bedtime. " +
                        "Groups see only what their challenges use.\n\n" +
                        "Heart, blood pressure, glucose, body, nutrition and sleep stages never leave this phone. Your email is never shown to anyone.",
                )
            },
            confirmButton = { TextButton(onClick = { showShared = false }) { Text("OK") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = { Text("This removes your profile, alias, daily summaries and group memberships. Groups you own pass to another member. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteAccount()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** A switch whose whole row, text included, flips it. */
@Composable
private fun ToggleRow(label: String, checked: Boolean, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun EditProfileDialog(
    displayName: String,
    about: String,
    photoUrl: String?,
    accountPhotoUrl: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, PhotoChange) -> Unit,
) {
    var name by remember { mutableStateOf(displayName) }
    var aboutText by remember { mutableStateOf(about) }
    var photo by remember { mutableStateOf<PhotoChange>(PhotoChange.Keep) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) photo = PhotoChange.Upload(uri)
    }
    val preview = when (val p = photo) {
        PhotoChange.Keep -> photoUrl
        PhotoChange.Remove -> null
        PhotoChange.UseAccount -> accountPhotoUrl
        is PhotoChange.Upload -> p.uri.toString()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit profile") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MemberAvatar(name.ifBlank { "?" }, preview, size = 72)
                    Column {
                        TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text(if (preview == null) "Add photo" else "Change photo")
                        }
                        if (preview != null) TextButton(onClick = { photo = PhotoChange.Remove }) { Text("Remove photo") }
                        if (accountPhotoUrl != null && preview != accountPhotoUrl) {
                            TextButton(onClick = { photo = PhotoChange.UseAccount }) { Text("Use account photo") }
                        }
                    }
                }
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    aboutText,
                    { aboutText = it.take(ABOUT_MAX) },
                    label = { Text("About me (optional)") },
                    placeholder = { Text("Early riser, weekend hiker…") },
                    supportingText = { Text("${aboutText.length}/$ABOUT_MAX · shown on your profile") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, aboutText, photo) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One toggle per kind of push, grouped as they reach you. Every change saves at once. */
@Composable
fun NotificationsScreen(onBack: () -> Unit, viewModel: YouViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = state.profile?.settings ?: UserSettings()
    var systemOn by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        systemOn = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    ScreenScaffold(title = "Notifications", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!systemOn) {
                AppCard {
                    Text("Notifications are off for Bandwagon", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Turn them on in your phone's settings, then choose what you get here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Open settings") }
                }
            }
            NotificationSection.entries.forEach { section ->
                SectionHeader(section.title)
                AppCard {
                    val types = NotificationType.entries.filter { it.section == section }
                    types.forEachIndexed { i, type ->
                        ToggleRow(type.title, settings.notificationEnabled(type), type.description) { viewModel.setNotification(type, it) }
                        if (i < types.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            SectionHeader("Delivery")
            AppCard {
                ToggleRow(
                    "Quiet hours",
                    settings.quietHoursEnabled,
                    "Nothing between ${settings.quietHoursStart} and ${settings.quietHoursEnd} in your time zone",
                ) { viewModel.setQuietHours(it) }
            }
            Text(
                "Changes save automatically.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun EditAliasScreen(onBack: () -> Unit, viewModel: YouViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Anonymous identity", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "One alias is used in all your anonymous groups. You can change the name once every 30 days; the avatar any time.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AliasEditor(state.aliasDraft, viewModel::onAliasDraft, state.avatarDraft, viewModel::onAvatarDraft, state.aliasStatus)
            Button(
                onClick = { viewModel.saveAlias(onBack) },
                enabled = !state.working && (state.aliasStatus == HandleStatus.Available || state.aliasDraft == state.profile?.alias?.name),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.profile?.alias == null) "Create alias" else "Save") }
        }
    }
}

@Composable
fun EditUsernameScreen(onBack: () -> Unit, viewModel: YouViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ScreenScaffold(title = "Username", onBack = onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Friends invite you by username. You can change it once every 30 days, and your old one stays reserved for 30 days.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HandleField(state.usernameDraft, viewModel::onUsernameDraft, "Username", state.usernameStatus)
            Button(
                onClick = { viewModel.saveUsername(onBack) },
                enabled = !state.working && state.usernameStatus == HandleStatus.Available && state.usernameDraft != state.profile?.username,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

/** Every data type with its status, grouped by category, plus where data comes from. */
@Composable
fun HealthDataScreen(onBack: () -> Unit, viewModel: YouViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val share = rememberShareHealth()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val caps = state.capabilities
    ScreenScaffold(title = "Health data", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (caps.notGranted.isNotEmpty()) {
                Button(onClick = { share.request(caps.notGranted) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (caps.canRequestAgain) "Share ${caps.notGranted.size} more types" else "Open Health Connect to share more")
                }
            }
            OutlinedButton(onClick = { openHealthConnectSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("Open Health Connect") }
            AppCard {
                StatusRow("Background sync", caps.background)
            }
            HealthCategory.entries.forEach { category ->
                val types = HealthDataType.REQUESTED.filter { it.category == category }
                SectionHeader(category.label)
                AppCard {
                    types.forEachIndexed { i, type ->
                        val access = caps.access[type] ?: Access.NOT_GRANTED
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (access == Access.GRANTED) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                contentDescription = null,
                                tint = if (access == Access.GRANTED) categoryColor(category) else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(12.dp))
                            Text(type.label, modifier = Modifier.weight(1f))
                            Text(
                                when (access) {
                                    Access.GRANTED -> "Shared"
                                    Access.NOT_GRANTED -> "Not shared"
                                    Access.UNSUPPORTED -> "Not on this phone"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (i < types.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            SectionHeader("Apps writing to Health Connect")
            AppCard {
                if (state.sources.isEmpty()) {
                    Text("No recent data from other apps yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.sources.forEach { source ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(source.label, modifier = Modifier.weight(1f))
                        Text(formatAgo(source.lastSeen), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, on: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Text(if (on) "On" else "Off", fontWeight = FontWeight.SemiBold)
    }
}

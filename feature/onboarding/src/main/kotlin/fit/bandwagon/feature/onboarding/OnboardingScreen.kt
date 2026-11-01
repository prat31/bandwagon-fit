package fit.bandwagon.feature.onboarding

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fit.bandwagon.core.health.rememberShareHealth
import fit.bandwagon.core.model.HealthCategory
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.SourceAvailability
import fit.bandwagon.core.ui.AliasEditor
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.BandwagonTheme
import fit.bandwagon.core.ui.ErrorBanner
import fit.bandwagon.core.ui.HandleField
import fit.bandwagon.core.ui.HandleStatus
import fit.bandwagon.core.ui.MemberAvatar
import fit.bandwagon.core.ui.TestSignInFields
import fit.bandwagon.core.ui.categoryColor

const val ONBOARDING_ROUTE = "onboarding"

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) { if (state.finished) onFinished() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshHealth() }

    // The Surface sets the content colour: without it untinted text is black on the black canvas.
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                Spacer(Modifier.height(120.dp))
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            val steps = listOf(OnboardingStep.USERNAME, OnboardingStep.PROFILE, OnboardingStep.ALIAS, OnboardingStep.HEALTH)
            if (state.step in steps) {
                LinearProgressIndicator(
                    progress = { (steps.indexOf(state.step) + 1f) / steps.size },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            state.error?.let { ErrorBanner(it, onRetry = viewModel::clearError) }

            when (state.step) {
                OnboardingStep.WELCOME -> WelcomeStep(state, viewModel)
                OnboardingStep.USERNAME -> UsernameStep(state, viewModel)
                OnboardingStep.PROFILE -> ProfileStep(state, viewModel)
                OnboardingStep.ALIAS -> AliasStep(state, viewModel)
                OnboardingStep.HEALTH -> HealthStep(state, viewModel)
                OnboardingStep.DONE -> CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun Title(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WelcomeStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val activity = LocalContext.current as Activity
    Spacer(Modifier.height(32.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(fit.bandwagon.core.ui.R.drawable.bandwagon_logo),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text("Bandwagon", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
    Title(
        "Get healthier together",
        "Track your day, see your health trends, and take on challenges with groups of friends, colleagues or anyone.",
    )
    Spacer(Modifier.height(8.dp))
    if (state.testSignInEnabled) {
        TestSignInFields(enabled = !state.working, onSignIn = viewModel::signInForTesting)
    } else {
        Button(onClick = { viewModel.signIn(activity) }, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
            Text("Continue with Google")
        }
    }
    Text(
        "We never show your email to anyone. Bandwagon is not a medical or emergency service.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun UsernameStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Title("Choose a username", "People find and invite you by your username. You can change it once a month.")
    HandleField(state.username, viewModel::onUsernameChange, "Username", state.usernameStatus, enabled = !state.working)
    Button(
        onClick = viewModel::claimUsername,
        enabled = state.usernameStatus == HandleStatus.Available && !state.working,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (state.working) "Saving…" else "Continue") }
}

@Composable
private fun ProfileStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Title("How should friends see you?", "Your name and photo show in groups that aren't anonymous.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MemberAvatar(state.displayName.ifBlank { "?" }, if (state.useAccountPhoto) state.accountPhotoUrl else null, size = 64)
        Column(Modifier.weight(1f)) {
            Text("@${state.username}", style = MaterialTheme.typography.titleMedium)
            if (state.accountPhotoUrl != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use my account photo", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = state.useAccountPhoto, onCheckedChange = viewModel::onUseAccountPhoto)
                }
            }
        }
    }
    OutlinedTextField(
        value = state.displayName,
        onValueChange = viewModel::onDisplayNameChange,
        label = { Text("Display name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = viewModel::saveProfile, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.working) "Saving…" else "Continue")
    }
}

@Composable
private fun AliasStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Title(
        "Want an anonymous identity?",
        "Some groups are anonymous: members see an alias instead of your name. You need one to join them. You can also create it later.",
    )
    AliasEditor(state.alias, viewModel::onAliasChange, state.aliasAvatar, viewModel::onAvatarChange, state.aliasStatus)
    Button(
        onClick = viewModel::saveAlias,
        enabled = state.aliasStatus == HandleStatus.Available && !state.working,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (state.working) "Saving…" else "Create alias") }
    TextButton(onClick = viewModel::skipAlias, modifier = Modifier.fillMaxWidth()) { Text("Skip for now") }
}

@Composable
private fun HealthStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val share = rememberShareHealth()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.finish() }
    val start = {
        val needsNotifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotifications) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.finish()
    }

    Title("Connect your health data", "Bandwagon reads Health Connect to show your trends and take part in challenges.")
    when (state.availability) {
        SourceAvailability.UNSUPPORTED -> AppCard {
            Text("Health Connect isn't available on this phone", style = MaterialTheme.typography.titleMedium)
            Text("You can still join groups; your data will show as not shared.", style = MaterialTheme.typography.bodyMedium)
        }
        SourceAvailability.UPDATE_REQUIRED -> AppCard {
            Text("Install Health Connect", style = MaterialTheme.typography.titleMedium)
            Text("On Android 9–13, Health Connect is a separate app. Install or update it to continue.")
            Button(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
            }) { Text("Open Play Store") }
        }
        else -> Unit
    }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Text(
                "Your health data stays on this phone. Only daily totals used in challenges (like steps or sleep time) are shared with groups you join.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    val caps = state.capabilities
    if (caps.anyGranted || state.healthAsked) {
        Text("What you shared", style = MaterialTheme.typography.titleMedium)
        HealthCategory.entries.forEach { category ->
            val types = HealthDataType.entries.filter { it.category == category && it in caps.supported }
            if (types.isEmpty()) return@forEach
            val granted = types.count { caps.granted(it) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    if (granted > 0) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (granted > 0) categoryColor(category) else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
                Text(category.label, modifier = Modifier.weight(1f))
                Text("$granted of ${types.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "You can change this any time in You → Health data.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (state.availability == SourceAvailability.AVAILABLE && caps.notGranted.isNotEmpty()) {
        Button(
            onClick = {
                viewModel.onHealthRequested()
                share.request()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (caps.anyGranted) "Share more" else "Continue") }
    }
    if (caps.anyGranted || state.healthAsked || state.availability != SourceAvailability.AVAILABLE) {
        Button(
            onClick = start,
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = BandwagonTheme.extendedColors.success,
                contentColor = BandwagonTheme.extendedColors.onSuccess,
            ),
        ) { Text(if (state.working) "Setting up…" else "Start using Bandwagon") }
    } else {
        TextButton(onClick = { viewModel.onHealthRequested() }, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    }
}

package fit.bandwagon.core.health

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fit.bandwagon.core.health.store.HealthRepository
import fit.bandwagon.core.model.HealthDataType
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShareHealthViewModel @Inject constructor(
    private val capabilities: HealthCapabilitiesRepository,
    private val memory: PermissionMemory,
    private val store: HealthRepository,
) : ViewModel() {
    private var requested: Set<String> = emptySet()
    private var grantedBefore: Set<String> = emptySet()

    /** Permission strings to ask for, or null when Health Connect won't show its dialog again. */
    fun prepare(types: Collection<HealthDataType>): Set<String>? {
        val caps = capabilities.capabilities.value
        if (!caps.canRequestAgain) return null
        val wanted = types.ifEmpty { HealthDataType.entries }
        grantedBefore = caps.grantedTypes.map(HealthPermissions::permissionOf).toSet() +
            listOfNotNull(HealthPermissions.HISTORY.takeIf { caps.history }, HealthPermissions.BACKGROUND.takeIf { caps.background })
        requested = HealthPermissions.request(wanted, caps.supported.ifEmpty { HealthDataType.entries.toSet() })
        return requested
    }

    fun onResult(grantedAfter: Set<String>) {
        memory.recordResult(requested, grantedBefore, grantedAfter)
        viewModelScope.launch {
            capabilities.refresh()
            store.invalidate()
        }
    }

    fun refresh() {
        viewModelScope.launch { capabilities.refresh() }
    }
}

/**
 * Asks for health data access. Empty `types` asks for everything the app reads; otherwise just the
 * missing ones for a section. Once Health Connect stops showing its dialog (two denials on
 * Android 14+), this opens Health Connect's own settings for the app instead.
 */
class ShareHealth internal constructor(private val start: (Collection<HealthDataType>) -> Unit) {
    fun request(types: Collection<HealthDataType> = emptyList()) = start(types)
}

@Composable
fun rememberShareHealth(viewModel: ShareHealthViewModel = hiltViewModel()): ShareHealth {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        viewModel.onResult(granted)
    }
    return remember(viewModel, launcher) {
        ShareHealth { types ->
            val permissions = viewModel.prepare(types)
            if (permissions == null) openHealthConnectSettings(context) else launcher.launch(permissions)
        }
    }
}

fun openHealthConnectSettings(context: Context) {
    val intent = runCatching { HealthConnectClient.getHealthConnectManageDataIntent(context) }
        .getOrElse { Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS") }
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

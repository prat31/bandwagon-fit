package fit.bandwagon.core.health

import fit.bandwagon.core.model.HealthCapabilities
import fit.bandwagon.core.model.SourceAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth for what the app may read. Refreshed on start, on every resume and
 * after every permission result; screens, sync and challenges all read from here.
 */
@Singleton
class HealthCapabilitiesRepository @Inject constructor(
    private val source: HealthDataSource,
) {
    private val _capabilities = MutableStateFlow(HealthCapabilities.NONE)
    val capabilities: StateFlow<HealthCapabilities> = _capabilities.asStateFlow()

    private val _availability = MutableStateFlow<SourceAvailability?>(null)
    val availability: StateFlow<SourceAvailability?> = _availability.asStateFlow()

    suspend fun refresh(): HealthCapabilities {
        _availability.value = runCatching { source.availability() }.getOrDefault(SourceAvailability.UNSUPPORTED)
        val caps = runCatching { source.capabilities() }.getOrDefault(HealthCapabilities.NONE)
        _capabilities.value = caps
        return caps
    }
}

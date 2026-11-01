package fit.bandwagon

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fit.bandwagon.core.data.repository.AuthRepository
import fit.bandwagon.core.health.HealthCapabilitiesRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the shared capability model fresh as the user moves around the app, and tells the shell
 * when a dead sign-in was ended so it can return to the sign-in screen.
 */
@HiltViewModel
class RootViewModel @Inject constructor(
    private val capabilities: HealthCapabilitiesRepository,
    auth: AuthRepository,
) : ViewModel() {
    val sessionExpired = auth.sessionExpired

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { capabilities.refresh() }
    }
}

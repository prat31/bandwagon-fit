package fit.bandwagon.feature.onboarding

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fit.bandwagon.core.data.remote.RemoteError
import fit.bandwagon.core.data.remote.isDeadSession
import fit.bandwagon.core.data.repository.AuthRepository
import fit.bandwagon.core.data.repository.Handles
import fit.bandwagon.core.data.repository.ProfileRepository
import fit.bandwagon.core.health.HealthCapabilitiesRepository
import fit.bandwagon.core.model.AliasAvatar
import fit.bandwagon.core.model.HealthCapabilities
import fit.bandwagon.core.model.MyProfile
import fit.bandwagon.core.model.SourceAvailability
import fit.bandwagon.core.sync.SyncEngine
import fit.bandwagon.core.ui.HandleStatus
import fit.bandwagon.core.ui.suggestAlias
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { WELCOME, USERNAME, PROFILE, ALIAS, HEALTH, DONE }

data class OnboardingUiState(
    val loading: Boolean = true,
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val testSignInEnabled: Boolean = false,
    val username: String = "",
    val usernameStatus: HandleStatus = HandleStatus.Idle,
    val displayName: String = "",
    val accountPhotoUrl: String? = null,
    val useAccountPhoto: Boolean = true,
    val alias: String = suggestAlias(),
    val aliasAvatar: AliasAvatar = AliasAvatar(AliasAvatar.SHAPES.random(), AliasAvatar.COLORS.random()),
    val aliasStatus: HandleStatus = HandleStatus.Idle,
    val capabilities: HealthCapabilities = HealthCapabilities.NONE,
    val availability: SourceAvailability? = null,
    val healthAsked: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val finished: Boolean = false,
)

/**
 * Sign-in → username (required) → name and photo → alias (optional) → health access →
 * notifications. Returning users skip straight to the app; accounts from before usernames resume
 * at the username step.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val capabilities: HealthCapabilitiesRepository,
    private val syncEngine: SyncEngine,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState(testSignInEnabled = auth.testSignInEnabled))
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private var firstEmission = true
    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            capabilities.capabilities.collect { caps -> _state.update { it.copy(capabilities = caps) } }
        }
        viewModelScope.launch {
            capabilities.availability.collect { a -> _state.update { it.copy(availability = a) } }
        }
        viewModelScope.launch {
            auth.currentUser.collect { user ->
                if (user == null) {
                    firstEmission = false
                    val expired = auth.sessionExpired.value
                    if (expired) auth.sessionExpiredShown()
                    _state.update {
                        it.copy(
                            loading = false,
                            step = OnboardingStep.WELCOME,
                            username = "",
                            usernameStatus = HandleStatus.Idle,
                            error = if (expired) RemoteError.SESSION_EXPIRED_MESSAGE else it.error,
                        )
                    }
                    return@collect
                }
                // A sign-in saved on this phone may be dead on the server; this signs out if so,
                // and the listener comes back here with no user.
                if (!auth.validateSession()) return@collect
                capabilities.refresh()
                val profile = runCatching { profiles.profile() }.getOrNull()
                val returning = firstEmission && profile?.username != null
                firstEmission = false
                _state.update {
                    it.copy(
                        loading = false,
                        displayName = it.displayName.ifBlank { profile?.displayName ?: user.displayName.orEmpty() },
                        accountPhotoUrl = auth.accountPhotoUrl,
                        step = if (returning) OnboardingStep.DONE else nextAfterSignIn(profile),
                        finished = it.finished || returning,
                    )
                }
            }
        }
    }

    private fun nextAfterSignIn(profile: MyProfile?): OnboardingStep =
        if (profile?.username == null) OnboardingStep.USERNAME else OnboardingStep.PROFILE

    fun signIn(activity: Activity) = signInWith { auth.signInWithGoogle(activity) }

    fun signInForTesting(name: String) {
        if (name.isBlank()) return
        _state.update { it.copy(displayName = name.trim()) }
        signInWith { auth.signInForTesting(name) }
    }

    private fun signInWith(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            block().onFailure { e -> _state.update { it.copy(error = e.message ?: "Sign-in failed") } }
            _state.update { it.copy(working = false) }
        }
    }

    fun onUsernameChange(value: String) {
        _state.update { it.copy(username = value) }
        checkUsername(value)
    }

    private fun checkUsername(value: String) {
        checkJob?.cancel()
        val handle = Handles.normalise(value)
        Handles.problem(handle)?.let { problem ->
            _state.update { it.copy(usernameStatus = if (handle.isEmpty()) HandleStatus.Idle else HandleStatus.Problem(problem)) }
            return
        }
        _state.update { it.copy(usernameStatus = HandleStatus.Checking) }
        checkJob = viewModelScope.launch {
            delay(350)
            val free = runCatching { profiles.isUsernameAvailable(handle) }.getOrDefault(true)
            _state.update { it.copy(usernameStatus = if (free) HandleStatus.Available else HandleStatus.Problem("That username is taken.")) }
        }
    }

    fun claimUsername() = work {
        profiles.claimUsername(_state.value.username)
        _state.update { it.copy(step = OnboardingStep.PROFILE) }
    }

    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value.take(40)) }

    fun onUseAccountPhoto(use: Boolean) = _state.update { it.copy(useAccountPhoto = use) }

    fun saveProfile() = work {
        val s = _state.value
        profiles.updateDisplayName(s.displayName.ifBlank { "Friend" })
        profiles.updatePhoto(if (s.useAccountPhoto) s.accountPhotoUrl else null)
        val hasAlias = profiles.profile()?.alias != null
        _state.update { it.copy(step = if (hasAlias) OnboardingStep.HEALTH else OnboardingStep.ALIAS) }
        if (!hasAlias) checkAlias(s.alias)
    }

    fun onAliasChange(value: String) {
        _state.update { it.copy(alias = value) }
        checkAlias(value)
    }

    fun onAvatarChange(avatar: AliasAvatar) = _state.update { it.copy(aliasAvatar = avatar) }

    private fun checkAlias(value: String) {
        checkJob?.cancel()
        val handle = Handles.normalise(value)
        Handles.problem(handle)?.let { problem ->
            _state.update { it.copy(aliasStatus = HandleStatus.Problem(problem)) }
            return
        }
        _state.update { it.copy(aliasStatus = HandleStatus.Checking) }
        checkJob = viewModelScope.launch {
            delay(350)
            val free = runCatching { profiles.isAliasAvailable(handle) }.getOrDefault(true)
            _state.update { it.copy(aliasStatus = if (free) HandleStatus.Available else HandleStatus.Problem("That alias is taken.")) }
        }
    }

    fun saveAlias() = work {
        profiles.setAlias(_state.value.alias, _state.value.aliasAvatar)
        _state.update { it.copy(step = OnboardingStep.HEALTH) }
    }

    fun skipAlias() = _state.update { it.copy(step = OnboardingStep.HEALTH) }

    fun onHealthRequested() = _state.update { it.copy(healthAsked = true) }

    fun refreshHealth() {
        viewModelScope.launch { capabilities.refresh() }
    }

    fun finish() = work {
        auth.updateTimeZone(java.time.ZoneId.systemDefault().id)
        syncEngine.sync(forceFull = true)
        _state.update { it.copy(step = OnboardingStep.DONE, finished = true) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            try {
                block()
            } catch (e: RemoteError) {
                // An expired session has already signed out; the sign-in screen explains it.
                if (!e.isSessionExpired) _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                if (e.isDeadSession()) auth.validateSession()
                else _state.update { it.copy(error = "Something went wrong. Try again.") }
            }
            _state.update { it.copy(working = false) }
        }
    }
}

package fit.bandwagon.core.data.remote

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One place that ends a dead sign-in, wherever it is noticed (start-up check or a failing call).
 * The app shell watches [expired] to return to the sign-in screen, which then explains why.
 */
@Singleton
class SessionMonitor @Inject constructor() {
    private val _expired = MutableStateFlow(false)
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    fun end() {
        _expired.value = true
        FirebaseAuth.getInstance().signOut()
    }

    fun shown() {
        _expired.value = false
    }
}

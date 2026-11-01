package fit.bandwagon.core.data.repository

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import fit.bandwagon.core.data.remote.RemoteDataSource
import fit.bandwagon.core.data.remote.SessionMonitor
import fit.bandwagon.core.data.remote.isDeadSession
import fit.bandwagon.core.model.AppEnvironment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class AuthUser(
    val uid: String,
    val displayName: String?,
    val photoUrl: String?,
)

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remote: RemoteDataSource,
    private val session: SessionMonitor,
    environment: AppEnvironment,
) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    /** Emulator builds have no Google OAuth client, so they offer a named test sign-in instead. */
    val testSignInEnabled: Boolean = environment.useEmulators

    val currentUser: Flow<AuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { state ->
            trySend(
                state.currentUser?.let { user ->
                    AuthUser(user.uid, user.displayName, user.photoUrl?.toString())
                },
            )
        }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    val uid: String?
        get() = auth.currentUser?.uid

    fun requireUid(): String = auth.currentUser?.uid ?: error("Not signed in")

    /** True after [validateSession] or a call found the sign-in dead and signed out. */
    val sessionExpired: StateFlow<Boolean> = session.expired

    fun sessionExpiredShown() = session.shown()

    /**
     * Asks the server whether the saved sign-in still works. A deleted, disabled or revoked account
     * is signed out so the app returns to sign-in instead of failing every call. Returns false then.
     * Offline counts as valid: the session is kept and checked again next time.
     */
    suspend fun validateSession(): Boolean {
        val user = auth.currentUser ?: return false
        val failure = runCatching { user.reload().await() }.exceptionOrNull() ?: return true
        if (!failure.isDeadSession()) return true
        session.end()
        return false
    }

    /** Google sign-in through Credential Manager, then create/refresh the public profile. */
    suspend fun signInWithGoogle(activity: Activity): Result<Unit> = runCatching {
        val webClientId = webClientId()
        check(webClientId.isNotBlank()) {
            "Missing default_web_client_id. Add google-services.json or set the web client id."
        }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val credential = CredentialManager.create(context).getCredential(activity, request).credential
        check(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "Google did not return an ID token." }

        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        ensureProfile()
    }

    /** Anonymous sign-in against the Auth emulator with a chosen display name. Dev builds only. */
    suspend fun signInForTesting(displayName: String): Result<Unit> = runCatching {
        check(testSignInEnabled) { "Test sign-in is only available in dev builds." }
        val user = auth.signInAnonymously().await().user ?: error("Anonymous sign-in returned no user.")
        user.updateProfile(userProfileChangeRequest { this.displayName = displayName.trim() }).await()
        // The auth listener fired before the name was set; reload so currentUser carries it.
        user.reload().await()
        ensureProfile()
    }

    /**
     * Creates the private profile on first sign-in. Later calls only refresh the time zone, so a
     * name or photo the user chose is never overwritten by the account's.
     */
    suspend fun ensureProfile() {
        val user = auth.currentUser ?: return
        if (remote.getMyProfile(user.uid) == null) {
            remote.upsertProfile(
                uid = user.uid,
                displayName = user.displayName ?: "Friend",
                photoUrl = user.photoUrl?.toString(),
                timeZone = ZoneId.systemDefault().id,
            )
        } else {
            updateTimeZone(ZoneId.systemDefault().id)
        }
    }

    suspend fun updateTimeZone(timeZone: String) {
        val user = auth.currentUser ?: return
        remote.updateProfileFields(user.uid, mapOf("tz" to timeZone))
    }

    fun signOut() {
        auth.signOut()
    }

    /** The Google account's photo, offered as the default profile photo. Never the email. */
    val accountPhotoUrl: String?
        get() = auth.currentUser?.photoUrl?.toString()

    private fun webClientId(): String {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id != 0) context.getString(id) else ""
    }
}

package fit.bandwagon.core.data.repository

import fit.bandwagon.core.data.remote.RemoteDataSource
import fit.bandwagon.core.model.AliasAvatar
import fit.bandwagon.core.model.Goals
import fit.bandwagon.core.model.MyProfile
import fit.bandwagon.core.model.PublicProfile
import fit.bandwagon.core.model.UserSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** Rules shared with functions/src/domain/identity.ts, checked locally for instant feedback. */
object Handles {
    private val PATTERN = Regex("^[a-z][a-z0-9._]{2,19}$")

    fun normalise(value: String): String = value.trim().lowercase().removePrefix("@")

    fun problem(handle: String): String? = when {
        handle.length < 3 -> "Use at least 3 characters."
        handle.length > 20 -> "Use at most 20 characters."
        !PATTERN.matches(handle) -> "Start with a letter and use only letters, numbers, dots and underscores."
        ".." in handle || handle.endsWith(".") -> "Dots can't be doubled or come last."
        else -> null
    }
}

const val ABOUT_MAX = 160

@Singleton
class ProfileRepository @Inject constructor(
    private val remote: RemoteDataSource,
    private val auth: AuthRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeProfile(): Flow<MyProfile?> =
        auth.currentUser.flatMapLatest { user -> user?.let { remote.observeMyProfile(it.uid) } ?: flowOf(null) }

    suspend fun profile(): MyProfile? = auth.uid?.let { remote.getMyProfile(it) }

    suspend fun isUsernameAvailable(username: String): Boolean = !remote.isUsernameTaken(username)

    suspend fun isAliasAvailable(alias: String): Boolean = !remote.isAliasTaken(alias)

    suspend fun claimUsername(username: String) = remote.claimUsername(Handles.normalise(username))

    suspend fun setAlias(alias: String, avatar: AliasAvatar) = remote.setAlias(Handles.normalise(alias), avatar)

    suspend fun updateDisplayName(name: String) =
        remote.updateProfileFields(auth.requireUid(), mapOf("displayName" to name.trim().take(40)))

    suspend fun updatePhoto(photoUrl: String?) =
        remote.updateProfileFields(auth.requireUid(), mapOf("photoUrl" to photoUrl))

    /** Uploads a prepared JPEG and makes it the profile photo. */
    suspend fun uploadPhoto(jpeg: ByteArray) {
        val uid = auth.requireUid()
        updatePhoto(remote.uploadProfilePhoto(uid, jpeg))
    }

    /** Blank clears it. firestore.rules caps it at [ABOUT_MAX] characters. */
    suspend fun updateAbout(about: String) =
        remote.updateProfileFields(auth.requireUid(), mapOf("about" to about.trim().take(ABOUT_MAX).ifBlank { null }))

    suspend fun updateGoals(goals: Goals) = remote.updateGoals(auth.requireUid(), goals)

    suspend fun updateSettings(settings: UserSettings) = remote.updateSettings(auth.requireUid(), settings)

    suspend fun findUser(username: String): PublicProfile? = remote.findUser(Handles.normalise(username))

    suspend fun deleteAccount() = remote.deleteAccount()
}

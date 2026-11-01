package fit.bandwagon.core.notifications

import android.content.Context
import android.provider.Settings
import fit.bandwagon.core.data.remote.RemoteDataSource
import fit.bandwagon.core.data.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FcmTokenRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remote: RemoteDataSource,
    private val auth: AuthRepository,
) {
    suspend fun register(token: String) {
        val uid = auth.uid ?: return
        remote.updateFcmToken(uid, deviceId(), token)
    }

    private fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}

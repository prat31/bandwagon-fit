package fit.bandwagon

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import fit.bandwagon.core.notifications.FcmTokenRepository
import fit.bandwagon.core.notifications.NotificationChannels
import fit.bandwagon.core.data.repository.AuthRepository
import fit.bandwagon.core.sync.SyncScheduler
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

@HiltAndroidApp
class BandwagonApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var tokenRepository: FcmTokenRepository
    @Inject lateinit var authRepository: AuthRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // Before super.onCreate(): Hilt injection creates Firestore-backed singletons.
        FirebaseSetup.configure(this)
        super.onCreate()
        NotificationChannels.create(this)

        syncScheduler.schedulePeriodic()
        syncScheduler.scheduleEveningFinalize()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    syncScheduler.syncNow()
                }
            },
        )

        scope.launch {
            authRepository.currentUser.collect { user ->
                if (user != null) {
                    runCatching { FirebaseMessaging.getInstance().token.await() }
                        .onSuccess { token -> tokenRepository.register(token) }
                }
            }
        }
    }
}

package fit.bandwagon.core.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (engine.sync().status) {
        SyncStatus.SUCCESS -> Result.success()
        SyncStatus.ERROR -> Result.retry()
        else -> Result.success()
    }
}

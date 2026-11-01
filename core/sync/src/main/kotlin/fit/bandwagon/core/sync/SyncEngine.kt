package fit.bandwagon.core.sync

import android.util.Log
import fit.bandwagon.core.data.repository.AuthRepository
import fit.bandwagon.core.data.repository.DailySummaryRepository
import fit.bandwagon.core.health.HealthCapabilitiesRepository
import fit.bandwagon.core.health.HealthDataSource
import fit.bandwagon.core.health.store.HealthRepository
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.model.SourceAvailability
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SyncEngine"

/** Types whose changes can alter an uploaded daily summary. */
private val SUMMARY_TYPES = setOf(
    HealthDataType.STEPS,
    HealthDataType.DISTANCE,
    HealthDataType.ACTIVE_CALORIES,
    HealthDataType.EXERCISE,
    HealthDataType.FLOORS,
    HealthDataType.ACTIVITY_INTENSITY,
    HealthDataType.SLEEP,
    HealthDataType.MINDFULNESS,
)

enum class SyncStatus {
    SUCCESS,
    NOT_SIGNED_IN,
    PERMISSION_MISSING,
    UNAVAILABLE,
    ERROR,
}

data class SyncResult(
    val status: SyncStatus,
    val uploaded: Int = 0,
    val checkedDates: Int = 0,
    val message: String? = null,
)

/**
 * Incremental sync:
 *  - today and yesterday are always refreshed;
 *  - Health Connect changes give the dates to recompute (and tell the Health tab cache to refresh);
 *  - an expired token (30 days) triggers a seven-day backfill.
 *
 * Only daily summaries of challenge metrics are written to Firestore, and only when they changed.
 * Any subset of permissions works: metrics the user didn't share are simply null.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val health: HealthDataSource,
    private val capabilities: HealthCapabilitiesRepository,
    private val healthStore: HealthRepository,
    private val dailyRepository: DailySummaryRepository,
    private val auth: AuthRepository,
    private val preferences: SyncPreferences,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    suspend fun sync(forceFull: Boolean = false): SyncResult = mutex.withLock {
        val uid = auth.uid ?: return@withLock SyncResult(SyncStatus.NOT_SIGNED_IN)

        when (health.availability()) {
            SourceAvailability.UNSUPPORTED ->
                return@withLock SyncResult(SyncStatus.UNAVAILABLE, message = "Health Connect is unavailable")
            SourceAvailability.UPDATE_REQUIRED ->
                return@withLock SyncResult(SyncStatus.UNAVAILABLE, message = "Health Connect needs an update")
            SourceAvailability.AVAILABLE -> Unit
        }

        val caps = capabilities.refresh()
        val readable = caps.grantedTypes.intersect(SUMMARY_TYPES)
        if (readable.isEmpty()) {
            runCatching { dailyRepository.setDataPaused(uid, true) }
            return@withLock SyncResult(SyncStatus.PERMISSION_MISSING, message = "No activity or sleep data shared")
        }

        return@withLock try {
            val zone = ZoneId.systemDefault()
            runCatching { auth.updateTimeZone(zone.id) }
            val today = LocalDate.now(clock)
            val dates = linkedSetOf(today, today.minusDays(1))
            if (forceFull) dates += (0L..6L).map { today.minusDays(it) }

            val tokenScope = readable.map { it.name }.sorted().joinToString(",")
            val previousToken = preferences.changesToken(tokenScope)
            val changes = health.changesSince(previousToken, readable)
            if (changes.expired) {
                dates += (0L..6L).map { today.minusDays(it) }
            } else {
                // First sync after install or a permission change: backfill the last few days.
                if (previousToken == null) dates += (0L..3L).map { today.minusDays(it) }
                dates += changes.changedDates.filter { !it.isAfter(today) && it.isAfter(today.minusDays(31)) }
            }
            if (changes.changedDates.isNotEmpty() || previousToken == null || changes.expired) healthStore.invalidate()

            var uploaded = 0
            for (date in dates.sorted()) {
                val summary = health.dailySummary(uid, date, zone, clock.instant())
                if (dailyRepository.uploadIfChanged(summary)) uploaded++
            }

            // Refresh the heartbeat even when nothing changed: it drives the "synced Xm ago" labels,
            // which tell a quiet day apart from a phone that has not synced.
            dailyRepository.setDataPaused(uid, false)
            dailyRepository.touchLastSync(uid, clock.instant())
            preferences.saveChangesToken(changes.nextToken, tokenScope)

            SyncResult(SyncStatus.SUCCESS, uploaded = uploaded, checkedDates = dates.size)
        } catch (error: Exception) {
            Log.w(TAG, "Sync failed", error)
            SyncResult(SyncStatus.ERROR, message = error.message)
        }
    }
}

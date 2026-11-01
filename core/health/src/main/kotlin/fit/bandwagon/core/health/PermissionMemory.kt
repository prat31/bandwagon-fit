package fit.bandwagon.core.health

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On Android 14+, Health Connect stops showing its permission dialog after the user denies twice.
 * The app then has to send people to Health Connect settings instead. Health Connect doesn't
 * report this, so remember requests that came back with nothing new granted.
 */
@Singleton
class PermissionMemory @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("health_permissions", Context.MODE_PRIVATE)

    fun canRequestAgain(): Boolean = prefs.getInt(KEY_EMPTY_RESULTS, 0) < 2

    /** Call with what was asked for and what is granted after the dialog closed. */
    fun recordResult(requested: Set<String>, grantedBefore: Set<String>, grantedAfter: Set<String>) {
        val newlyGranted = (grantedAfter - grantedBefore).intersect(requested)
        val stillMissing = requested - grantedAfter
        val empty = prefs.getInt(KEY_EMPTY_RESULTS, 0)
        prefs.edit().putInt(
            KEY_EMPTY_RESULTS,
            when {
                newlyGranted.isNotEmpty() || stillMissing.isEmpty() -> 0
                else -> empty + 1
            },
        ).apply()
    }

    private companion object {
        const val KEY_EMPTY_RESULTS = "empty_results"
    }
}

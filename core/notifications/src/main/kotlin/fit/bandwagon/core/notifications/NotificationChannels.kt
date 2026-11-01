package fit.bandwagon.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationChannels {
    const val RESULTS = "results"
    const val REMINDERS = "reminders"
    const val GROUPS = "groups"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(RESULTS, "Results and milestones", NotificationManager.IMPORTANCE_DEFAULT),
        )
        // Nudges were removed; drop the channel earlier versions created.
        manager.deleteNotificationChannel("nudges")
        manager.createNotificationChannel(
            NotificationChannel(REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(GROUPS, "Groups: invites, roles and new challenges", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
}

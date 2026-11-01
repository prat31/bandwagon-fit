package fit.bandwagon.core.model

/** Build-time facts feature modules need without depending on the app module's BuildConfig. */
data class AppEnvironment(
    /** Dev builds run against the local Firebase Emulator Suite. */
    val useEmulators: Boolean,
)

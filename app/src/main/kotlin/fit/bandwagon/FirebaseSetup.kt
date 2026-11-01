package fit.bandwagon

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage

/**
 * Dev builds talk to the Firebase Emulator Suite running on the host machine.
 *
 * Must run before Hilt injects anything that touches Firebase, i.e. before
 * `super.onCreate()` in the Application.
 */
object FirebaseSetup {
    /**
     * The project the local emulators run as. It matches the real dev project, so builds with
     * `src/dev/google-services.json` and fresh clones without it both reach the same emulator data.
     */
    const val LOCAL_PROJECT_ID = "bandwagon-dev"
    const val FUNCTIONS_REGION = "asia-south1"

    fun configure(context: Context) {
        if (!BuildConfig.USE_FIREBASE_EMULATORS) return

        // Without google-services.json nothing initialises the default app, and the
        // first getInstance() call crashes. The emulators accept any API key and app ID.
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId(LOCAL_PROJECT_ID)
                    .setApplicationId("1:000000000000:android:0000000000000000")
                    // Firebase Installations rejects keys that don't look real; this one is never sent to Google.
                    .setApiKey("AIzaSyDemoKeyForLocalEmulatorsOnly00000")
                    .setStorageBucket("$LOCAL_PROJECT_ID.firebasestorage.app")
                    .build(),
            )
        }

        runCatching {
            FirebaseAuth.getInstance().useEmulator(BuildConfig.EMULATOR_HOST, 9099)
            FirebaseFirestore.getInstance().useEmulator(BuildConfig.EMULATOR_HOST, 8080)
            FirebaseFunctions.getInstance(FUNCTIONS_REGION).useEmulator(BuildConfig.EMULATOR_HOST, 5001)
            FirebaseStorage.getInstance().useEmulator(BuildConfig.EMULATOR_HOST, 9199)
        }
    }
}

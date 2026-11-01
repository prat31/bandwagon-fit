package fit.bandwagon

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.core.util.Consumer
import androidx.navigation.compose.rememberNavController
import fit.bandwagon.core.ui.BandwagonTheme
import fit.bandwagon.navigation.BandwagonNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            BandwagonTheme {
                val navController = rememberNavController()
                // NavHost handles the launch intent itself. With singleTask, links opened while the
                // app is running (invites, notifications) arrive here instead.
                DisposableEffect(navController) {
                    val listener = Consumer<Intent> { navController.handleDeepLink(it) }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }
                BandwagonNavHost(navController = navController)
            }
        }
    }
}

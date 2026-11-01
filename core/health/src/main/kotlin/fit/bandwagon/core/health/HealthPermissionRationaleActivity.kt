package fit.bandwagon.core.health

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Required rationale screen for Health Connect. It is opened from the Health Connect UI and from
 * Android's permission usage screen.
 */
class HealthPermissionRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Rationale()
                }
            }
        }
    }

    @Composable
    private fun Rationale() {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Why Bandwagon reads your health data", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Bandwagon reads steps, workouts and sleep from Health Connect so friends can take " +
                    "part in challenges, shared goals and sleep pacts.",
            )
            Text(
                "Only one daily summary leaves your phone: steps, workout minutes, workout count " +
                    "and sleep duration. Raw samples and individual records never leave the device. " +
                    "We never sell health data or use it for advertising.",
            )
            Text(
                "You can change or revoke access at any time in Health Connect. If access is " +
                    "revoked, groups show that your data is paused instead of showing zeros.",
            )
            Button(
                onClick = {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://bandwagon.fit/privacy")),
                    )
                },
            ) {
                Text("Read the privacy policy")
            }
        }
    }
}

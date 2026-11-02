package fit.bandwagon.core.health

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fit.bandwagon.core.model.HealthCategory
import fit.bandwagon.core.model.HealthDataType
import fit.bandwagon.core.ui.BandwagonTheme

/**
 * Required rationale screen for Health Connect. It is opened from the Health Connect UI and from
 * Android's permission usage screen, and Play reviewers check it, so it lists exactly the types the
 * app requests.
 */
class HealthPermissionRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            BandwagonTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Rationale()
                }
            }
        }
    }

    @Composable
    private fun Rationale() {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Why Bandwagon reads your health data", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Bandwagon reads health data from Health Connect so you and your friends can take part " +
                    "in challenges, shared goals and sleep pacts, and so you can see your own trends.",
            )
            HealthCategory.entries.forEach { category ->
                val types = HealthDataType.REQUESTED.filter { it.category == category }
                if (types.isNotEmpty()) {
                    Text(
                        "${category.label}: ${types.joinToString { it.label.lowercase() }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Only daily summaries leave your phone: totals for steps, distance, active calories, active " +
                    "minutes, workouts, floors and mindful minutes, and last night's sleep times. Heart, " +
                    "weight and hydration data stay on your phone. Raw samples and individual records " +
                    "never leave the device.",
            )
            Text(
                "We never sell health data, use it for advertising, or use it for anything other than " +
                    "Bandwagon's features. Bandwagon is not a medical device.",
            )
            Text(
                "Background access keeps group standings and sleep pacts up to date without opening " +
                    "the app. You can change or revoke access at any time in Health Connect. If access " +
                    "is revoked, groups show that your data is paused instead of showing zeros.",
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

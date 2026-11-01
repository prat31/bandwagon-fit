package fit.bandwagon.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** Dev builds only: the Auth emulator has no Google OAuth client, so testers pick a name instead. */
@Composable
fun TestSignInFields(enabled: Boolean, onSignIn: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Test user name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onSignIn(name) },
        enabled = enabled && name.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Sign in as test user") }
    Text(
        "Dev build: signs in to the local Auth emulator.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

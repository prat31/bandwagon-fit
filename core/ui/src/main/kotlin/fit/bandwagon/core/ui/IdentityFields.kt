package fit.bandwagon.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import fit.bandwagon.core.model.AliasAvatar

/** Live result of checking a username or alias. */
sealed interface HandleStatus {
    data object Idle : HandleStatus
    data object Checking : HandleStatus
    data object Available : HandleStatus
    data class Problem(val message: String) : HandleStatus
}

@Composable
fun HandleField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    status: HandleStatus,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.lowercase().filter { c -> c.isLetterOrDigit() || c == '.' || c == '_' }.take(20)) },
        label = { Text(label) },
        // Always visible, unlike a prefix, which Material hides while the field is empty.
        leadingIcon = { Text("@", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        singleLine = true,
        enabled = enabled,
        isError = status is HandleStatus.Problem,
        supportingText = {
            when (status) {
                HandleStatus.Idle -> Text("3–20 letters, numbers, dots or underscores")
                HandleStatus.Checking -> Text("Checking…")
                HandleStatus.Available -> Text("Available", color = BandwagonTheme.extendedColors.onSuccessContainer)
                is HandleStatus.Problem -> Text(status.message)
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Pick an alias and a generated avatar. Used at sign-up, in You, and when joining an anonymous
 * group without an alias.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AliasEditor(
    alias: String,
    onAliasChange: (String) -> Unit,
    avatar: AliasAvatar,
    onAvatarChange: (AliasAvatar) -> Unit,
    status: HandleStatus,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AliasAvatarView(alias.ifBlank { "?" }, avatar, size = 64)
            Text(
                "This is all other members see in anonymous groups. It can't be traced back to you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        HandleField(alias, onAliasChange, "Alias", status)
        Text("Shape", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AliasAvatar.SHAPES.forEach { shape ->
                Selectable(selected = avatar.shape == shape, label = shape, onClick = { onAvatarChange(avatar.copy(shape = shape)) }) {
                    AliasAvatarView(alias.ifBlank { "?" }, avatar.copy(shape = shape), size = 40)
                }
            }
        }
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AliasAvatar.COLORS.forEach { color ->
                Selectable(selected = avatar.color == color, label = color, onClick = { onAvatarChange(avatar.copy(color = color)) }) {
                    AliasAvatarView(alias.ifBlank { "?" }, avatar.copy(color = color), size = 40)
                }
            }
        }
    }
}

@Composable
private fun Selectable(selected: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    androidx.compose.foundation.layout.Box(
        Modifier
            .clip(CircleShape)
            .border(3.dp, ring, CircleShape)
            .clickable(onClick = onClick)
            .padding(4.dp)
            .semantics {
                this.selected = selected
                contentDescription = label
            },
    ) { content() }
}

/** A random, friendly alias suggestion such as "quietotter42". */
fun suggestAlias(seed: Long = System.nanoTime()): String {
    val adjectives = listOf("quiet", "brisk", "sunny", "lucky", "steady", "swift", "calm", "bold", "merry", "nimble")
    val animals = listOf("otter", "heron", "fox", "lynx", "panda", "robin", "koala", "tiger", "orca", "wren")
    val r = java.util.Random(seed)
    return adjectives[r.nextInt(adjectives.size)] + animals[r.nextInt(animals.size)] + (10 + r.nextInt(90))
}

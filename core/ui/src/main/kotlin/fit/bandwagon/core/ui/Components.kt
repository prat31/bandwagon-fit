@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package fit.bandwagon.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.Instant

@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = { actions() },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            content()
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorBanner(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
fun StaleLabel(lastSyncAt: Instant?, modifier: Modifier = Modifier) {
    Text(
        formatRelative(lastSyncAt),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun MemberAvatar(name: String, photoUrl: String? = null, size: Int = 40) {
    val initials = name.trim().split(" ").filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (photoUrl.isNullOrBlank()) {
            Text(
                initials,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        } else {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(vertical = 8.dp),
    )
}

@Composable
fun StatusDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** A generated alias avatar: a coloured shape with the alias initial. Never a photo. */
@Composable
fun AliasAvatarView(alias: String, avatar: fit.bandwagon.core.model.AliasAvatar?, size: Int = 40) {
    val color = BandwagonTheme.extendedColors.avatar[avatar?.color] ?: MaterialTheme.colorScheme.tertiary
    val shape: androidx.compose.ui.graphics.Shape = when (avatar?.shape) {
        "square" -> RoundedCornerShape((size / 5).dp)
        "diamond" -> androidx.compose.foundation.shape.CutCornerShape(50)
        "triangle" -> androidx.compose.foundation.shape.GenericShape { s, _ ->
            moveTo(s.width / 2, 0f); lineTo(s.width, s.height); lineTo(0f, s.height); close()
        }
        "hexagon" -> androidx.compose.foundation.shape.CutCornerShape(25)
        "star" -> androidx.compose.foundation.shape.CutCornerShape(topStartPercent = 50, bottomEndPercent = 50)
        else -> CircleShape
    }
    Box(
        modifier = Modifier.size(size.dp).clip(shape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            alias.take(1).uppercase(),
            style = if (size >= 56) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
            color = Color.Black,
            modifier = if (avatar?.shape == "triangle") Modifier.padding(top = (size / 5).dp) else Modifier,
        )
    }
}

/** A member as shown in a group: their alias avatar in anonymous groups, photo or initials otherwise. */
@Composable
fun MemberBadge(member: fit.bandwagon.core.model.Member?, size: Int = 40) {
    when {
        member == null -> MemberAvatar("?", null, size)
        member.isAnonymous -> AliasAvatarView(member.alias.orEmpty(), member.avatar, size)
        else -> MemberAvatar(member.label, member.photoUrl, size)
    }
}

/** White card with the app's standard padding and corner radius. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    if (onClick != null) {
        androidx.compose.material3.Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors) {
            Column(Modifier.padding(16.dp), content = content)
        }
    } else {
        androidx.compose.material3.Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

/** Shown wherever a metric isn't shared: says why, offers to share. Never shows zero. */
@Composable
fun NotSharedNote(what: String, onShare: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$what isn't shared with Bandwagon.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onShare != null) TextButton(onClick = onShare) { Text("Share") }
    }
}

@Composable
fun BadgeChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, color: Color = MaterialTheme.colorScheme.surfaceVariant) {
    Surface(color = color, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }
}

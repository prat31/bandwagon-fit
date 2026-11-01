package fit.bandwagon.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fit.bandwagon.core.model.FeedEvent
import fit.bandwagon.core.model.FeedEventType
import fit.bandwagon.core.model.Member
import fit.bandwagon.core.model.Reactions
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.MemberBadge
import fit.bandwagon.core.ui.formatAgo

/**
 * The group's activity. Reactions aren't rows of their own: they are folded into counts on the
 * event they react to. Actors are member ids, shown by alias in anonymous groups.
 */
@Composable
fun FeedList(
    events: List<FeedEvent>,
    members: Map<String, Member>,
    onReact: (FeedEvent, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reactions = events.filter { it.type == FeedEventType.REACTION }
        .groupBy { it.data["eventId"] as? String }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        events.filter { it.type != FeedEventType.REACTION && it.type != FeedEventType.UNKNOWN }.forEach { event ->
            val actor = event.actorMemberId?.let { members[it] }
            val counts = reactions[event.id].orEmpty().groupingBy { it.data["reaction"] as? String ?: "" }.eachCount()
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (event.actorMemberId != null) {
                        MemberBadge(actor, size = 32)
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                    }
                    Text(describe(event, actor, members), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(formatAgo(event.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Reactions.EMOJI.forEach { emoji ->
                        val count = counts[emoji] ?: 0
                        TextButton(
                            onClick = { onReact(event, emoji) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.tertiary),
                        ) { Text(if (count > 0) "$emoji $count" else emoji) }
                    }
                }
            }
        }
    }
}

private fun describe(event: FeedEvent, actor: Member?, members: Map<String, Member>): String {
    val name = actor?.label ?: "A member"
    return when (event.type) {
        FeedEventType.GROUP_CREATED -> "$name created the group"
        FeedEventType.MEMBER_JOINED -> "$name joined"
        FeedEventType.MEMBERS_JOINED -> "${(event.data["count"] as? Number)?.toInt() ?: 0} people joined today"
        FeedEventType.OWNER_CHANGED -> "$name is now the owner"
        FeedEventType.MOD_ADDED -> "$name is now a mod"
        FeedEventType.MOD_REMOVED -> "$name is no longer a mod"
        FeedEventType.CHALLENGE_CREATED -> "$name started a challenge: ${event.message.orEmpty()}"
        FeedEventType.CHALLENGE_CANCELLED -> "$name cancelled ${event.message.orEmpty()}"
        FeedEventType.CHALLENGE_ENDED -> {
            val winners = (event.data["winners"] as? List<*>)?.mapNotNull { members[it as? String]?.label }.orEmpty()
            if (winners.isEmpty()) event.message ?: "A challenge ended" else "${event.message}. Winner: ${winners.joinToString()}"
        }
        FeedEventType.MILESTONE -> event.message ?: "A milestone was reached"
        FeedEventType.REACTION, FeedEventType.UNKNOWN -> ""
    }
}

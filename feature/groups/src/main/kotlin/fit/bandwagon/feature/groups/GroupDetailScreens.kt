@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package fit.bandwagon.feature.groups

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fit.bandwagon.core.model.Group
import fit.bandwagon.core.model.GroupIdentity
import fit.bandwagon.core.model.InviteLink
import fit.bandwagon.core.model.Member
import fit.bandwagon.core.model.Membership
import fit.bandwagon.core.model.Role
import fit.bandwagon.core.model.Visibility
import fit.bandwagon.core.model.canInvite
import fit.bandwagon.core.model.canManageChallenges
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.BadgeChip
import fit.bandwagon.core.ui.LoadingState
import fit.bandwagon.core.ui.MemberBadge
import fit.bandwagon.core.ui.ScreenScaffold
import fit.bandwagon.core.ui.SectionHeader
import fit.bandwagon.core.ui.brandFilterChipColors
import fit.bandwagon.core.ui.formatShortDate
import fit.bandwagon.core.ui.peopleCount
import java.time.ZoneId

@Composable
fun GroupDetailScreen(
    onBack: () -> Unit,
    onSettings: (String) -> Unit,
    onInvites: (String) -> Unit,
    onNewChallenge: (String) -> Unit,
    onLeft: () -> Unit,
    challengesTab: @Composable (groupId: String, membership: Membership?, members: Map<String, Member>) -> Unit,
    viewModel: GroupDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<Member?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var reportGroup by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }
    LaunchedEffect(state.left) { if (state.left) onLeft() }

    val group = state.group
    val me = state.membership
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(group?.name.orEmpty(), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (me != null && (me.role.canInvite || group?.visibility == Visibility.PUBLIC)) {
                        IconButton(onClick = { onInvites(viewModel.groupId) }) { Icon(Icons.Filled.PersonAdd, "Invite") }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (me?.role == Role.OWNER) DropdownMenuItem(text = { Text("Group settings") }, onClick = { menu = false; onSettings(viewModel.groupId) })
                        DropdownMenuItem(text = { Text("Report group") }, onClick = { menu = false; reportGroup = true })
                        DropdownMenuItem(text = { Text("Leave group") }, onClick = { menu = false; confirmLeave = true })
                    }
                },
            )
        },
        floatingActionButton = {
            if (tab == 0 && me?.role?.canManageChallenges == true) {
                ExtendedFloatingActionButton(onClick = { onNewChallenge(viewModel.groupId) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New challenge") })
            }
        },
    ) { padding ->
        if (group == null) return@Scaffold LoadingState(Modifier.padding(padding))
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (group.description.isNotBlank()) Text(group.description, style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(peopleCount(group.memberCount), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IdentityBadges(group.identity, group.visibility)
                }
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("Challenges", "Members", "Activity").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> challengesTab(viewModel.groupId, me, state.memberMap)
                1 -> MembersTab(group, state.members, me, onMember = { sheetFor = it })
                else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    if (state.feed.isEmpty()) Text("Nothing here yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FeedList(state.feed, state.memberMap, onReact = viewModel::react)
                }
            }
        }
    }

    sheetFor?.let { member ->
        MemberSheet(member, me, isMe = member.memberId == me?.memberId, onDismiss = { sheetFor = null }, viewModel = viewModel)
    }
    if (confirmLeave) {
        val ownerWithOthers = me?.role == Role.OWNER && (group?.memberCount ?: 0) > 1
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (ownerWithOthers) "Make someone else the owner first" else "Leave ${group?.name}?") },
            text = {
                Text(
                    when {
                        ownerWithOthers -> "Open a member in the Members tab and choose \"Make owner\". Then you can leave."
                        me?.role == Role.OWNER -> "You're the only member, so leaving deletes the group."
                        else -> "You'll leave all its challenges too."
                    },
                )
            },
            confirmButton = {
                if (!ownerWithOthers) TextButton(onClick = { confirmLeave = false; viewModel.leave() }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(if (ownerWithOthers) "OK" else "Cancel") } },
        )
    }
    if (reportGroup) {
        ReportDialog(onDismiss = { reportGroup = false }, onSend = { viewModel.report(null, it); reportGroup = false })
    }
}

@Composable
private fun MembersTab(group: Group, members: List<Member>, me: Membership?, onMember: (Member) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (group.identity == GroupIdentity.ANONYMOUS) {
            Text(
                "Anonymous group: everyone appears by alias, including you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        val sorted = members.sortedWith(compareBy<Member> { it.role.ordinal }.thenBy { it.label.lowercase() })
        sorted.forEach { member ->
            Row(
                Modifier.fillMaxWidth().clickable { onMember(member) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemberBadge(member)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(member.label + if (member.memberId == me?.memberId) " (you)" else "", style = MaterialTheme.typography.bodyLarge)
                    member.username?.let { Text("@$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                when (member.role) {
                    Role.OWNER -> BadgeChip("Owner", color = MaterialTheme.colorScheme.primaryContainer)
                    Role.MOD -> BadgeChip("Mod", color = MaterialTheme.colorScheme.tertiaryContainer)
                    Role.MEMBER -> Unit
                }
            }
        }
        if (group.memberCount > members.size) {
            Text("and ${group.memberCount - members.size} more", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun MemberSheet(member: Member, me: Membership?, isMe: Boolean, onDismiss: () -> Unit, viewModel: GroupDetailViewModel) {
    var report by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberBadge(member, size = 56)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(member.label, style = MaterialTheme.typography.titleLarge)
                    member.username?.let { Text("@$it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    member.joinedAt?.let { Text("Joined ${formatShortDate(it.atZone(ZoneId.systemDefault()).toLocalDate())}", style = MaterialTheme.typography.labelMedium) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!isMe) {
                val myRole = me?.role
                if (myRole == Role.OWNER && member.role != Role.OWNER) {
                    if (member.role == Role.MOD) {
                        TextButton(onClick = { viewModel.setRole(member, Role.MEMBER); onDismiss() }) { Text("Remove as mod") }
                    } else {
                        TextButton(onClick = { viewModel.setRole(member, Role.MOD); onDismiss() }) { Text("Make mod") }
                    }
                    TextButton(onClick = { confirm = "owner" }) { Text("Make owner") }
                }
                val canRemove = (myRole == Role.OWNER && member.role != Role.OWNER) || (myRole == Role.MOD && member.role == Role.MEMBER)
                if (canRemove) {
                    TextButton(onClick = { confirm = "remove" }) { Text("Remove from group", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { confirm = "ban" }) { Text("Remove and ban", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = { report = true }) { Text("Report") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (report) ReportDialog(onDismiss = { report = false }, onSend = { viewModel.report(member, it); report = false; onDismiss() })
    confirm?.let { action ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    when (action) {
                        "owner" -> "Make ${member.label} the owner?"
                        "ban" -> "Remove and ban ${member.label}?"
                        else -> "Remove ${member.label}?"
                    },
                )
            },
            text = {
                Text(
                    when (action) {
                        "owner" -> "You'll become a mod. There's only one owner."
                        "ban" -> "They won't be able to rejoin."
                        else -> "They can rejoin with an invite, or from Discover if the group is public."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        "owner" -> viewModel.transfer(member)
                        "ban" -> viewModel.remove(member, ban = true)
                        else -> viewModel.remove(member, ban = false)
                    }
                    confirm = null
                    onDismiss()
                }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ReportDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tell us what's wrong. Reports go to the Bandwagon team, not the group.")
                OutlinedTextField(reason, { reason = it.take(500) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSend(reason) }, enabled = reason.isNotBlank()) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun GroupSettingsScreen(onBack: () -> Unit, onDeleted: () -> Unit, viewModel: GroupDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val group = state.group
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.left) { if (state.left) onDeleted() }
    ScreenScaffold(title = "Group settings", onBack = onBack) {
        if (group == null) return@ScreenScaffold LoadingState()
        var name by rememberSaveable(group.id) { mutableStateOf(group.name) }
        var description by rememberSaveable(group.id) { mutableStateOf(group.description) }
        var tags by remember(group.id) { mutableStateOf(group.tags.toSet()) }
        var visibility by rememberSaveable(group.id) { mutableStateOf(group.visibility) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it.take(280) }, label = { Text("Description") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Group.TAGS.forEach { tag ->
                    FilterChip(
                        selected = tag in tags,
                        onClick = { tags = if (tag in tags) tags - tag else if (tags.size < 3) tags + tag else tags },
                        label = { Text(tag.replaceFirstChar { it.uppercase() }) },
                        colors = brandFilterChipColors(),
                    )
                }
            }
            Text("Who can join", style = MaterialTheme.typography.titleMedium)
            ChoiceCard(visibility == Visibility.INVITE_ONLY, "Invite only", "People join with a link or a username invite.") { visibility = Visibility.INVITE_ONLY }
            ChoiceCard(visibility == Visibility.PUBLIC, "Public", "Listed in Discover. Anyone can join.") { visibility = Visibility.PUBLIC }
            AppCard {
                Text("Identity: ${if (group.identity == GroupIdentity.ANONYMOUS) "Anonymous" else "Named"}", style = MaterialTheme.typography.titleSmall)
                Text("Chosen when the group was created. It can't be changed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = { viewModel.update(name, description, tags.toList(), visibility) },
                enabled = name.trim().length >= 3 && !state.working,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            HorizontalDivider()
            TextButton(onClick = { confirmDelete = true }) { Text("Delete group for everyone", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${group?.name}?") },
            text = { Text("Everyone loses the group, its challenges and history. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun InvitesScreen(onBack: () -> Unit, viewModel: GroupDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var username by rememberSaveable { mutableStateOf("") }
    var uses by rememberSaveable { mutableStateOf<Int?>(1) }
    var days by rememberSaveable { mutableStateOf<Int?>(7) }
    val canManage = state.membership?.role?.canInvite == true
    LaunchedEffect(canManage) { if (canManage) viewModel.loadInvites() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }
    LaunchedEffect(state.newInvite) {
        state.newInvite?.let { invite ->
            val text = "Join ${state.group?.name} on Bandwagon: ${invite.link}"
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share invite"))
            viewModel.inviteShown()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { outer ->
        ScreenScaffold(title = "Invite people", onBack = onBack) {
            Column(Modifier.padding(outer).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.group?.identity == GroupIdentity.ANONYMOUS) {
                    Text(
                        "This group is anonymous. You won't see whether someone you invited joined, or which alias is theirs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SectionHeader("Invite by username")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        username,
                        { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '.' || c == '_' }.take(20) },
                        prefix = { Text("@") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { viewModel.inviteByUsername(username); username = "" }, enabled = username.length >= 3 && !state.working) { Text("Invite") }
                }

                SectionHeader("Invite link")
                Text("How many people can use it", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<Pair<Int?, String>>(1 to "One person", 10 to "10 people", 50 to "50 people", null to "Unlimited").forEach { (value, label) ->
                        FilterChip(selected = uses == value, onClick = { uses = value }, label = { Text(label) }, colors = brandFilterChipColors())
                    }
                }
                Text("Expires", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf<Pair<Int?, String>>(1 to "1 day", 7 to "7 days", 30 to "30 days", null to "Never").forEach { (value, label) ->
                        FilterChip(selected = days == value, onClick = { days = value }, label = { Text(label) }, colors = brandFilterChipColors())
                    }
                }
                Button(onClick = { viewModel.createInvite(uses, days) }, enabled = !state.working, modifier = Modifier.fillMaxWidth()) { Text("Create and share link") }

                if (canManage && state.invites.isNotEmpty()) {
                    SectionHeader("Active links")
                    state.invites.forEach { invite -> InviteRow(invite, onRevoke = { viewModel.revoke(invite) }) }
                }
            }
        }
    }
}

@Composable
private fun InviteRow(invite: InviteLink, onRevoke: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(invite.code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    listOf(
                        invite.maxUses?.let { "${invite.uses} of $it used" } ?: "${invite.uses} used · unlimited",
                        invite.expiresAt?.let { "expires ${formatShortDate(it.atZone(ZoneId.systemDefault()).toLocalDate())}" } ?: "never expires",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onRevoke) { Text("Turn off") }
        }
    }
}

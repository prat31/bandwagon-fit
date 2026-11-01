@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package fit.bandwagon.feature.groups

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import fit.bandwagon.core.data.remote.DiscoverSort
import fit.bandwagon.core.model.Group
import fit.bandwagon.core.model.GroupIdentity
import fit.bandwagon.core.model.Membership
import fit.bandwagon.core.model.Role
import fit.bandwagon.core.model.Visibility
import fit.bandwagon.core.ui.AliasEditor
import fit.bandwagon.core.ui.AppCard
import fit.bandwagon.core.ui.BadgeChip
import fit.bandwagon.core.ui.ErrorBanner
import fit.bandwagon.core.ui.HandleStatus
import fit.bandwagon.core.ui.LoadingState
import fit.bandwagon.core.ui.ScreenScaffold
import fit.bandwagon.core.ui.SectionHeader
import fit.bandwagon.core.ui.brandFilterChipColors
import fit.bandwagon.core.ui.peopleCount
import fit.bandwagon.core.ui.rememberTactilePagerFlingBehavior

object GroupRoutes {
    const val HOME = "groups"
    const val CREATE = "groups/create"
    const val DETAIL = "groups/{groupId}"
    const val SETTINGS = "groups/{groupId}/settings"
    const val INVITES = "groups/{groupId}/invites"
    const val PREVIEW = "groups/{groupId}/preview"
    const val JOIN = "join/{code}"

    fun detail(groupId: String) = "groups/$groupId"
    fun settings(groupId: String) = "groups/$groupId/settings"
    fun invites(groupId: String) = "groups/$groupId/invites"
    fun preview(groupId: String) = "groups/$groupId/preview"
}

@Composable
fun IdentityBadges(identity: GroupIdentity, visibility: Visibility) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (identity == GroupIdentity.ANONYMOUS) {
            BadgeChip("Anonymous", Icons.Outlined.TheaterComedy, MaterialTheme.colorScheme.tertiaryContainer)
        } else {
            BadgeChip("Named", Icons.Filled.Groups)
        }
        BadgeChip(
            if (visibility == Visibility.PUBLIC) "Public" else "Invite only",
            if (visibility == Visibility.PUBLIC) Icons.Filled.Public else Icons.Filled.Lock,
        )
    }
}

@Composable
fun GroupsHomeScreen(
    onOpenGroup: (String) -> Unit,
    onPreviewGroup: (String) -> Unit,
    onCreate: () -> Unit,
    viewModel: GroupsHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 2 })
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() } }
    LaunchedEffect(state.joinedGroupId) { state.joinedGroupId?.let { onOpenGroup(it); viewModel.navigated() } }

    if (state.aliasNeededFor != null) {
        AliasRequiredDialog(onDismiss = viewModel::aliasHandled, onCreated = viewModel::aliasHandled)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCreate, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New group") })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Groups",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                listOf("My groups", "Discover").forEachIndexed { i, label ->
                    Tab(
                        selected = pagerState.currentPage == i,
                        onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                        text = { Text(label) },
                    )
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), flingBehavior = rememberTactilePagerFlingBehavior(pagerState)) { page ->
                when (page) {
                    0 -> MyGroupsTab(state, onOpenGroup, viewModel)
                    else -> DiscoverTab(state, viewModel, onPreviewGroup)
                }
            }
        }
    }
}

@Composable
private fun MyGroupsTab(
    state: GroupsHomeState,
    onOpenGroup: (String) -> Unit,
    viewModel: GroupsHomeViewModel,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.inbox.isNotEmpty()) {
            item { SectionHeader("Invites") }
            items(state.inbox, key = { "inbox-${it.groupId}" }) { invite ->
                AppCard {
                    Text(invite.groupName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        invite.invitedBy?.let { "$it invited you" } ?: "You're invited to an anonymous group",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    IdentityBadges(invite.identity, invite.visibility)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { viewModel.respond(invite, false) }) { Text("Decline") }
                        Button(onClick = { viewModel.respond(invite, true) }) { Text("Join") }
                    }
                }
            }
        }

        if (state.loaded && state.memberships.isEmpty()) {
            item {
                AppCard {
                    Text("You're not in any groups yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Join a public group in Discover, or create one and invite friends.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(state.memberships, key = { "m-${it.groupId}" }) { membership ->
            MyGroupCard(
                membership,
                state.groups[membership.groupId],
                state.activeChallenges[membership.groupId] ?: 0,
                onClick = { onOpenGroup(membership.groupId) },
            )
        }
    }
}

@Composable
private fun DiscoverTab(
    state: GroupsHomeState,
    viewModel: GroupsHomeViewModel,
    onPreviewGroup: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQuery,
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Search by name") },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Group.TAGS.forEach { tag ->
                    FilterChip(
                        selected = state.tag == tag,
                        onClick = { viewModel.onTag(tag) },
                        label = { Text(tag.replaceFirstChar { it.uppercase() }) },
                        colors = brandFilterChipColors(),
                    )
                }
            }
        }
        if (state.query.isBlank() && state.tag == null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(DiscoverSort.LARGEST to "Largest", DiscoverSort.ACTIVE to "Active", DiscoverSort.NEW to "New").forEach { (sort, label) ->
                        FilterChip(selected = state.sort == sort, onClick = { viewModel.onSort(sort) }, label = { Text(label) }, colors = brandFilterChipColors())
                    }
                }
            }
        }
        val mine = state.memberships.map { it.groupId }.toSet()
        items(state.discover.filter { it.id !in mine }, key = { "d-${it.id}" }) { group ->
            DiscoverCard(group, onClick = { onPreviewGroup(group.id) })
        }
        if (!state.loadingDiscover && state.discover.none { it.id !in mine }) {
            item {
                Text(
                    "No public groups match. Create one!",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun MyGroupCard(membership: Membership, group: Group?, activeChallenges: Int, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group?.name ?: membership.groupName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        group?.let { peopleCount(it.memberCount) },
                        activeChallenges.takeIf { it > 0 }?.let { "$it active ${if (it == 1) "challenge" else "challenges"}" },
                        when (membership.role) {
                            Role.OWNER -> "Owner"
                            Role.MOD -> "Mod"
                            Role.MEMBER -> null
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (group != null) IdentityBadges(group.identity, group.visibility)
        }
    }
}

@Composable
private fun DiscoverCard(group: Group, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Text(group.name, style = MaterialTheme.typography.titleMedium)
        if (group.description.isNotBlank()) {
            Text(group.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(peopleCount(group.memberCount), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IdentityBadges(group.identity, group.visibility)
        }
    }
}

@Composable
fun CreateGroupScreen(onBack: () -> Unit, onCreated: (String) -> Unit, viewModel: CreateGroupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.createdId) { state.createdId?.let(onCreated) }
    if (state.needsAlias) {
        AliasRequiredDialog(
            onDismiss = viewModel::aliasHandled,
            onCreated = {
                viewModel.aliasHandled()
                viewModel.create()
            },
            reason = "Anonymous groups show your alias instead of your name, so create one first.",
        )
    }
    ScreenScaffold(title = "New group", onBack = onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.error?.let { ErrorBanner(it) }
            OutlinedTextField(state.name, viewModel::onName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.description, viewModel::onDescription, label = { Text("Description (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Text("Tags (up to 3)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Group.TAGS.forEach { tag ->
                    FilterChip(selected = tag in state.tags, onClick = { viewModel.toggleTag(tag) }, label = { Text(tag.replaceFirstChar { it.uppercase() }) }, colors = brandFilterChipColors())
                }
            }

            Text("Identity", style = MaterialTheme.typography.titleMedium)
            ChoiceCard(
                selected = state.identity == GroupIdentity.NAMED,
                title = "Named",
                body = "Members see each other's name, username and photo.",
                onClick = { viewModel.onIdentity(GroupIdentity.NAMED) },
            )
            ChoiceCard(
                selected = state.identity == GroupIdentity.ANONYMOUS,
                title = "Anonymous",
                body = "Everyone, including you, appears only by alias. Real identities stay hidden.",
                onClick = { viewModel.onIdentity(GroupIdentity.ANONYMOUS) },
            )
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Text(
                    "This can't be changed later.",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Text("Who can join", style = MaterialTheme.typography.titleMedium)
            ChoiceCard(
                selected = state.visibility == Visibility.INVITE_ONLY,
                title = "Invite only",
                body = "People join with a link or an invite to their username.",
                onClick = { viewModel.onVisibility(Visibility.INVITE_ONLY) },
            )
            ChoiceCard(
                selected = state.visibility == Visibility.PUBLIC,
                title = "Public",
                body = "Listed in Discover. Anyone can join.",
                onClick = { viewModel.onVisibility(Visibility.PUBLIC) },
            )

            Button(
                onClick = viewModel::create,
                enabled = state.name.trim().length >= 3 && !state.working,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.working) "Creating…" else "Create group") }
        }
    }
}

@Composable
fun ChoiceCard(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Create an alias in place, then carry on with whatever needed it. */
@Composable
fun AliasRequiredDialog(
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
    reason: String = "This group is anonymous. Members only ever see your alias, so you need one to join.",
    viewModel: AliasSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) {
        if (state.done) {
            viewModel.reset()
            onCreated()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create your alias") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(reason, style = MaterialTheme.typography.bodyMedium)
                state.error?.let { ErrorBanner(it) }
                AliasEditor(state.alias, viewModel::onAlias, state.avatar, viewModel::onAvatar, state.status)
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::save, enabled = state.status == HandleStatus.Available && !state.working) { Text("Create and continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun JoinGroupScreen(onBack: () -> Unit, onJoined: (String) -> Unit, viewModel: JoinGroupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.joinedGroupId) { state.joinedGroupId?.let(onJoined) }
    if (state.needsAlias) {
        AliasRequiredDialog(onDismiss = viewModel::aliasHandled, onCreated = {
            viewModel.aliasHandled()
            viewModel.join()
        })
    }
    ScreenScaffold(title = "Join group", onBack = onBack) {
        if (state.loading) return@ScreenScaffold LoadingState()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            state.error?.let { ErrorBanner(it) }
            val preview = state.preview ?: return@Column
            AppCard {
                Text(preview.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (preview.description.isNotBlank()) Text(preview.description, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                IdentityBadges(preview.identity, preview.visibility)
                Spacer(Modifier.height(8.dp))
                Text(peopleCount(preview.memberCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (preview.identity == GroupIdentity.ANONYMOUS) {
                    "You'll appear by your alias only. Other members won't see your name, username or photo."
                } else {
                    "Members will see your name, username and photo."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Members take part in every challenge in the group. Challenges use only daily totals, never raw health data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = viewModel::join, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.working) "Joining…" else "Join group")
            }
        }
    }
}

package fit.bandwagon.feature.groups

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fit.bandwagon.core.data.remote.DiscoverSort
import fit.bandwagon.core.data.remote.RemoteError
import fit.bandwagon.core.data.repository.ChallengeRepository
import fit.bandwagon.core.data.repository.GroupRepository
import fit.bandwagon.core.data.repository.Handles
import fit.bandwagon.core.data.repository.ProfileRepository
import fit.bandwagon.core.model.AliasAvatar
import fit.bandwagon.core.model.FeedEvent
import fit.bandwagon.core.model.Group
import fit.bandwagon.core.model.GroupIdentity
import fit.bandwagon.core.model.GroupPreview
import fit.bandwagon.core.model.InboxInvite
import fit.bandwagon.core.model.InviteLink
import fit.bandwagon.core.model.Member
import fit.bandwagon.core.model.Membership
import fit.bandwagon.core.model.MyProfile
import fit.bandwagon.core.model.Role
import fit.bandwagon.core.model.Visibility
import fit.bandwagon.core.model.challenge.ChallengePhase
import fit.bandwagon.core.ui.HandleStatus
import fit.bandwagon.core.ui.suggestAlias
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Runs a callable, turning server errors (already written for users) into a message. */
internal suspend fun <T> attempt(onError: (String) -> Unit, block: suspend () -> T): T? = try {
    block()
} catch (e: RemoteError) {
    onError(e.message)
    null
} catch (e: Exception) {
    onError(e.message ?: "Something went wrong. Try again.")
    null
}

data class GroupsHomeState(
    val memberships: List<Membership> = emptyList(),
    val groups: Map<String, Group> = emptyMap(),
    val activeChallenges: Map<String, Int> = emptyMap(),
    val inbox: List<InboxInvite> = emptyList(),
    val discover: List<Group> = emptyList(),
    val query: String = "",
    val tag: String? = null,
    val sort: DiscoverSort = DiscoverSort.LARGEST,
    val loadingDiscover: Boolean = false,
    val message: String? = null,
    val joinedGroupId: String? = null,
    val aliasNeededFor: String? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class GroupsHomeViewModel @Inject constructor(
    private val groups: GroupRepository,
    private val challenges: ChallengeRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupsHomeState())
    val state: StateFlow<GroupsHomeState> = _state.asStateFlow()
    private var searchJob: Job? = null
    private val watched = mutableSetOf<String>()
    private val watchedChallenges = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            groups.observeMemberships().catch { emit(emptyList()) }.collect { list ->
                _state.update { it.copy(memberships = list, loaded = true) }
                list.forEach {
                    watchGroup(it.groupId)
                    watchChallenges(it.groupId)
                }
            }
        }
        viewModelScope.launch { groups.observeInbox().catch { emit(emptyList()) }.collect { inbox -> _state.update { it.copy(inbox = inbox) } } }
        search()
    }

    private fun watchGroup(groupId: String) {
        if (!watched.add(groupId)) return
        viewModelScope.launch {
            groups.observeGroup(groupId).catch { }.collect { group ->
                if (group != null) _state.update { it.copy(groups = it.groups + (groupId to group)) }
            }
        }
    }

    private fun watchChallenges(groupId: String) {
        if (!watchedChallenges.add(groupId)) return
        viewModelScope.launch {
            challenges.observeChallenges(groupId).catch { }.collect { list ->
                val live = list.count { it.phase(LocalDate.now()) == ChallengePhase.LIVE }
                _state.update { it.copy(activeChallenges = it.activeChallenges + (groupId to live)) }
            }
        }
    }

    fun onQuery(query: String) {
        _state.update { it.copy(query = query) }
        search(debounce = true)
    }

    fun onTag(tag: String?) {
        _state.update { it.copy(tag = if (it.tag == tag) null else tag) }
        search()
    }

    fun onSort(sort: DiscoverSort) {
        _state.update { it.copy(sort = sort) }
        search()
    }

    fun search(debounce: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(300)
            _state.update { it.copy(loadingDiscover = true) }
            val s = _state.value
            val results = attempt({ msg -> _state.update { it.copy(message = msg) } }) { groups.discover(s.query, s.tag, s.sort) }
            _state.update { it.copy(discover = results ?: it.discover, loadingDiscover = false) }
        }
    }

    fun respond(invite: InboxInvite, accept: Boolean) {
        viewModelScope.launch {
            val result = attempt({ msg -> handleJoinError(msg, invite.groupId) }) { groups.respond(invite.groupId, accept) }
            if (result != null) _state.update { it.copy(joinedGroupId = result.groupId) }
        }
    }

    private fun handleJoinError(message: String, groupId: String) {
        if (message == ALIAS_REQUIRED) _state.update { it.copy(aliasNeededFor = groupId) }
        else _state.update { it.copy(message = message) }
    }

    fun aliasHandled() = _state.update { it.copy(aliasNeededFor = null) }
    fun navigated() = _state.update { it.copy(joinedGroupId = null) }
    fun messageShown() = _state.update { it.copy(message = null) }

    companion object {
        /** Matches the HttpsError message the server sends when an alias is required. */
        const val ALIAS_REQUIRED = "ALIAS_REQUIRED"
    }
}

data class CreateGroupState(
    val name: String = "",
    val description: String = "",
    val tags: Set<String> = emptySet(),
    val identity: GroupIdentity = GroupIdentity.NAMED,
    val visibility: Visibility = Visibility.INVITE_ONLY,
    val working: Boolean = false,
    val error: String? = null,
    val createdId: String? = null,
    val needsAlias: Boolean = false,
    val profile: MyProfile? = null,
)

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val groups: GroupRepository,
    profiles: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CreateGroupState())
    val state: StateFlow<CreateGroupState> = _state.asStateFlow()

    init {
        viewModelScope.launch { profiles.observeProfile().collect { p -> _state.update { it.copy(profile = p) } } }
    }

    fun onName(v: String) = _state.update { it.copy(name = v.take(40)) }
    fun onDescription(v: String) = _state.update { it.copy(description = v.take(280)) }
    fun onIdentity(v: GroupIdentity) = _state.update { it.copy(identity = v) }
    fun onVisibility(v: Visibility) = _state.update { it.copy(visibility = v) }
    fun toggleTag(tag: String) = _state.update {
        it.copy(tags = if (tag in it.tags) it.tags - tag else if (it.tags.size < 3) it.tags + tag else it.tags)
    }

    fun create() {
        val s = _state.value
        if (s.identity == GroupIdentity.ANONYMOUS && s.profile?.alias == null) {
            _state.update { it.copy(needsAlias = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            val id = attempt({ msg -> _state.update { it.copy(error = if (msg == GroupsHomeViewModel.ALIAS_REQUIRED) "Create an alias first." else msg) } }) {
                groups.create(s.name.trim(), s.description.trim(), s.tags.toList(), s.identity, s.visibility)
            }
            _state.update { it.copy(working = false, createdId = id) }
        }
    }

    fun aliasHandled() = _state.update { it.copy(needsAlias = false) }
}

/** Shared by every place that may need an alias first (joining or creating anonymous groups). */
data class AliasSetupState(
    val alias: String = suggestAlias(),
    val avatar: AliasAvatar = AliasAvatar(AliasAvatar.SHAPES.random(), AliasAvatar.COLORS.random()),
    val status: HandleStatus = HandleStatus.Idle,
    val working: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class AliasSetupViewModel @Inject constructor(
    private val profiles: ProfileRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AliasSetupState())
    val state: StateFlow<AliasSetupState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        onAlias(_state.value.alias)
    }

    fun onAlias(value: String) {
        _state.update { it.copy(alias = value) }
        job?.cancel()
        val handle = Handles.normalise(value)
        Handles.problem(handle)?.let { p -> _state.update { it.copy(status = HandleStatus.Problem(p)) }; return }
        _state.update { it.copy(status = HandleStatus.Checking) }
        job = viewModelScope.launch {
            delay(300)
            val free = runCatching { profiles.isAliasAvailable(handle) }.getOrDefault(true)
            _state.update { it.copy(status = if (free) HandleStatus.Available else HandleStatus.Problem("That alias is taken.")) }
        }
    }

    fun onAvatar(avatar: AliasAvatar) = _state.update { it.copy(avatar = avatar) }

    fun save() {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            val ok = attempt({ msg -> _state.update { it.copy(error = msg) } }) { profiles.setAlias(_state.value.alias, _state.value.avatar) }
            _state.update { it.copy(working = false, done = ok != null) }
        }
    }

    fun reset() = _state.update { it.copy(done = false) }
}

data class JoinState(
    val preview: GroupPreview? = null,
    val loading: Boolean = true,
    val working: Boolean = false,
    val error: String? = null,
    val joinedGroupId: String? = null,
    val needsAlias: Boolean = false,
)

/** Joining from an invite link (code) or from Discover (public group id). */
@HiltViewModel
class JoinGroupViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val groups: GroupRepository,
) : ViewModel() {
    private val code: String? = savedState.get<String>("code")
    private val groupId: String? = savedState.get<String>("groupId")
    private val _state = MutableStateFlow(JoinState())
    val state: StateFlow<JoinState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val preview = attempt({ msg -> _state.update { it.copy(error = msg) } }) {
                if (code != null) groups.preview(code) else {
                    // Public groups are readable by anyone signed in, so read it directly.
                    val g = groups.observeGroup(groupId!!).first() ?: error("That group is no longer listed.")
                    GroupPreview(g.id, g.name, g.description, g.identity, g.visibility, g.memberCount, g.photoUrl)
                }
            }
            _state.update { it.copy(preview = preview, loading = false) }
        }
    }

    fun join() {
        viewModelScope.launch {
            _state.update { it.copy(working = true, error = null) }
            val result = attempt({ msg ->
                if (msg == GroupsHomeViewModel.ALIAS_REQUIRED) _state.update { it.copy(needsAlias = true) }
                else _state.update { it.copy(error = msg) }
            }) {
                if (code != null) groups.redeem(code) else groups.join(groupId!!)
            }
            _state.update { it.copy(working = false, joinedGroupId = result?.groupId) }
        }
    }

    fun aliasHandled() = _state.update { it.copy(needsAlias = false) }
}

data class GroupDetailState(
    val group: Group? = null,
    val membership: Membership? = null,
    val members: List<Member> = emptyList(),
    val memberMap: Map<String, Member> = emptyMap(),
    val feed: List<FeedEvent> = emptyList(),
    val message: String? = null,
    val left: Boolean = false,
    val invites: List<InviteLink> = emptyList(),
    val newInvite: InviteLink? = null,
    val working: Boolean = false,
)

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val groups: GroupRepository,
) : ViewModel() {
    val groupId: String = checkNotNull(savedState["groupId"])
    private val _state = MutableStateFlow(GroupDetailState())
    val state: StateFlow<GroupDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch { groups.observeGroup(groupId).catch { }.collect { g -> _state.update { it.copy(group = g) } } }
        viewModelScope.launch { groups.observeMembership(groupId).catch { }.collect { m -> _state.update { it.copy(membership = m) } } }
        viewModelScope.launch {
            groups.observeMembers(groupId, MEMBER_PAGE).catch { }.collect { list ->
                _state.update { it.copy(members = list, memberMap = it.memberMap + list.associateBy { m -> m.memberId }) }
            }
        }
        viewModelScope.launch {
            groups.observeFeed(groupId).catch { }.collect { feed ->
                _state.update { it.copy(feed = feed) }
                val unknown = feed.mapNotNull { it.actorMemberId } + feed.mapNotNull { it.data["toMemberId"] as? String }
                val missing = unknown.filter { it !in _state.value.memberMap }
                if (missing.isNotEmpty()) {
                    val found = runCatching { groups.members(groupId, missing) }.getOrDefault(emptyMap())
                    _state.update { it.copy(memberMap = it.memberMap + found) }
                }
            }
        }
    }

    private fun run(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            val ok = attempt({ msg -> _state.update { it.copy(message = msg) } }) { block() }
            _state.update { it.copy(working = false, message = if (ok != null && success != null) success else it.message) }
        }
    }

    fun react(event: FeedEvent, emoji: String) {
        val me = _state.value.membership?.memberId ?: return
        run { groups.react(groupId, me, event.id, emoji) }
    }

    fun setRole(member: Member, role: Role) = run(if (role == Role.MOD) "${member.label} is now a mod" else "${member.label} is no longer a mod") { groups.setRole(groupId, member.memberId, role) }
    fun transfer(member: Member) = run("${member.label} is now the owner") { groups.transferOwnership(groupId, member.memberId) }
    fun remove(member: Member, ban: Boolean) = run("${member.label} was removed") { groups.remove(groupId, member.memberId, ban) }
    fun report(member: Member?, reason: String) = run("Thanks. We'll review it.") { groups.report(groupId, member?.memberId, reason) }

    fun leave() = run {
        groups.leave(groupId)
        _state.update { it.copy(left = true) }
    }

    fun delete() = run {
        groups.delete(groupId)
        _state.update { it.copy(left = true) }
    }

    fun update(name: String, description: String, tags: List<String>, visibility: Visibility) =
        run("Saved") { groups.update(groupId, name, description, tags, visibility) }

    fun loadInvites() = run { _state.update { it.copy(invites = groups.invites(groupId)) } }

    fun createInvite(maxUses: Int?, expiresInDays: Int?) = run {
        val invite = groups.createInvite(groupId, maxUses, expiresInDays)
        _state.update { it.copy(newInvite = invite, invites = listOf(invite) + it.invites) }
    }

    fun revoke(invite: InviteLink) = run("Link turned off") {
        groups.revokeInvite(invite.code)
        _state.update { it.copy(invites = it.invites.filterNot { i -> i.code == invite.code }) }
    }

    fun inviteByUsername(username: String) = run("Invite sent") { groups.inviteByUsername(groupId, username) }

    fun inviteShown() = _state.update { it.copy(newInvite = null) }
    fun messageShown() = _state.update { it.copy(message = null) }

    private companion object {
        const val MEMBER_PAGE = 200L
    }
}

package fit.bandwagon.core.data.repository

import fit.bandwagon.core.data.remote.DiscoverSort
import fit.bandwagon.core.data.remote.RemoteDataSource
import fit.bandwagon.core.model.FeedEvent
import fit.bandwagon.core.model.Group
import fit.bandwagon.core.model.GroupIdentity
import fit.bandwagon.core.model.GroupPreview
import fit.bandwagon.core.model.InboxInvite
import fit.bandwagon.core.model.InviteLink
import fit.bandwagon.core.model.JoinResult
import fit.bandwagon.core.model.Member
import fit.bandwagon.core.model.Membership
import fit.bandwagon.core.model.Role
import fit.bandwagon.core.model.Visibility
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Groups, membership, invites and the feed. Every write goes through Cloud Functions, which keep
 * membership, roles and anonymity consistent (AGENTS.md).
 */
@Singleton
class GroupRepository @Inject constructor(
    private val remote: RemoteDataSource,
    private val auth: AuthRepository,
) {
    private val memberCache = ConcurrentHashMap<String, Member>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMemberships(): Flow<List<Membership>> =
        auth.currentUser.flatMapLatest { user -> user?.let { remote.observeMemberships(it.uid) } ?: flowOf(emptyList()) }

    fun observeMembership(groupId: String): Flow<Membership?> =
        observeMemberships().map { list -> list.firstOrNull { it.groupId == groupId } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeInbox(): Flow<List<InboxInvite>> =
        auth.currentUser.flatMapLatest { user -> user?.let { remote.observeInbox(it.uid) } ?: flowOf(emptyList()) }

    fun observeGroup(groupId: String): Flow<Group?> = remote.observeGroup(groupId)

    suspend fun discover(query: String, tag: String?, sort: DiscoverSort): List<Group> = remote.discoverGroups(query, tag, sort)

    fun observeMembers(groupId: String, limit: Long = 100): Flow<List<Member>> =
        remote.observeMembers(groupId, limit).map { members ->
            members.forEach { memberCache["$groupId/${it.memberId}"] = it }
            members
        }

    /** Names for leaderboard rows, fetched by id and cached, so big groups never load everyone. */
    suspend fun members(groupId: String, memberIds: Collection<String>): Map<String, Member> {
        val missing = memberIds.filter { !memberCache.containsKey("$groupId/$it") }
        if (missing.isNotEmpty()) {
            remote.membersByIds(groupId, missing).forEach { memberCache["$groupId/${it.memberId}"] = it }
        }
        return memberIds.mapNotNull { id -> memberCache["$groupId/$id"]?.let { id to it } }.toMap()
    }

    fun observeFeed(groupId: String): Flow<List<FeedEvent>> = remote.observeFeed(groupId)

    suspend fun react(groupId: String, myMemberId: String, eventId: String, emoji: String) =
        remote.react(groupId, myMemberId, eventId, emoji)

    suspend fun create(
        name: String,
        description: String,
        tags: List<String>,
        identity: GroupIdentity,
        visibility: Visibility,
    ): String = remote.createGroup(name, description, tags, identity, visibility, photoUrl = null)

    suspend fun update(groupId: String, name: String, description: String, tags: List<String>, visibility: Visibility) =
        remote.updateGroup(groupId, name, description, tags, visibility)

    suspend fun join(groupId: String): JoinResult = remote.joinGroup(groupId)
    suspend fun leave(groupId: String) = remote.leaveGroup(groupId)
    suspend fun delete(groupId: String) = remote.deleteGroup(groupId)
    suspend fun setRole(groupId: String, memberId: String, role: Role) = remote.setRole(groupId, memberId, role)
    suspend fun transferOwnership(groupId: String, memberId: String) = remote.transferOwnership(groupId, memberId)
    suspend fun remove(groupId: String, memberId: String, ban: Boolean) = remote.removeMember(groupId, memberId, ban)
    suspend fun report(groupId: String, memberId: String?, reason: String) = remote.report(groupId, memberId, reason)

    suspend fun createInvite(groupId: String, maxUses: Int?, expiresInDays: Int?): InviteLink =
        remote.createInvite(groupId, maxUses, expiresInDays)

    suspend fun invites(groupId: String): List<InviteLink> = remote.listInvites(groupId)
    suspend fun revokeInvite(code: String) = remote.revokeInvite(code)
    suspend fun preview(code: String): GroupPreview = remote.previewInvite(code.trim().uppercase())
    suspend fun redeem(code: String): JoinResult = remote.redeemInvite(code.trim().uppercase())
    suspend fun inviteByUsername(groupId: String, username: String) = remote.inviteByUsername(groupId, Handles.normalise(username))
    suspend fun respond(groupId: String, accept: Boolean): JoinResult? = remote.respondToInvitation(groupId, accept)

}

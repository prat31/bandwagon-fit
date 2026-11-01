package fit.bandwagon.core.data.remote

import fit.bandwagon.core.model.AliasAvatar
import fit.bandwagon.core.model.DailySummary
import fit.bandwagon.core.model.FeedEvent
import fit.bandwagon.core.model.Goals
import fit.bandwagon.core.model.Group
import fit.bandwagon.core.model.GroupIdentity
import fit.bandwagon.core.model.GroupPreview
import fit.bandwagon.core.model.InboxInvite
import fit.bandwagon.core.model.InviteLink
import fit.bandwagon.core.model.JoinResult
import fit.bandwagon.core.model.Member
import fit.bandwagon.core.model.Membership
import fit.bandwagon.core.model.MyProfile
import fit.bandwagon.core.model.PublicProfile
import fit.bandwagon.core.model.Role
import fit.bandwagon.core.model.UserSettings
import fit.bandwagon.core.model.Visibility
import fit.bandwagon.core.model.challenge.Challenge
import fit.bandwagon.core.model.challenge.ChallengeSpec
import fit.bandwagon.core.model.challenge.ChallengeSummary
import fit.bandwagon.core.model.challenge.DayCounts
import fit.bandwagon.core.model.challenge.DayRow
import fit.bandwagon.core.model.challenge.MemberScore
import kotlinx.coroutines.flow.Flow
import java.time.Instant

enum class DiscoverSort { ACTIVE, LARGEST, NEW }

/** A callable failed with a reason meant for the user (Cloud Functions HttpsError message). */
class RemoteError(val code: String, override val message: String) : Exception(message) {
    val isSessionExpired: Boolean get() = code == SESSION_EXPIRED

    companion object {
        const val SESSION_EXPIRED = "SESSION_EXPIRED"
        const val SESSION_EXPIRED_MESSAGE = "Your sign-in has expired. Please sign in again."
    }
}

/**
 * True when Firebase says the saved sign-in can no longer be used: the account was deleted or
 * disabled, or its refresh token was revoked (also what a restarted Auth emulator answers).
 * Network failures are not this; the session is kept and the call can be retried.
 */
fun Throwable.isDeadSession(): Boolean {
    var e: Throwable? = this
    while (e != null) {
        if (e is com.google.firebase.FirebaseNetworkException) return false
        if (e is com.google.firebase.auth.FirebaseAuthInvalidUserException) return true
        val text = e.message.orEmpty()
        if (DEAD_SESSION_CODES.any { it in text }) return true
        e = e.cause
    }
    return false
}

private val DEAD_SESSION_CODES = listOf("INVALID_REFRESH_TOKEN", "USER_NOT_FOUND", "USER_DISABLED", "TOKEN_EXPIRED", "INVALID_ID_TOKEN")

/**
 * All Firestore and Cloud Functions access goes through this interface, which keeps the
 * repositories and view models testable without Firebase.
 */
interface RemoteDataSource {
    // Profile and identity
    suspend fun upsertDailySummary(summary: DailySummary)
    suspend fun updateLastSync(uid: String, at: Instant)
    suspend fun setDataPaused(uid: String, paused: Boolean)
    suspend fun upsertProfile(uid: String, displayName: String, photoUrl: String?, timeZone: String)
    suspend fun updateProfileFields(uid: String, fields: Map<String, Any?>)

    /** Stores a JPEG as the user's profile photo and returns its download URL. */
    suspend fun uploadProfilePhoto(uid: String, jpeg: ByteArray): String
    suspend fun updateGoals(uid: String, goals: Goals)
    suspend fun updateSettings(uid: String, settings: UserSettings)
    fun observeMyProfile(uid: String): Flow<MyProfile?>
    suspend fun getMyProfile(uid: String): MyProfile?
    suspend fun isUsernameTaken(username: String): Boolean
    suspend fun isAliasTaken(alias: String): Boolean
    suspend fun claimUsername(username: String)
    suspend fun setAlias(alias: String, avatar: AliasAvatar)
    suspend fun findUser(username: String): PublicProfile?
    suspend fun updateFcmToken(uid: String, deviceId: String, token: String)
    suspend fun deleteAccount()

    // Groups
    fun observeMemberships(uid: String): Flow<List<Membership>>
    fun observeInbox(uid: String): Flow<List<InboxInvite>>
    fun observeGroup(groupId: String): Flow<Group?>
    suspend fun discoverGroups(query: String, tag: String?, sort: DiscoverSort, limit: Long = 30): List<Group>
    fun observeMembers(groupId: String, limit: Long): Flow<List<Member>>
    suspend fun membersByIds(groupId: String, memberIds: Collection<String>): List<Member>
    fun observeFeed(groupId: String, limit: Long = 50): Flow<List<FeedEvent>>
    suspend fun react(groupId: String, actorMemberId: String, eventId: String, emoji: String)

    suspend fun createGroup(name: String, description: String, tags: List<String>, identity: GroupIdentity, visibility: Visibility, photoUrl: String?): String
    suspend fun updateGroup(groupId: String, name: String, description: String, tags: List<String>, visibility: Visibility)
    suspend fun joinGroup(groupId: String): JoinResult
    suspend fun leaveGroup(groupId: String)
    suspend fun deleteGroup(groupId: String)
    suspend fun setRole(groupId: String, memberId: String, role: Role)
    suspend fun transferOwnership(groupId: String, memberId: String)
    suspend fun removeMember(groupId: String, memberId: String, ban: Boolean)
    suspend fun report(groupId: String, memberId: String?, reason: String)

    // Invites
    suspend fun createInvite(groupId: String, maxUses: Int?, expiresInDays: Int?): InviteLink
    suspend fun listInvites(groupId: String): List<InviteLink>
    suspend fun revokeInvite(code: String)
    suspend fun previewInvite(code: String): GroupPreview
    suspend fun redeemInvite(code: String): JoinResult
    suspend fun inviteByUsername(groupId: String, username: String)
    suspend fun respondToInvitation(groupId: String, accept: Boolean): JoinResult?

    // Challenges
    fun observeChallenges(groupId: String): Flow<List<Challenge>>
    fun observeChallenge(groupId: String, challengeId: String): Flow<Challenge?>
    fun observeSummary(groupId: String, challengeId: String): Flow<ChallengeSummary?>
    fun observeScore(groupId: String, challengeId: String, memberId: String): Flow<MemberScore?>
    fun observeTopScores(groupId: String, challengeId: String, limit: Long): Flow<List<MemberScore>>
    /** Members with a strictly higher score: your rank is this plus one. */
    suspend fun countAbove(groupId: String, challengeId: String, score: Double): Long
    fun observeDayRows(groupId: String, challengeId: String, date: String, limit: Long): Flow<List<DayRow>>
    suspend fun dayHistory(groupId: String, challengeId: String, limit: Long): List<DayCounts>
    suspend fun createChallenge(groupId: String, spec: ChallengeSpec): String
    suspend fun updateChallenge(groupId: String, challengeId: String, spec: ChallengeSpec)
    suspend fun cancelChallenge(groupId: String, challengeId: String)
}

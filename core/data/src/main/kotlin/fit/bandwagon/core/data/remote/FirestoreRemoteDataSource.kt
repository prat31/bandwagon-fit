package fit.bandwagon.core.data.remote

import com.google.firebase.Timestamp
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import fit.bandwagon.core.data.mapper.DailySummaryMapper
import fit.bandwagon.core.data.mapper.FirestoreMappers
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirestoreRemoteDataSource @Inject constructor(
    private val session: SessionMonitor,
) : RemoteDataSource {
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("asia-south1")

    private fun user(uid: String) = db.collection("users").document(uid)
    private fun group(groupId: String) = db.collection("groups").document(groupId)
    private fun challenge(groupId: String, challengeId: String) =
        group(groupId).collection("challenges").document(challengeId)

    // --- Profile and identity -----------------------------------------------------------------

    override suspend fun upsertDailySummary(summary: DailySummary) {
        user(summary.userId).collection("daily").document(summary.date.toString())
            .set(DailySummaryMapper.toFirestore(summary) { Timestamp(Date.from(it)) })
            .await()
    }

    override suspend fun updateLastSync(uid: String, at: Instant) {
        val ts = Timestamp(Date.from(at))
        user(uid).set(mapOf("lastSyncAt" to ts, "updatedAt" to ts), SetOptions.merge()).await()
    }

    override suspend fun setDataPaused(uid: String, paused: Boolean) {
        user(uid).set(mapOf("dataPaused" to paused, "updatedAt" to Timestamp.now()), SetOptions.merge()).await()
    }

    /** One file per user, overwritten on change; storage.rules limits it to the owner and 2 MB. */
    override suspend fun uploadProfilePhoto(uid: String, jpeg: ByteArray): String {
        val ref = FirebaseStorage.getInstance().reference.child("users/$uid/profile/photo.jpg")
        ref.putBytes(jpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
        return ref.downloadUrl.await().toString()
    }

    override suspend fun upsertProfile(uid: String, displayName: String, photoUrl: String?, timeZone: String) {
        user(uid).set(
            mapOf("displayName" to displayName, "photoUrl" to photoUrl, "tz" to timeZone, "updatedAt" to Timestamp.now()),
            SetOptions.merge(),
        ).await()
    }

    override suspend fun updateProfileFields(uid: String, fields: Map<String, Any?>) {
        user(uid).set(fields + ("updatedAt" to Timestamp.now()), SetOptions.merge()).await()
    }

    override suspend fun updateGoals(uid: String, goals: Goals) =
        updateProfileFields(uid, mapOf("goals" to FirestoreMappers.goalsMap(goals)))

    override suspend fun updateSettings(uid: String, settings: UserSettings) =
        updateProfileFields(uid, mapOf("settings" to FirestoreMappers.settingsMap(settings)))

    override fun observeMyProfile(uid: String): Flow<MyProfile?> =
        user(uid).observe().map { data -> data?.let { FirestoreMappers.myProfile(uid, it) } }

    override suspend fun getMyProfile(uid: String): MyProfile? =
        user(uid).get().await().data?.let { FirestoreMappers.myProfile(uid, it) }

    override suspend fun isUsernameTaken(username: String): Boolean {
        val snap = db.collection("usernames").document(username).get().await()
        return snap.exists() || db.collection("aliases").document(username).get().await().exists()
    }

    override suspend fun isAliasTaken(alias: String): Boolean {
        val snap = db.collection("aliases").document(alias).get().await()
        return snap.exists() || db.collection("usernames").document(alias).get().await().exists()
    }

    override suspend fun claimUsername(username: String) {
        call("claimUsername", mapOf("username" to username))
    }

    override suspend fun setAlias(alias: String, avatar: AliasAvatar) {
        call("setAlias", mapOf("alias" to alias, "avatar" to mapOf("shape" to avatar.shape, "color" to avatar.color)))
    }

    override suspend fun findUser(username: String): PublicProfile? {
        val data = call("findUser", mapOf("username" to username))
        return if (data["found"] == true) FirestoreMappers.publicProfile(data) else null
    }

    override suspend fun updateFcmToken(uid: String, deviceId: String, token: String) {
        user(uid).collection("devices").document(deviceId).set(
            mapOf("fcmToken" to token, "platform" to "android", "appVersion" to "", "updatedAt" to Timestamp.now()),
            SetOptions.merge(),
        ).await()
    }

    override suspend fun deleteAccount() {
        call("deleteAccount", emptyMap())
    }

    // --- Groups -------------------------------------------------------------------------------

    override fun observeMemberships(uid: String): Flow<List<Membership>> =
        user(uid).collection("memberships").observeQuery().map { snap ->
            snap.documents.map { FirestoreMappers.membership(it.id, it.data.orEmpty()) }
                .sortedByDescending { it.joinedAt }
        }

    override fun observeInbox(uid: String): Flow<List<InboxInvite>> =
        user(uid).collection("inbox").observeQuery().map { snap ->
            snap.documents.map { FirestoreMappers.inbox(it.id, it.data.orEmpty()) }
        }

    override fun observeGroup(groupId: String): Flow<Group?> =
        group(groupId).observe().map { data -> data?.let { FirestoreMappers.group(groupId, it) } }

    override suspend fun discoverGroups(query: String, tag: String?, sort: DiscoverSort, limit: Long): List<Group> {
        var q: Query = db.collection("groups")
            .whereEqualTo("visibility", "PUBLIC")
            .whereEqualTo("status", "ACTIVE")
        val text = query.trim().lowercase()
        q = when {
            text.isNotEmpty() -> q.orderBy("nameLower").startAt(text).endAt(text + "")
            tag != null -> q.whereArrayContains("tags", tag).orderBy("memberCount", Query.Direction.DESCENDING)
            sort == DiscoverSort.NEW -> q.orderBy("createdAt", Query.Direction.DESCENDING)
            sort == DiscoverSort.ACTIVE -> q.orderBy("updatedAt", Query.Direction.DESCENDING)
            else -> q.orderBy("memberCount", Query.Direction.DESCENDING)
        }
        val groups = q.limit(limit).get().await().documents.map { FirestoreMappers.group(it.id, it.data.orEmpty()) }
        return if (text.isNotEmpty() && tag != null) groups.filter { tag in it.tags } else groups
    }

    override fun observeMembers(groupId: String, limit: Long): Flow<List<Member>> =
        group(groupId).collection("members").orderBy("joinedAt").limit(limit).observeQuery().map { snap ->
            snap.documents.map { FirestoreMappers.member(it.id, it.data.orEmpty()) }
        }

    override suspend fun membersByIds(groupId: String, memberIds: Collection<String>): List<Member> =
        memberIds.distinct().chunked(30).flatMap { chunk ->
            group(groupId).collection("members").whereIn(FieldPath.documentId(), chunk).get().await()
                .documents.map { FirestoreMappers.member(it.id, it.data.orEmpty()) }
        }

    override fun observeFeed(groupId: String, limit: Long): Flow<List<FeedEvent>> =
        group(groupId).collection("feed").orderBy("createdAt", Query.Direction.DESCENDING).limit(limit)
            .observeQuery().map { snap -> snap.documents.map { FirestoreMappers.feedEvent(it.id, it.data.orEmpty()) } }

    override suspend fun react(groupId: String, actorMemberId: String, eventId: String, emoji: String) {
        group(groupId).collection("feed").add(
            mapOf(
                "type" to "REACTION",
                "actorMemberId" to actorMemberId,
                "message" to null,
                "data" to mapOf("eventId" to eventId, "reaction" to emoji),
                "createdAt" to Timestamp.now(),
            ),
        ).await()
    }

    override suspend fun createGroup(
        name: String,
        description: String,
        tags: List<String>,
        identity: GroupIdentity,
        visibility: Visibility,
        photoUrl: String?,
    ): String = call(
        "createGroup",
        mapOf(
            "name" to name,
            "description" to description,
            "tags" to tags,
            "identity" to identity.name,
            "visibility" to visibility.name,
            "photoUrl" to photoUrl,
        ),
    )["groupId"] as String

    override suspend fun updateGroup(groupId: String, name: String, description: String, tags: List<String>, visibility: Visibility) {
        call(
            "updateGroup",
            mapOf("groupId" to groupId, "name" to name, "description" to description, "tags" to tags, "visibility" to visibility.name),
        )
    }

    override suspend fun joinGroup(groupId: String): JoinResult = joinResult(call("joinGroup", mapOf("groupId" to groupId)))

    override suspend fun leaveGroup(groupId: String) {
        call("leaveGroup", mapOf("groupId" to groupId))
    }

    override suspend fun deleteGroup(groupId: String) {
        call("deleteGroup", mapOf("groupId" to groupId))
    }

    override suspend fun setRole(groupId: String, memberId: String, role: Role) {
        call("setRole", mapOf("groupId" to groupId, "memberId" to memberId, "role" to role.name))
    }

    override suspend fun transferOwnership(groupId: String, memberId: String) {
        call("transferOwnership", mapOf("groupId" to groupId, "memberId" to memberId))
    }

    override suspend fun removeMember(groupId: String, memberId: String, ban: Boolean) {
        call("removeMember", mapOf("groupId" to groupId, "memberId" to memberId, "ban" to ban))
    }

    override suspend fun report(groupId: String, memberId: String?, reason: String) {
        call("reportContent", mapOf("groupId" to groupId, "memberId" to memberId, "reason" to reason))
    }

    // --- Invites ------------------------------------------------------------------------------

    override suspend fun createInvite(groupId: String, maxUses: Int?, expiresInDays: Int?): InviteLink {
        val data = call("createInvite", mapOf("groupId" to groupId, "maxUses" to maxUses, "expiresInDays" to expiresInDays))
        return InviteLink(
            code = data["code"] as String,
            link = data["link"] as? String ?: "",
            maxUses = maxUses,
            uses = 0,
            expiresAt = expiresInDays?.let { Instant.now().plusSeconds(it * 86_400L) },
        )
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun listInvites(groupId: String): List<InviteLink> =
        (call("listInvites", mapOf("groupId" to groupId))["invites"] as? List<Map<String, Any?>>)
            ?.map(FirestoreMappers::invite) ?: emptyList()

    override suspend fun revokeInvite(code: String) {
        call("revokeInvite", mapOf("code" to code))
    }

    override suspend fun previewInvite(code: String): GroupPreview =
        FirestoreMappers.preview(call("previewInvite", mapOf("code" to code)))

    override suspend fun redeemInvite(code: String): JoinResult = joinResult(call("redeemInvite", mapOf("code" to code)))

    override suspend fun inviteByUsername(groupId: String, username: String) {
        call("inviteByUsername", mapOf("groupId" to groupId, "username" to username))
    }

    override suspend fun respondToInvitation(groupId: String, accept: Boolean): JoinResult? {
        val data = call("respondToInvitation", mapOf("groupId" to groupId, "accept" to accept))
        return if (data["joined"] == true) joinResult(data) else null
    }

    private fun joinResult(data: Map<String, Any?>) = JoinResult(
        groupId = data["groupId"] as? String ?: error("Missing group"),
        memberId = data["memberId"] as? String ?: "",
        alreadyMember = data["alreadyMember"] == true,
    )

    // --- Challenges ---------------------------------------------------------------------------

    override fun observeChallenges(groupId: String): Flow<List<Challenge>> =
        group(groupId).collection("challenges").orderBy("createdAt", Query.Direction.DESCENDING).limit(50)
            .observeQuery().map { snap ->
                snap.documents.map { FirestoreMappers.challenge(groupId, it.id, it.data.orEmpty()) }
            }

    override fun observeChallenge(groupId: String, challengeId: String): Flow<Challenge?> =
        challenge(groupId, challengeId).observe().map { data -> data?.let { FirestoreMappers.challenge(groupId, challengeId, it) } }

    override fun observeSummary(groupId: String, challengeId: String): Flow<ChallengeSummary?> =
        challenge(groupId, challengeId).collection("summary").document("current").observe()
            .map { data -> data?.let(FirestoreMappers::summary) }

    override fun observeScore(groupId: String, challengeId: String, memberId: String): Flow<MemberScore?> =
        challenge(groupId, challengeId).collection("scores").document(memberId).observe()
            .map { data -> data?.let { FirestoreMappers.score(memberId, it) } }

    override fun observeTopScores(groupId: String, challengeId: String, limit: Long): Flow<List<MemberScore>> =
        challenge(groupId, challengeId).collection("scores")
            .orderBy("score", Query.Direction.DESCENDING)
            .orderBy("tieBreak", Query.Direction.DESCENDING)
            .limit(limit)
            .observeQuery().map { snap -> snap.documents.map { FirestoreMappers.score(it.id, it.data.orEmpty()) } }

    override suspend fun countAbove(groupId: String, challengeId: String, score: Double): Long =
        challenge(groupId, challengeId).collection("scores").whereGreaterThan("score", score)
            .count().get(AggregateSource.SERVER).await().count

    override fun observeDayRows(groupId: String, challengeId: String, date: String, limit: Long): Flow<List<DayRow>> =
        challenge(groupId, challengeId).collection("days").document(date).collection("rows").limit(limit)
            .observeQuery().map { snap -> snap.documents.map { FirestoreMappers.dayRow(it.id, it.data.orEmpty()) } }

    override suspend fun dayHistory(groupId: String, challengeId: String, limit: Long): List<DayCounts> =
        challenge(groupId, challengeId).collection("days").orderBy("date", Query.Direction.DESCENDING).limit(limit)
            .get().await().documents.map { FirestoreMappers.dayCounts(it.data.orEmpty()) }.reversed()

    override suspend fun createChallenge(groupId: String, spec: ChallengeSpec): String =
        call("createChallenge", mapOf("groupId" to groupId, "spec" to FirestoreMappers.specMap(spec)))["challengeId"] as String

    override suspend fun updateChallenge(groupId: String, challengeId: String, spec: ChallengeSpec) {
        call("updateChallenge", mapOf("groupId" to groupId, "challengeId" to challengeId, "spec" to FirestoreMappers.specMap(spec)))
    }

    override suspend fun cancelChallenge(groupId: String, challengeId: String) {
        call("cancelChallenge", mapOf("groupId" to groupId, "challengeId" to challengeId))
    }

    // --- Plumbing -----------------------------------------------------------------------------

    /** Calls a Cloud Function; server error messages are written for users, so pass them through. */
    private suspend fun call(name: String, payload: Map<String, Any?>): Map<String, Any?> {
        try {
            val result = functions.getHttpsCallable(name).call(payload).await()
            @Suppress("UNCHECKED_CAST")
            return result.getData() as? Map<String, Any?> ?: emptyMap()
        } catch (error: FirebaseFunctionsException) {
            if (error.code == FirebaseFunctionsException.Code.UNAUTHENTICATED || error.isDeadSession()) {
                session.end()
                throw RemoteError(RemoteError.SESSION_EXPIRED, RemoteError.SESSION_EXPIRED_MESSAGE)
            }
            throw RemoteError(error.code.name, error.message ?: "Something went wrong. Try again.")
        } catch (error: Exception) {
            // Failures before the call is sent (getting the auth token, no network) arrive as plain
            // task errors like "1 out of 2 underlying tasks failed"; never show those as they are.
            throw when {
                error.isDeadSession() -> {
                    session.end()
                    RemoteError(RemoteError.SESSION_EXPIRED, RemoteError.SESSION_EXPIRED_MESSAGE)
                }
                generateSequence<Throwable>(error) { it.cause }.any { it is com.google.firebase.FirebaseNetworkException } ->
                    RemoteError("UNAVAILABLE", "Can't reach Bandwagon. Check your connection and try again.")
                else -> RemoteError("UNKNOWN", "Something went wrong. Try again.")
            }
        }
    }

    private fun DocumentReference.observe(): Flow<Map<String, Any?>?> = callbackFlow {
        val listener = addSnapshotListener { snapshot, error ->
            if (error != null) close(error) else trySend(snapshot?.data)
        }
        awaitClose { listener.remove() }
    }

    private fun Query.observeQuery(): Flow<QuerySnapshot> = callbackFlow {
        val listener = addSnapshotListener { snapshot, error ->
            if (error != null) close(error) else if (snapshot != null) trySend(snapshot)
        }
        awaitClose { listener.remove() }
    }
}

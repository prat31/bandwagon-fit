package fit.bandwagon.core.model

import java.time.Instant

/** Generated avatar for an alias: a shape and a colour token, never a photo. */
data class AliasAvatar(val shape: String = "circle", val color: String = "teal") {
    companion object {
        val SHAPES = listOf("circle", "square", "triangle", "diamond", "hexagon", "star")
        val COLORS = listOf("coral", "mango", "teal", "green", "plum", "sky", "yellow")
    }
}

data class Alias(val name: String, val avatar: AliasAvatar)

/** The signed-in user's own profile. Never holds an email. */
data class MyProfile(
    val uid: String,
    val username: String?,
    val displayName: String,
    val photoUrl: String?,
    val alias: Alias?,
    val timeZone: String,
    /** Optional "About me", shown on the public profile. */
    val about: String? = null,
    val goals: Goals = Goals(),
    val settings: UserSettings = UserSettings(),
    val lastSyncAt: Instant? = null,
)

enum class UnitSystem { AUTO, METRIC, IMPERIAL }

data class UserSettings(
    val units: UnitSystem = UnitSystem.AUTO,
    val use24h: Boolean? = null,
    val pushesInsteadOfSteps: Boolean = false,
    val morningDigest: Boolean = true,
    val quietHoursStart: String = "22:00",
    val quietHoursEnd: String = "07:00",
    val quietHoursEnabled: Boolean = true,
    /** Optional, device-only facts for VO2 max bands. */
    val birthYear: Int? = null,
    /** [Gender.key] or null when not set. */
    val sex: String? = null,
    val pinnedMetrics: List<String> = emptyList(),
    /** Opt-outs by [NotificationType.key]; a missing key means on. */
    val notifications: Map<String, Boolean> = emptyMap(),
) {
    fun notificationEnabled(type: NotificationType): Boolean =
        notifications[type.key] ?: if (type == NotificationType.SLEEP_DIGEST) morningDigest else true
}

enum class Gender(val key: String, val label: String) {
    MALE("male", "Male"),
    FEMALE("female", "Female"),
    OTHER("other", "Other"),
    ;

    companion object {
        fun of(key: String?): Gender? = entries.firstOrNull { it.key == key }
    }
}

enum class NotificationSection(val title: String) {
    GROUPS("Groups and friends"),
    CHALLENGES("Challenges"),
}

/**
 * Every kind of push the server sends, one toggle each. [key] is shared with
 * functions/src/lib/push.ts, which skips a push when the user turned its type off.
 */
enum class NotificationType(val key: String, val section: NotificationSection, val title: String, val description: String) {
    INVITES("invites", NotificationSection.GROUPS, "Group invites", "Someone invites you to a group"),
    ROLE_CHANGES("roleChanges", NotificationSection.GROUPS, "Role changes", "You become a mod or owner of a group, or stop being one"),
    NEW_CHALLENGES("newChallenges", NotificationSection.CHALLENGES, "New challenges", "A challenge starts in one of your groups"),
    MILESTONES("milestones", NotificationSection.CHALLENGES, "Team milestones", "A team challenge passes 25%, 50%, 75% or its goal"),
    RESULTS("results", NotificationSection.CHALLENGES, "Final results", "A challenge ends and the results are in"),
    SLEEP_DIGEST("sleepDigest", NotificationSection.CHALLENGES, "Morning sleep digest", "How your sleep pacts went last night, at 9:00"),
}

/** What anyone else can see about a user: never an email. */
data class PublicProfile(
    val username: String,
    val displayName: String,
    val photoUrl: String?,
    val about: String? = null,
)

enum class GroupIdentity { NAMED, ANONYMOUS }

enum class Visibility { PUBLIC, INVITE_ONLY }

enum class Role { OWNER, MOD, MEMBER }

val Role.canManageChallenges: Boolean get() = this == Role.OWNER || this == Role.MOD

val Role.canInvite: Boolean get() = this == Role.OWNER || this == Role.MOD

data class Group(
    val id: String,
    val name: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val photoUrl: String? = null,
    val identity: GroupIdentity = GroupIdentity.NAMED,
    val visibility: Visibility = Visibility.INVITE_ONLY,
    val memberCount: Int = 0,
    val createdAt: Instant? = null,
) {
    companion object {
        val TAGS = listOf("walking", "running", "sleep", "fitness", "mindfulness", "weight", "workplace", "local")
    }
}

/**
 * How another member appears in a group. Named groups show their public profile; anonymous
 * groups only their alias. [memberId] is random per membership and never the uid.
 */
data class Member(
    val memberId: String,
    val role: Role,
    val joinedAt: Instant? = null,
    val username: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
    val alias: String? = null,
    val avatar: AliasAvatar? = null,
) {
    val label: String
        get() = alias ?: displayName?.takeIf { it.isNotBlank() } ?: username ?: "Member"

    val isAnonymous: Boolean get() = alias != null
}

/** The user's own membership, from users/{uid}/memberships. */
data class Membership(
    val groupId: String,
    val memberId: String,
    val role: Role,
    val identity: GroupIdentity,
    val groupName: String,
    val joinedAt: Instant? = null,
)

/** An invite to a group by username, waiting in the user's inbox. */
data class InboxInvite(
    val groupId: String,
    val groupName: String,
    val identity: GroupIdentity,
    val visibility: Visibility,
    val memberCount: Int,
    /** Null for anonymous groups: the inviter stays anonymous too. */
    val invitedBy: String?,
    val createdAt: Instant? = null,
)

data class InviteLink(
    val code: String,
    val link: String,
    /** Null = unlimited uses. */
    val maxUses: Int?,
    val uses: Int,
    /** Null = never expires. */
    val expiresAt: Instant?,
    val usable: Boolean = true,
)

/** What an invite link shows before joining: the group, never its members. */
data class GroupPreview(
    val groupId: String,
    val name: String,
    val description: String,
    val identity: GroupIdentity,
    val visibility: Visibility,
    val memberCount: Int,
    val photoUrl: String?,
)

data class JoinResult(val groupId: String, val memberId: String, val alreadyMember: Boolean)

enum class FeedEventType {
    GROUP_CREATED,
    MEMBER_JOINED,
    MEMBERS_JOINED,
    OWNER_CHANGED,
    MOD_ADDED,
    MOD_REMOVED,
    CHALLENGE_CREATED,
    CHALLENGE_CANCELLED,
    CHALLENGE_ENDED,
    MILESTONE,
    REACTION,
    UNKNOWN,
}

data class FeedEvent(
    val id: String,
    val type: FeedEventType,
    val actorMemberId: String? = null,
    val message: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    val createdAt: Instant = Instant.EPOCH,
)

/** The fixed emoji members react to feed events with. */
object Reactions {
    val EMOJI = listOf("👏", "🔥", "💪", "😴", "❤️")
}

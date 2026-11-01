package fit.bandwagon.core.data.mapper

import com.google.firebase.Timestamp
import fit.bandwagon.core.model.Alias
import fit.bandwagon.core.model.AliasAvatar
import fit.bandwagon.core.model.FeedEvent
import fit.bandwagon.core.model.FeedEventType
import fit.bandwagon.core.model.Goals
import fit.bandwagon.core.model.Group
import fit.bandwagon.core.model.GroupIdentity
import fit.bandwagon.core.model.GroupPreview
import fit.bandwagon.core.model.InboxInvite
import fit.bandwagon.core.model.InviteLink
import fit.bandwagon.core.model.Member
import fit.bandwagon.core.model.Membership
import fit.bandwagon.core.model.MyProfile
import fit.bandwagon.core.model.PublicProfile
import fit.bandwagon.core.model.Role
import fit.bandwagon.core.model.UnitSystem
import fit.bandwagon.core.model.UserSettings
import fit.bandwagon.core.model.Visibility
import fit.bandwagon.core.model.challenge.Challenge
import fit.bandwagon.core.model.challenge.ChallengeSpec
import fit.bandwagon.core.model.challenge.ChallengeStatus
import fit.bandwagon.core.model.challenge.ChallengeSummary
import fit.bandwagon.core.model.challenge.ChallengeTemplates
import fit.bandwagon.core.model.challenge.DayCounts
import fit.bandwagon.core.model.challenge.DayRow
import fit.bandwagon.core.model.challenge.DayStatus
import fit.bandwagon.core.model.challenge.MemberScore
import fit.bandwagon.core.model.challenge.Streak
import fit.bandwagon.core.model.challenge.TopRow
import java.time.Instant

typealias Doc = Map<String, Any?>

internal fun Doc.str(key: String): String? = this[key] as? String
internal fun Doc.num(key: String): Double? = (this[key] as? Number)?.toDouble()
internal fun Doc.int(key: String): Int? = (this[key] as? Number)?.toInt()
internal fun Doc.bool(key: String): Boolean = this[key] == true

@Suppress("UNCHECKED_CAST")
internal fun Doc.map(key: String): Doc? = this[key] as? Map<String, Any?>

internal fun Doc.instant(key: String): Instant? = when (val v = this[key]) {
    is Timestamp -> v.toDate().toInstant()
    is java.util.Date -> v.toInstant()
    is String -> runCatching { Instant.parse(v) }.getOrNull()
    else -> null
}

internal inline fun <reified E : Enum<E>> Doc.enum(key: String, default: E): E =
    str(key)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

/** Firestore documents to models. Never reads an email: there is none to read. */
object FirestoreMappers {
    fun avatar(data: Doc?): AliasAvatar? = data?.let { AliasAvatar(it.str("shape") ?: "circle", it.str("color") ?: "teal") }

    fun myProfile(uid: String, data: Doc): MyProfile {
        val alias = data.map("alias")?.let { a -> a.str("name")?.let { Alias(it, avatar(a.map("avatar")) ?: AliasAvatar()) } }
        val goals = data.map("goals")
        val settings = data.map("settings") ?: emptyMap()
        return MyProfile(
            uid = uid,
            username = data.str("username"),
            displayName = data.str("displayName").orEmpty(),
            photoUrl = data.str("photoUrl"),
            alias = alias,
            timeZone = data.str("tz") ?: "UTC",
            about = data.str("about")?.ifBlank { null },
            goals = Goals(
                steps = goals?.int("steps") ?: Goals().steps,
                activeMinutesWeekly = goals?.int("activeMinutesWeekly") ?: Goals().activeMinutesWeekly,
                sleepMinutes = goals?.int("sleepMinutes") ?: Goals().sleepMinutes,
                hydrationMl = goals?.int("hydrationMl") ?: Goals().hydrationMl,
            ),
            settings = UserSettings(
                units = settings.enum("units", UnitSystem.AUTO),
                use24h = settings["use24h"] as? Boolean,
                pushesInsteadOfSteps = settings.bool("pushesInsteadOfSteps"),
                morningDigest = settings["morningDigest"] as? Boolean ?: true,
                quietHoursStart = settings.str("quietHoursStart") ?: "22:00",
                quietHoursEnd = settings.str("quietHoursEnd") ?: "07:00",
                quietHoursEnabled = settings["quietHoursEnabled"] as? Boolean ?: true,
                birthYear = settings.int("birthYear"),
                sex = settings.str("sex"),
                pinnedMetrics = (settings["pinnedMetrics"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                notifications = (settings["notifications"] as? Map<*, *>)
                    ?.mapNotNull { (k, v) -> if (k is String && v is Boolean) k to v else null }?.toMap() ?: emptyMap(),
            ),
            lastSyncAt = data.instant("lastSyncAt"),
        )
    }

    fun settingsMap(settings: UserSettings): Map<String, Any?> = mapOf(
        "units" to settings.units.name,
        "use24h" to settings.use24h,
        "pushesInsteadOfSteps" to settings.pushesInsteadOfSteps,
        "morningDigest" to settings.morningDigest,
        "quietHoursStart" to settings.quietHoursStart,
        "quietHoursEnd" to settings.quietHoursEnd,
        "quietHoursEnabled" to settings.quietHoursEnabled,
        "birthYear" to settings.birthYear,
        "sex" to settings.sex,
        "pinnedMetrics" to settings.pinnedMetrics,
        "notifications" to settings.notifications,
    )

    fun goalsMap(goals: Goals): Map<String, Any?> = mapOf(
        "steps" to goals.steps,
        "activeMinutesWeekly" to goals.activeMinutesWeekly,
        "sleepMinutes" to goals.sleepMinutes,
        "hydrationMl" to goals.hydrationMl,
    )

    fun publicProfile(data: Doc): PublicProfile? {
        val username = data.str("username") ?: return null
        return PublicProfile(username, data.str("displayName") ?: username, data.str("photoUrl"), data.str("about")?.ifBlank { null })
    }

    fun group(id: String, data: Doc): Group = Group(
        id = id,
        name = data.str("name").orEmpty(),
        description = data.str("description").orEmpty(),
        tags = (data["tags"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        photoUrl = data.str("photoUrl"),
        identity = data.enum("identity", GroupIdentity.NAMED),
        visibility = data.enum("visibility", Visibility.INVITE_ONLY),
        memberCount = data.int("memberCount") ?: 0,
        createdAt = data.instant("createdAt"),
    )

    fun member(id: String, data: Doc): Member = Member(
        memberId = id,
        role = data.enum("role", Role.MEMBER),
        joinedAt = data.instant("joinedAt"),
        username = data.str("username"),
        displayName = data.str("displayName"),
        photoUrl = data.str("photoUrl"),
        alias = data.str("alias"),
        avatar = avatar(data.map("avatar")),
    )

    fun membership(groupId: String, data: Doc): Membership = Membership(
        groupId = groupId,
        memberId = data.str("memberId").orEmpty(),
        role = data.enum("role", Role.MEMBER),
        identity = data.enum("identity", GroupIdentity.NAMED),
        groupName = data.str("groupName").orEmpty(),
        joinedAt = data.instant("joinedAt"),
    )

    fun inbox(groupId: String, data: Doc): InboxInvite = InboxInvite(
        groupId = groupId,
        groupName = data.str("groupName").orEmpty(),
        identity = data.enum("identity", GroupIdentity.NAMED),
        visibility = data.enum("visibility", Visibility.INVITE_ONLY),
        memberCount = data.int("memberCount") ?: 0,
        invitedBy = data.str("invitedBy"),
        createdAt = data.instant("createdAt"),
    )

    fun invite(data: Doc): InviteLink = InviteLink(
        code = data.str("code").orEmpty(),
        link = data.str("link").orEmpty(),
        maxUses = data.int("maxUses"),
        uses = data.int("uses") ?: 0,
        expiresAt = data.instant("expiresAt"),
        usable = data["usable"] as? Boolean ?: true,
    )

    fun preview(data: Doc): GroupPreview = GroupPreview(
        groupId = data.str("groupId").orEmpty(),
        name = data.str("name").orEmpty(),
        description = data.str("description").orEmpty(),
        identity = data.enum("identity", GroupIdentity.NAMED),
        visibility = data.enum("visibility", Visibility.INVITE_ONLY),
        memberCount = data.int("memberCount") ?: 0,
        photoUrl = data.str("photoUrl"),
    )

    fun feedEvent(id: String, data: Doc): FeedEvent = FeedEvent(
        id = id,
        type = data.enum("type", FeedEventType.UNKNOWN),
        actorMemberId = data.str("actorMemberId"),
        message = data.str("message"),
        data = data.map("data") ?: emptyMap(),
        createdAt = data.instant("createdAt") ?: Instant.EPOCH,
    )

    /** A spec this app version can't decode becomes null, and the UI asks to update. */
    fun spec(data: Doc?): ChallengeSpec? = data?.let {
        runCatching { ChallengeTemplates.json.decodeFromJsonElement(ChallengeSpec.serializer(), it.toJson()) }.getOrNull()
    }

    /**
     * The template JSON leaves out default values, but the server requires `schemaVersion`
     * (functions/src/domain/engine.ts), so it is always written.
     */
    fun specMap(spec: ChallengeSpec): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val map = ChallengeTemplates.json.encodeToJsonElement(ChallengeSpec.serializer(), spec).toPlainValue() as Map<String, Any?>
        return map + ("schemaVersion" to spec.schemaVersion)
    }

    fun challenge(groupId: String, id: String, data: Doc): Challenge = Challenge(
        id = id,
        groupId = groupId,
        spec = spec(data.map("spec")),
        status = data.enum("status", ChallengeStatus.ACTIVE),
        createdByMemberId = data.str("createdByMemberId"),
        createdAt = data.instant("createdAt"),
    )

    fun score(id: String, data: Doc): MemberScore = MemberScore(
        memberId = id,
        score = data.num("score") ?: 0.0,
        tieBreak = data.num("tieBreak") ?: 0.0,
        progress = data.num("progress"),
        daysHit = data.int("daysHit") ?: 0,
        daysWithData = data.int("daysWithData") ?: 0,
        longestStreak = data.int("longestStreak") ?: 0,
        finished = data.bool("finished"),
        notShared = data.bool("notShared"),
        flagged = data.bool("flagged"),
        lastSyncAt = data.instant("lastSyncAt"),
    )

    @Suppress("UNCHECKED_CAST")
    fun summary(data: Doc): ChallengeSummary = ChallengeSummary(
        top = (data["top"] as? List<Doc>)?.map {
            TopRow(
                memberId = it.str("memberId").orEmpty(),
                score = it.num("score") ?: 0.0,
                rank = it.int("rank") ?: 0,
                finished = it.bool("finished"),
                notShared = it.bool("notShared"),
            )
        } ?: emptyList(),
        participants = data.int("participants") ?: 0,
        scored = data.int("scored") ?: 0,
        finishedCount = data.int("finishedCount") ?: 0,
        total = data.num("total"),
        target = data.num("target"),
        pct = data.num("pct"),
        milestonesHit = (data["milestonesHit"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList(),
        streak = data.map("streak")?.let { Streak(it.int("current") ?: 0, it.int("best") ?: 0) },
        refreshedAt = data.instant("refreshedAt"),
        finalised = data.bool("finalised"),
    )

    fun dayRow(id: String, data: Doc): DayRow = DayRow(
        memberId = id,
        status = data.enum("status", DayStatus.MISSED),
        bedtime = data.instant("bedtime"),
        zoneId = data.str("zoneId"),
    )

    fun dayCounts(data: Doc): DayCounts = DayCounts(
        date = data.str("date").orEmpty(),
        hit = data.int("hit") ?: 0,
        missed = data.int("missed") ?: 0,
        members = data.int("members") ?: 0,
    )
}

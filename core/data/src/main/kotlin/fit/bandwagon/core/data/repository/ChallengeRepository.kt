package fit.bandwagon.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fit.bandwagon.core.data.remote.RemoteDataSource
import fit.bandwagon.core.model.challenge.Challenge
import fit.bandwagon.core.model.challenge.ChallengeSpec
import fit.bandwagon.core.model.challenge.ChallengeSummary
import fit.bandwagon.core.model.challenge.ChallengeTemplate
import fit.bandwagon.core.model.challenge.ChallengeTemplates
import fit.bandwagon.core.model.challenge.DayCounts
import fit.bandwagon.core.model.challenge.DayRow
import fit.bandwagon.core.model.challenge.MemberScore
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChallengeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val remote: RemoteDataSource,
) {
    /** Presets from shared/challenge-templates.json, bundled as an asset at build time. */
    val templates: List<ChallengeTemplate> by lazy {
        runCatching {
            ChallengeTemplates.parse(context.assets.open("challenge-templates.json").bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())
    }

    fun template(id: String): ChallengeTemplate? = templates.firstOrNull { it.id == id }

    fun observeChallenges(groupId: String): Flow<List<Challenge>> = remote.observeChallenges(groupId)
    fun observeChallenge(groupId: String, challengeId: String): Flow<Challenge?> = remote.observeChallenge(groupId, challengeId)
    fun observeSummary(groupId: String, challengeId: String): Flow<ChallengeSummary?> = remote.observeSummary(groupId, challengeId)
    fun observeScore(groupId: String, challengeId: String, memberId: String): Flow<MemberScore?> =
        remote.observeScore(groupId, challengeId, memberId)

    fun observeLeaderboard(groupId: String, challengeId: String, limit: Long): Flow<List<MemberScore>> =
        remote.observeTopScores(groupId, challengeId, limit)

    /** Rank is computed on read: how many scored higher, plus one. Works in any size of group. */
    suspend fun rankOf(groupId: String, challengeId: String, score: Double): Long =
        remote.countAbove(groupId, challengeId, score) + 1

    fun observeDayRows(groupId: String, challengeId: String, date: String, limit: Long = 100): Flow<List<DayRow>> =
        remote.observeDayRows(groupId, challengeId, date, limit)

    suspend fun dayHistory(groupId: String, challengeId: String, days: Long = 14): List<DayCounts> =
        remote.dayHistory(groupId, challengeId, days)

    suspend fun create(groupId: String, spec: ChallengeSpec): String = remote.createChallenge(groupId, spec)
    suspend fun update(groupId: String, challengeId: String, spec: ChallengeSpec) = remote.updateChallenge(groupId, challengeId, spec)
    suspend fun cancel(groupId: String, challengeId: String) = remote.cancelChallenge(groupId, challengeId)
}

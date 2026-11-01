package fit.bandwagon.feature.you

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import fit.bandwagon.core.data.remote.RemoteError
import fit.bandwagon.core.data.repository.AuthRepository
import fit.bandwagon.core.data.repository.DailySummaryRepository
import fit.bandwagon.core.data.repository.GoalRepository
import fit.bandwagon.core.data.repository.Handles
import fit.bandwagon.core.data.repository.ProfileRepository
import fit.bandwagon.core.data.repository.ScoreRepository
import fit.bandwagon.core.health.HealthCapabilitiesRepository
import fit.bandwagon.core.health.store.HealthRepository
import fit.bandwagon.core.model.AliasAvatar
import fit.bandwagon.core.model.Gender
import fit.bandwagon.core.model.HealthCapabilities
import fit.bandwagon.core.model.MyProfile
import fit.bandwagon.core.model.NotificationType
import fit.bandwagon.core.model.UnitSystem
import fit.bandwagon.core.model.UserSettings
import fit.bandwagon.core.model.scores.ScoreKind
import fit.bandwagon.core.sync.SyncEngine
import fit.bandwagon.core.sync.SyncStatus
import fit.bandwagon.core.ui.HandleStatus
import fit.bandwagon.core.ui.suggestAlias
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class DataSource(val label: String, val lastSeen: Instant)

data class YouUiState(
    val profile: MyProfile? = null,
    val capabilities: HealthCapabilities = HealthCapabilities.NONE,
    val sources: List<DataSource> = emptyList(),
    val working: Boolean = false,
    val message: String? = null,
    val signedOut: Boolean = false,
    val aliasDraft: String = "",
    val avatarDraft: AliasAvatar = AliasAvatar(),
    val aliasStatus: HandleStatus = HandleStatus.Idle,
    val usernameDraft: String = "",
    val usernameStatus: HandleStatus = HandleStatus.Idle,
)

/** How the edit-profile dialog changes the photo on save. */
sealed interface PhotoChange {
    data object Keep : PhotoChange
    data object Remove : PhotoChange
    data object UseAccount : PhotoChange
    data class Upload(val uri: Uri) : PhotoChange
}

@HiltViewModel
class YouViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val capabilities: HealthCapabilitiesRepository,
    private val healthStore: HealthRepository,
    private val daily: DailySummaryRepository,
    private val syncEngine: SyncEngine,
    private val goals: GoalRepository,
    private val scores: ScoreRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(YouUiState())
    val state: StateFlow<YouUiState> = _state.asStateFlow()
    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            profiles.observeProfile().collect { profile ->
                _state.update {
                    it.copy(
                        profile = profile,
                        aliasDraft = it.aliasDraft.ifBlank { profile?.alias?.name ?: suggestAlias() },
                        avatarDraft = profile?.alias?.avatar ?: it.avatarDraft,
                        usernameDraft = it.usernameDraft.ifBlank { profile?.username.orEmpty() },
                    )
                }
            }
        }
        viewModelScope.launch { capabilities.capabilities.collect { caps -> _state.update { it.copy(capabilities = caps) } } }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            capabilities.refresh()
            val origins = runCatching { healthStore.recentOrigins() }.getOrDefault(emptyMap())
            _state.update { it.copy(sources = origins.map { (pkg, at) -> DataSource(appLabel(pkg), at) }.sortedByDescending { s -> s.lastSeen }) }
        }
    }

    /** Data sources by their installed app label, never a hard-coded brand (AGENTS.md). */
    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrElse { packageName }

    /** Name, "About me" and photo from the edit-profile dialog, saved together. */
    fun saveProfile(name: String, about: String, photo: PhotoChange) = work("Profile saved") {
        val profile = _state.value.profile
        if (name.isNotBlank() && name.trim() != profile?.displayName) profiles.updateDisplayName(name)
        if (about.trim() != profile?.about.orEmpty()) profiles.updateAbout(about)
        when (photo) {
            PhotoChange.Keep -> Unit
            PhotoChange.Remove -> profiles.updatePhoto(null)
            PhotoChange.UseAccount -> profiles.updatePhoto(auth.accountPhotoUrl)
            is PhotoChange.Upload -> profiles.uploadPhoto(withContext(Dispatchers.Default) { squareJpeg(photo.uri) })
        }
    }

    /** Centre-cropped, 512 px square JPEG: small enough for every avatar and the 2 MB storage rule. */
    private fun squareJpeg(uri: Uri, size: Int = 512): ByteArray {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val shortest = minOf(info.size.width, info.size.height)
            if (shortest >= size * 2) decoder.setTargetSampleSize(shortest / size)
        }
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val scaled = if (side > size) Bitmap.createScaledBitmap(square, size, size, true) else square
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    val accountPhotoUrl: String? get() = auth.accountPhotoUrl

    /** Settings save as soon as they change; Firestore's local cache updates the UI at once. */
    fun updateSettings(transform: (UserSettings) -> UserSettings) {
        val current = _state.value.profile?.settings ?: UserSettings()
        work { profiles.updateSettings(transform(current)) }
    }

    fun setGender(gender: Gender?) = updateSettings { it.copy(sex = gender?.key) }

    fun setUnits(units: UnitSystem) = updateSettings { it.copy(units = units) }

    fun setNotification(type: NotificationType, on: Boolean) =
        updateSettings { it.copy(notifications = it.notifications + (type.key to on)) }

    fun setQuietHours(on: Boolean) = updateSettings { it.copy(quietHoursEnabled = on) }

    fun onUsernameDraft(value: String) {
        _state.update { it.copy(usernameDraft = value) }
        check(value, isAlias = false)
    }

    fun onAliasDraft(value: String) {
        _state.update { it.copy(aliasDraft = value) }
        check(value, isAlias = true)
    }

    fun onAvatarDraft(avatar: AliasAvatar) = _state.update { it.copy(avatarDraft = avatar) }

    private fun check(value: String, isAlias: Boolean) {
        checkJob?.cancel()
        val handle = Handles.normalise(value)
        val current = if (isAlias) _state.value.profile?.alias?.name else _state.value.profile?.username
        val set: (HandleStatus) -> Unit = { s -> _state.update { if (isAlias) it.copy(aliasStatus = s) else it.copy(usernameStatus = s) } }
        if (handle == current) return set(HandleStatus.Available)
        Handles.problem(handle)?.let { return set(HandleStatus.Problem(it)) }
        set(HandleStatus.Checking)
        checkJob = viewModelScope.launch {
            delay(350)
            val free = runCatching { if (isAlias) profiles.isAliasAvailable(handle) else profiles.isUsernameAvailable(handle) }.getOrDefault(true)
            set(if (free) HandleStatus.Available else HandleStatus.Problem(if (isAlias) "That alias is taken." else "That username is taken."))
        }
    }

    fun saveAlias(onDone: () -> Unit) = work("Alias saved") {
        profiles.setAlias(_state.value.aliasDraft, _state.value.avatarDraft)
        onDone()
    }

    fun saveUsername(onDone: () -> Unit) = work("Username saved") {
        profiles.claimUsername(_state.value.usernameDraft)
        onDone()
    }

    fun syncNow() = work {
        val result = syncEngine.sync(forceFull = true)
        _state.update {
            it.copy(
                message = when (result.status) {
                    SyncStatus.SUCCESS -> "Synced ${result.checkedDates} days"
                    SyncStatus.PERMISSION_MISSING -> "Share activity or sleep data to sync"
                    else -> result.message ?: "Sync failed"
                },
            )
        }
    }

    /**
     * Everything Bandwagon keeps about you, as one JSON file written where the user chose (the
     * system "save to" screen opens in Downloads): profile, settings, goals with their history,
     * recorded scores and the daily summaries that were synced.
     */
    fun download(target: Uri) = work("Your data was saved") {
        val json = withContext(Dispatchers.IO) { buildDownload().toString() }
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(target, "wt")?.use { it.write(json.toByteArray()) }
                ?: error("Couldn't write the file")
        }
    }

    private suspend fun buildDownload(): JsonObject {
        val profile = _state.value.profile
        val today = LocalDate.now()
        val since = today.minusDays(400)
        fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to json(v) })
        val goalList = goals.observeGoals().first()
        return obj(
            "exportedAt" to Instant.now().toString(),
            "profile" to obj(
                "username" to profile?.username,
                "displayName" to profile?.displayName,
                "about" to profile?.about,
                "photoUrl" to profile?.photoUrl,
                "alias" to profile?.alias?.name,
                "timeZone" to profile?.timeZone,
            ),
            "settings" to profile?.settings?.let { s ->
                obj(
                    "units" to s.units.name,
                    "gender" to s.sex,
                    "quietHours" to if (s.quietHoursEnabled) "${s.quietHoursStart}-${s.quietHoursEnd}" else null,
                    "notifications" to JsonObject(NotificationType.entries.associate { it.key to JsonPrimitive(s.notificationEnabled(it)) }),
                )
            },
            "goals" to JsonArray(
                goalList.map { g ->
                    obj(
                        "metric" to g.metric.name,
                        "period" to g.period.name,
                        "target" to g.target,
                        "dailyThreshold" to g.dailyThreshold,
                        "createdOn" to g.createdOn.toString(),
                        "history" to JsonArray(
                            goals.outcomes(g.id).map { o ->
                                obj("start" to o.window.start.toString(), "end" to o.window.endInclusive.toString(), "target" to o.target, "achieved" to o.achieved, "result" to o.result.name)
                            },
                        ),
                    )
                },
            ),
            "scores" to obj(
                *ScoreKind.entries.map { kind ->
                    kind.name.lowercase() to JsonArray(scores.range(kind, since, today).map { (date, score) -> obj("date" to date.toString(), "score" to score.value) })
                }.toTypedArray(),
            ),
            "dailySummaries" to JsonArray(
                daily.recent(400).map { s ->
                    obj(
                        "date" to s.date.toString(),
                        "steps" to s.steps,
                        "distanceMeters" to s.distanceMeters,
                        "activeCalories" to s.activeCalories,
                        "activeMinutes" to s.activeMinutes,
                        "workoutMinutes" to s.workoutMinutes,
                        "workoutCount" to s.workoutCount,
                        "floors" to s.floors,
                        "mindfulMinutes" to s.mindfulMinutes,
                        "sleepMinutes" to s.sleep?.durationMinutes,
                    )
                },
            ),
        )
    }

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private suspend fun clearLocal() {
        healthStore.clear()
        daily.clearLocal()
        goals.clear()
        scores.clear()
    }

    fun signOut() {
        viewModelScope.launch {
            clearLocal()
            auth.signOut()
            _state.update { it.copy(signedOut = true) }
        }
    }

    fun deleteAccount() = work {
        profiles.deleteAccount()
        clearLocal()
        auth.signOut()
        _state.update { it.copy(signedOut = true) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun work(success: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(working = true) }
            try {
                block()
                if (success != null) _state.update { it.copy(message = success) }
            } catch (e: RemoteError) {
                _state.update { it.copy(message = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Something went wrong") }
            }
            _state.update { it.copy(working = false) }
        }
    }
}

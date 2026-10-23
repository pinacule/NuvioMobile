package com.nuvio.app.core.sync

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.collection.CollectionMobileSettingsRepository
import com.nuvio.app.features.collection.CollectionMobileSettingsStorage
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsStorage
import com.nuvio.app.features.details.MetaScreenSettingsRepository
import com.nuvio.app.features.mdblist.MdbListMetadataService
import com.nuvio.app.features.mdblist.MdbListSettingsStorage
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationsRepository
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.core.ui.CardDepthStyleRepository
import com.nuvio.app.core.ui.CardDepthStyleStorage
import com.nuvio.app.core.ui.PosterCardStyleRepository
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.CustomPosterUrlStorage
import com.nuvio.app.core.ui.PosterCardStyleStorage
import com.nuvio.app.features.settings.ThemeSettingsStorage
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamBadgeSettingsStorage
import com.nuvio.app.features.tmdb.TmdbSettingsStorage
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktCommentsStorage
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.trakt.TraktSettingsStorage
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesStorage
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

private const val PUSH_DEBOUNCE_MS = 1500L

object ProfileSettingsSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("ProfileSettingsSync")
    private val syncMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var isApplyingRemoteBlob: Boolean = false

    @Volatile
    private var isServerSyncInFlight: Boolean = false

    @Volatile
    private var skipNextPushSignature: String? = null

    /**
     * Profiles with a local settings change that is known to not have reached the
     * server (stranded by an interrupted pull or a failed compensating push),
     * mapped to the account generation that recorded them. A pending push is
     * retried before that profile's next pull — before the pull's whole-blob
     * apply can overwrite the change — and cleared by any successful push of
     * that profile's whole blob. The generation guard keeps markers recorded
     * under a previous account from firing after an account switch.
     *
     * The map is persisted on every mutation (see
     * [ProfileSettingsPendingPushStorage]) and restored during initialisation:
     * without that, a process death between a mid-pull local change and its
     * compensating push lost the marker, and the next launch's pull applied
     * the remote blob over the never-pushed local change.
     */
    private val pendingPushLock = SynchronizedObject()
    private val pendingPushGenerations = mutableMapOf<Int, Int>()

    /** Guarded by [pendingPushLock]. */
    private var pendingPushGenerationsLoaded: Boolean = false

    @Volatile
    private var accountSyncGeneration: Int = 0

    private var observeJob: Job? = null

    fun startObserving() {
        if (observeJob?.isActive == true) return
        ensurePendingPushGenerationsLoaded()
        ensureRepositoriesLoaded()
        ProviderCredentialSync.startObserving()
        MediaServerSync.startObserving()
        observeLocalChangesAndPush()
    }

    fun clearAccountState() {
        observeJob?.cancel()
        observeJob = null
        skipNextPushSignature = null
        accountSyncGeneration++
        synchronized(pendingPushLock) {
            pendingPushGenerations.clear()
            // The account's markers must not survive into the next account,
            // in this process or after a restart. Mark the (empty) state as
            // loaded too, so a failed persist cannot resurrect stale markers
            // later in this session.
            pendingPushGenerationsLoaded = true
            persistPendingPushGenerationsLocked()
        }
        ProviderCredentialSync.clearAccountState()
        MediaServerSync.clearAccountState()
    }

    /**
     * Drops [profileId]'s pending-push marker — from memory and from the
     * persisted payload — when the profile is deleted. The marker map is
     * account-scoped, but its entries are keyed by profile index inside the
     * single payload, so the per-profile storage sweep cannot reach them:
     * left behind, the marker would fire against the next profile created at
     * the recycled index and discard its first settings pull as a stranded
     * push.
     */
    fun removeProfile(profileId: Int) {
        // Load first so a not-yet-restored marker is the one being cleared
        // (and its persisted copy with it), mirroring pushProfileSettingsToRemote:
        // clearing only the in-memory map would leave the persisted marker
        // to be restored on the next launch.
        ensurePendingPushGenerationsLoaded()
        clearPendingPush(profileId)
    }

    private fun markPendingPush(profileId: Int, generation: Int) {
        synchronized(pendingPushLock) {
            pendingPushGenerations[profileId] = generation
            persistPendingPushGenerationsLocked()
        }
    }

    private fun hasPendingPush(profileId: Int, generation: Int): Boolean =
        synchronized(pendingPushLock) { pendingPushGenerations[profileId] == generation }

    private fun clearPendingPush(profileId: Int) {
        synchronized(pendingPushLock) {
            // Only an actual removal is a mutation worth persisting. Callers
            // invoke this after an acknowledged successful push, so the
            // persisted marker is cleared only once the push has landed.
            if (pendingPushGenerations.remove(profileId) != null) {
                persistPendingPushGenerationsLocked()
            }
        }
    }

    /** Caller must hold [pendingPushLock]. */
    private fun persistPendingPushGenerationsLocked() {
        runCatching {
            if (pendingPushGenerations.isEmpty()) {
                ProfileSettingsPendingPushStorage.clearPayload()
            } else {
                ProfileSettingsPendingPushStorage.savePayload(
                    encodePendingPushGenerations(pendingPushGenerations),
                )
            }
        }.onFailure { error ->
            log.e(error) { "persistPendingPushGenerations — FAILED; markers stay in memory only" }
        }
    }

    /**
     * Loads persisted pending-push markers once, before the first pull can
     * apply a remote blob (called from startObserving, pull and the push
     * entry point). Restored markers are rebased onto the current account
     * generation (see restorePendingPushGenerations), so the pull's
     * pre-fetch stranded push treats a restored marker exactly like one
     * recorded in this session and pushes local state before fetching.
     */
    private fun ensurePendingPushGenerationsLoaded() {
        synchronized(pendingPushLock) {
            if (pendingPushGenerationsLoaded) return
            pendingPushGenerationsLoaded = true
            val restored = runCatching {
                restorePendingPushGenerations(
                    ProfileSettingsPendingPushStorage.loadPayload(),
                    accountSyncGeneration,
                )
            }.onFailure { error ->
                log.e(error) { "loadPendingPushGenerations — FAILED; continuing without restored markers" }
            }.getOrDefault(emptyMap())
            // Never overwrite a marker recorded in this session before the
            // load ran; the load happens at every sync entry point, so this
            // merge is only a belt-and-braces guard.
            restored.forEach { (profileId, generation) ->
                if (!pendingPushGenerations.containsKey(profileId)) {
                    pendingPushGenerations[profileId] = generation
                }
            }
        }
    }

    fun onProfileChanged() {
        if (observeJob?.isActive != true) return
        skipNextPushSignature = currentObservedStateSignature()
        ProviderCredentialSync.onProfileChanged()
    }

    suspend fun pull(profileId: Int): Boolean {
        // Restored markers must be in the map before the pre-fetch stranded
        // push below checks them, so no pull can apply a remote blob over a
        // change whose marker survived a process death.
        ensurePendingPushGenerationsLoaded()
        ensureRepositoriesLoaded()
        val pullGeneration = accountSyncGeneration
        var pushLocalAfterPull = false
        val applied = syncMutex.withLock {
            if (ProfileRepository.activeProfileId != profileId) {
                log.d { "pull(profileId=$profileId) — skipped because profile is no longer active" }
                return@withLock false
            }
            // A change stranded by an earlier interrupted pull of this profile —
            // including a marker restored from storage after a process death —
            // is pushed before fetching: otherwise this pull's whole-blob apply
            // could overwrite it before it ever reaches the server. Best-effort —
            // on failure the marker stays and the pull proceeds (its RPC will
            // most likely fail the same way and re-settle below).
            if (hasPendingPush(profileId, pullGeneration)) {
                pushProfileSettingsLocked(profileId)
            }
            var exit = ProfileSettingsPullExit.Failed
            var preFetchLocalSignature: String? = null
            var remoteSignature: String? = null
            isServerSyncInFlight = true
            try {
                val localBlob = exportSettingsBlob()
                if (ProfileRepository.activeProfileId != profileId) {
                    exit = ProfileSettingsPullExit.ProfileSwitched
                    return@withLock false
                }
                val localSignature = buildSignature(localBlob)
                preFetchLocalSignature = localSignature

                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_platform", MOBILE_SYNC_PLATFORM)
                }
                val result = SupabaseProvider.client.postgrest.rpc("sync_pull_profile_settings_blob", params)
                if (ProfileRepository.activeProfileId != profileId) {
                    exit = ProfileSettingsPullExit.ProfileSwitched
                    return@withLock false
                }
                val response = result.decodeList<SettingsBlobResponse>().firstOrNull()
                val remoteJson = response?.settingsJson

                if (remoteJson == null) {
                    log.i { "pull(profileId=$profileId) — no remote settings blob found" }
                    exit = ProfileSettingsPullExit.NoRemoteBlob
                    return@withLock false
                }

                val remoteBlob = runCatching {
                    json.decodeFromJsonElement(MobileProfileSettingsBlob.serializer(), remoteJson)
                }.getOrElse { error ->
                    log.e(error) { "pull(profileId=$profileId) — failed to decode remote settings blob" }
                    exit = ProfileSettingsPullExit.UndecodableRemote
                    return@withLock false
                }
                val fetchedRemoteSignature = buildSignature(remoteBlob)
                remoteSignature = fetchedRemoteSignature

                // The user may have changed settings while the RPC was in flight.
                // Re-read the current local signature before deciding: if it moved
                // since the pre-fetch snapshot, the local change wins and the
                // remote blob must not overwrite it.
                if (ProfileRepository.activeProfileId != profileId) {
                    exit = ProfileSettingsPullExit.ProfileSwitched
                    return@withLock false
                }
                val postFetchLocalSignature = currentObservedStateSignature()
                when (decideProfileSettingsPull(localSignature, postFetchLocalSignature, fetchedRemoteSignature)) {
                    ProfileSettingsPullDecision.SkipRemoteMatchesLocal -> {
                        log.d { "pull(profileId=$profileId) — remote matches local" }
                        exit = ProfileSettingsPullExit.RemoteMatchedLocal
                        return@withLock false
                    }
                    ProfileSettingsPullDecision.SkipLocalChangedDuringPull -> {
                        log.i { "pull(profileId=$profileId) — local settings changed during pull; keeping local state" }
                        exit = ProfileSettingsPullExit.LocalChangedDuringPull
                        return@withLock false
                    }
                    ProfileSettingsPullDecision.ApplyRemote -> Unit
                }

                if (ProfileRepository.activeProfileId != profileId) {
                    exit = ProfileSettingsPullExit.ProfileSwitched
                    return@withLock false
                }
                isApplyingRemoteBlob = true
                try {
                    applyRemoteBlob(remoteBlob)
                    // Only absorb the applied state into the push-skip signature
                    // when what landed locally is exactly the applied blob. If a
                    // local change slipped in during the apply, the post-apply
                    // signature diverges from the remote signature; leaving the
                    // skip unset lets the exit settle below treat the divergence
                    // as a local change (push/pending) instead of masking it, and
                    // keeps the observer able to re-fire for that signature.
                    val postApplySignature = currentObservedStateSignature()
                    if (postApplySignature == fetchedRemoteSignature) {
                        skipNextPushSignature = postApplySignature
                    }
                } finally {
                    isApplyingRemoteBlob = false
                }

                log.i { "pull(profileId=$profileId) — applied remote settings blob" }
                exit = ProfileSettingsPullExit.AppliedRemote
                true
            } catch (error: Exception) {
                log.e(error) { "pull(profileId=$profileId) — FAILED" }
                false
            } finally {
                isServerSyncInFlight = false
                // Unified exit discipline: EVERY exit (not just the decision
                // path) settles the fate of a possible in-flight local change
                // before returning, comparing the pulled profile's current
                // local signature against the pre-fetch snapshot. A change is
                // either pushed (bound to this profile) or recorded as a
                // pending push — never silently stranded for the next pull's
                // whole-blob apply to overwrite.
                val stillActive = ProfileRepository.activeProfileId == profileId
                val exitLocalSignature = if (stillActive) {
                    runCatching { currentObservedStateSignature() }.getOrNull()
                } else {
                    null
                }
                when (
                    decideProfileSettingsPullExitAction(
                        exit = exit,
                        // An unreadable local state is treated like an inactive
                        // profile: no comparison is possible, so the change is
                        // recorded as pending instead of being judged settled.
                        pulledProfileStillActive = stillActive && exitLocalSignature != null,
                        preFetchLocalSignature = preFetchLocalSignature,
                        exitLocalSignature = exitLocalSignature,
                        remoteSignature = remoteSignature,
                        appliedRemoteSignature = if (exit == ProfileSettingsPullExit.AppliedRemote) {
                            remoteSignature
                        } else {
                            null
                        },
                    )
                ) {
                    ProfileSettingsPullExitAction.PushLocalNow -> {
                        // Record the pending push before attempting it: if the
                        // push below fails, the marker is what retries it.
                        markPendingPush(profileId, pullGeneration)
                        pushLocalAfterPull = true
                    }
                    ProfileSettingsPullExitAction.MarkPendingPush ->
                        markPendingPush(profileId, pullGeneration)
                    ProfileSettingsPullExitAction.None -> Unit
                }
            }
        }
        if (pushLocalAfterPull) {
            // The local-change observer drops events while a sync is in flight, so a
            // change made during the pull may never re-fire it. Push the winning
            // local state explicitly once the sync lock has been released. The push
            // is bound to the pulled profile: if that profile is no longer active
            // it is skipped (its blob cannot be exported) and the pending marker
            // retries it before that profile's next pull.
            scope.launch { pushProfileSettingsToRemote(profileId) }
        }
        return applied
    }

    suspend fun pushCurrentProfileToRemote(): Boolean =
        pushProfileSettingsToRemote(ProfileRepository.activeProfileId)

    /**
     * Pushes [profileId]'s current local settings blob to the server. The settings
     * export machinery is scoped to the active profile, so for a non-active
     * profile this records (or keeps) a pending push and returns false instead of
     * pushing whatever profile happens to be active.
     */
    suspend fun pushProfileSettingsToRemote(profileId: Int): Boolean {
        // A successful push clears the profile's marker via clearPendingPush;
        // load first so a restored marker is the one being cleared (and its
        // persisted copy with it) instead of surviving to fire redundantly.
        ensurePendingPushGenerationsLoaded()
        ensureRepositoriesLoaded()
        return syncMutex.withLock { pushProfileSettingsLocked(profileId) != null }
    }

    /**
     * Caller must hold [syncMutex]. Returns the pushed state's signature on
     * success, or null when the profile is not active or the push failed (in
     * which case any pending-push marker for the profile is kept).
     */
    private suspend fun pushProfileSettingsLocked(profileId: Int): String? {
        if (ProfileRepository.activeProfileId != profileId) {
            log.d { "pushProfileSettingsToRemote(profileId=$profileId) — skipped because profile is not active" }
            return null
        }
        return runCatching {
            val blob = exportSettingsBlob()
            if (ProfileRepository.activeProfileId != profileId) return@runCatching null
            val signature = buildSignature(blob)
            pushToRemoteLocked(profileId, blob)
            // The whole blob — including any previously stranded change — is now
            // on the server, so the pending marker for this profile is satisfied.
            clearPendingPush(profileId)
            // Suppress the observer echoing the exact state just pushed.
            skipNextPushSignature = signature
            signature
        }.onFailure { error ->
            log.e(error) { "pushProfileSettingsToRemote(profileId=$profileId) — FAILED" }
        }.getOrNull()
    }

    @OptIn(FlowPreview::class)
    private fun observeLocalChangesAndPush() {
        val signatureFlows = listOf(
            ThemeSettingsRepository.selectedThemePreference.map { "theme" },
            ThemeSettingsRepository.customThemePreference.map { "custom_theme_colors" },
            ThemeSettingsRepository.amoledEnabled.map { "amoled" },
            ThemeSettingsRepository.liquidGlassNativeTabBarEnabled.map { "liquid_glass_tab_bar" },
            ThemeSettingsRepository.navBarGlowEnabled.map { "nav_bar_glow_enabled" },
            ThemeSettingsRepository.navBarStyle.map { "nav_bar_style" },
            PosterCardStyleRepository.uiState.map { "poster_card_style" },
            CustomPosterUrlRepository.pattern.map { "custom_poster_url" },
            CustomPosterUrlRepository.enabledScreens.map { "custom_poster_screens" },
            CardDepthStyleRepository.uiState.map { "card_depth_style" },
            PlayerSettingsRepository.uiState.map { "player" },
            StreamBadgeSettingsRepository.uiState.map { "stream_badges" },
            DebridSettingsRepository.uiState.map { "debrid" },
            TmdbSettingsRepository.uiState.map { "tmdb" },
            MdbListSettingsRepository.uiState.map { "mdblist" },
            MetaScreenSettingsRepository.uiState.map { "meta" },
            CollectionMobileSettingsRepository.uiState.map { "collection_mobile_settings" },
            ContinueWatchingPreferencesRepository.uiState.map { "continue_watching" },
            TrackingSettingsRepository.uiState.map { "trakt_settings" },
            TraktCommentsSettings.enabled.map { "trakt_comments" },
            EpisodeReleaseNotificationsRepository.uiState.map { "episode_release_alerts" },
        )

        observeJob = scope.launch {
            combine(signatureFlows) { currentObservedStateSignature() }
                .distinctUntilChanged()
                .drop(1)
                .debounce(PUSH_DEBOUNCE_MS)
                .collect { signature ->
                    val authState = AuthRepository.state.value
                    if (authState !is AuthState.Authenticated || authState.isAnonymous) return@collect
                    if (isApplyingRemoteBlob || isServerSyncInFlight) return@collect
                    if (signature != currentObservedStateSignature()) return@collect
                    if (signature == skipNextPushSignature) {
                        skipNextPushSignature = null
                        return@collect
                    }
                    pushCurrentProfileToRemote()
                }
        }
    }

    private suspend fun pushToRemoteLocked(profileId: Int, blob: MobileProfileSettingsBlob) {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", MOBILE_SYNC_PLATFORM)
            put("p_settings_json", json.encodeToJsonElement(MobileProfileSettingsBlob.serializer(), blob))
            putSyncOriginClientId()
        }
        SupabaseProvider.client.postgrest.rpc("sync_push_profile_settings_blob", params)
        log.d { "pushToRemoteLocked(profileId=$profileId) — success" }
    }

    private fun exportSettingsBlob(): MobileProfileSettingsBlob {
        ensureRepositoriesLoaded()
        return MobileProfileSettingsBlob(
            features = MobileProfileSettingsFeatures(
                themeSettings = ThemeSettingsStorage.exportToSyncPayload(),
                posterCardStyleSettingsPayload = PosterCardStyleStorage.loadPayload().orEmpty().trim(),
                customPosterUrlPattern = CustomPosterUrlStorage.loadPattern().orEmpty().trim(),
                customPosterEnabledScreens = CustomPosterUrlStorage.loadEnabledScreens()
                    ?.joinToString(",").orEmpty(),
                cardDepthStyleSettingsPayload = CardDepthStyleStorage.loadPayload().orEmpty().trim(),
                playerSettings = withoutProfileCredentials(
                    PROFILE_PLAYER_SETTINGS_FEATURE,
                    PlayerSettingsStorage.exportToSyncPayload(),
                ),
                streamBadgeSettings = StreamBadgeSettingsStorage.exportToSyncPayload(),
                debridSettings = withoutProfileCredentials(
                    PROFILE_DEBRID_SETTINGS_FEATURE,
                    DebridSettingsStorage.exportToSyncPayload(),
                ),
                tmdbSettings = withoutProfileCredentials(
                    PROFILE_TMDB_SETTINGS_FEATURE,
                    TmdbSettingsStorage.exportToSyncPayload(),
                ),
                mdbListSettings = withoutProfileCredentials(
                    PROFILE_MDBLIST_SETTINGS_FEATURE,
                    MdbListSettingsStorage.exportToSyncPayload(),
                ),
                metaScreenSettingsPayload = MetaScreenSettingsStorage.loadPayload().orEmpty().trim(),
                collectionMobileSettingsPayload = CollectionMobileSettingsStorage.loadPayload().orEmpty().trim(),
                continueWatchingSettingsPayload = ContinueWatchingPreferencesStorage.loadPayload().orEmpty().trim(),
                traktSettingsPayload = TraktSettingsStorage.loadPayload().orEmpty().trim(),
                traktCommentsSettings = TraktCommentsStorage.exportToSyncPayload(),
                notificationsSettings = NotificationsSettingsPayload(
                    episodeReleaseAlertsEnabled = EpisodeReleaseNotificationsRepository.uiState.value.isEnabled,
                ),
            ),
        )
    }

    private fun applyRemoteBlob(blob: MobileProfileSettingsBlob) {
        ThemeSettingsStorage.replaceFromSyncPayload(blob.features.themeSettings)
        ThemeSettingsRepository.onProfileChanged()

        PosterCardStyleStorage.savePayload(blob.features.posterCardStyleSettingsPayload)
        PosterCardStyleRepository.onProfileChanged()

        CustomPosterUrlStorage.savePattern(blob.features.customPosterUrlPattern.ifBlank { null })
        val remoteScreenKeys = blob.features.customPosterEnabledScreens
            .takeIf { it.isNotBlank() }
            ?.split(",")
            ?.toSet()
        CustomPosterUrlStorage.saveEnabledScreens(remoteScreenKeys)
        CustomPosterUrlRepository.onProfileChanged()
        com.nuvio.app.features.home.HomeRepository.applyCurrentSettings()

        CardDepthStyleStorage.savePayload(blob.features.cardDepthStyleSettingsPayload)
        CardDepthStyleRepository.onProfileChanged()

        val localPlayerSettings = PlayerSettingsStorage.exportToSyncPayload()
        val localIntroDbApiKey = PlayerSettingsStorage.loadIntroDbApiKey()
        PlayerSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_PLAYER_SETTINGS_FEATURE,
                blob.features.playerSettings,
                localPlayerSettings,
            ),
        )
        localIntroDbApiKey?.let(PlayerSettingsStorage::saveIntroDbApiKey)
        PlayerSettingsRepository.onProfileChanged()

        StreamBadgeSettingsStorage.replaceFromSyncPayload(blob.features.streamBadgeSettings)
        StreamBadgeSettingsRepository.onProfileChanged()

        DebridSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_DEBRID_SETTINGS_FEATURE,
                blob.features.debridSettings,
                DebridSettingsStorage.exportToSyncPayload(),
            ),
        )
        DebridSettingsRepository.onProfileChanged()

        TmdbSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_TMDB_SETTINGS_FEATURE,
                blob.features.tmdbSettings,
                TmdbSettingsStorage.exportToSyncPayload(),
            ),
        )
        TmdbSettingsRepository.onProfileChanged()

        MdbListSettingsStorage.replaceFromSyncPayload(
            preservingLocalProfileCredentials(
                PROFILE_MDBLIST_SETTINGS_FEATURE,
                blob.features.mdbListSettings,
                MdbListSettingsStorage.exportToSyncPayload(),
            ),
        )
        MdbListMetadataService.clearCache()
        MdbListSettingsRepository.onProfileChanged()

        MetaScreenSettingsStorage.savePayload(blob.features.metaScreenSettingsPayload)
        MetaScreenSettingsRepository.onProfileChanged()

        CollectionMobileSettingsStorage.savePayload(blob.features.collectionMobileSettingsPayload)
        CollectionMobileSettingsRepository.onProfileChanged()

        ContinueWatchingPreferencesStorage.savePayload(blob.features.continueWatchingSettingsPayload)
        ContinueWatchingPreferencesRepository.onProfileChanged()

        TraktSettingsStorage.savePayload(blob.features.traktSettingsPayload)
        TrackingSettingsRepository.onProfileChanged()

        TraktCommentsStorage.replaceFromSyncPayload(blob.features.traktCommentsSettings)
        TraktCommentsSettings.onProfileChanged()

        EpisodeReleaseNotificationsRepository.applyFromSyncEnabled(blob.features.notificationsSettings.episodeReleaseAlertsEnabled)
    }

    private fun ensureRepositoriesLoaded() {
        ThemeSettingsRepository.ensureLoaded()
        PosterCardStyleRepository.ensureLoaded()
        CustomPosterUrlRepository.ensureLoaded()
        CardDepthStyleRepository.ensureLoaded()
        PlayerSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.ensureLoaded()
        DebridSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.ensureLoaded()
        MdbListSettingsRepository.ensureLoaded()
        MetaScreenSettingsRepository.ensureLoaded()
        CollectionMobileSettingsRepository.ensureLoaded()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        TrackingSettingsRepository.ensureLoaded()
        TraktCommentsSettings.ensureLoaded()
        EpisodeReleaseNotificationsRepository.ensureLoaded()
    }

    private fun buildSignature(blob: MobileProfileSettingsBlob): String =
        json.encodeToString(MobileProfileSettingsBlob.serializer(), blob)

    private fun currentObservedStateSignature(): String = buildSignature(exportSettingsBlob())

}

@Serializable
private data class MobileProfileSettingsBlob(
    val version: Int = 4,
    val features: MobileProfileSettingsFeatures = MobileProfileSettingsFeatures(),
)

@Serializable
private data class MobileProfileSettingsFeatures(
    @SerialName("theme_settings") val themeSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("poster_card_style_settings_payload") val posterCardStyleSettingsPayload: String = "",
    @SerialName("custom_poster_url_pattern") val customPosterUrlPattern: String = "",
    @SerialName("custom_poster_enabled_screens") val customPosterEnabledScreens: String = "",
    @SerialName("card_depth_style_settings_payload") val cardDepthStyleSettingsPayload: String = "",
    @SerialName("player_settings") val playerSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("stream_badge_settings") val streamBadgeSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("debrid_settings") val debridSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("tmdb_settings") val tmdbSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("mdblist_settings") val mdbListSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("meta_screen_settings_payload") val metaScreenSettingsPayload: String = "",
    @SerialName("collection_mobile_settings_payload") val collectionMobileSettingsPayload: String = "",
    @SerialName("continue_watching_settings_payload") val continueWatchingSettingsPayload: String = "",
    @SerialName("trakt_settings_payload") val traktSettingsPayload: String = "",
    @SerialName("trakt_comments_settings") val traktCommentsSettings: JsonObject = JsonObject(emptyMap()),
    @SerialName("notifications_settings") val notificationsSettings: NotificationsSettingsPayload = NotificationsSettingsPayload(),
)

@Serializable
private data class NotificationsSettingsPayload(
    @SerialName("episode_release_alerts_enabled") val episodeReleaseAlertsEnabled: Boolean = false,
)

@Serializable
private data class SettingsBlobResponse(
    @SerialName("profile_id") val profileId: Int = 0,
    @SerialName("settings_json") val settingsJson: JsonObject? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

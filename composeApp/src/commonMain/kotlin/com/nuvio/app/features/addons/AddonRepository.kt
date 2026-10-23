package com.nuvio.app.features.addons

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.sync.putSyncOriginClientId
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.rpc
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

@Serializable
private data class AddonRow(
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
internal data class AddonPushItem(
    val url: String,
    val name: String = "",
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

/**
 * Per-profile addons sync bookkeeping (audit M11): the server revision
 * the local list is based on, whether local changes are still waiting
 * for a successful push, and the last server-acknowledged list (the
 * baseline a dirty pull merges against). Persisted as JSON via
 * AddonStorage so a failed push stays known-dirty across restarts.
 */
@Serializable
private data class AddonSyncState(
    val baseRevision: Long? = null,
    val dirty: Boolean = false,
    val syncedItems: List<AddonPushItem> = emptyList(),
)

@Serializable
private data class AddonSyncStateRow(
    val revision: Long = 0,
)

private const val ADDON_PUSH_DEBOUNCE_MS = 500L

/** Marker in the server's exception when a push's base revision is stale. */
private const val ADDONS_REVISION_CONFLICT_MARKER = "addons_revision_conflict"

/** Bounded pull-merge-repush attempts after a revision conflict. */
private const val MAX_ADDON_CONFLICT_RETRIES = 2

object AddonRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AddonRepository")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _uiState = MutableStateFlow(AddonsUiState())
    val uiState: StateFlow<AddonsUiState> = _uiState.asStateFlow()

    private val _pendingInstallUrl = MutableStateFlow<String?>(null)

    /**
     * Manifest URL staged by an external deep link (stremio:// or nuvio:// addon
     * link). Staging never fetches, installs, persists, or syncs anything — the
     * Addons screen pre-fills its URL field with this value and the addon is only
     * installed when the user taps Install there.
     */
    val pendingInstallUrl: StateFlow<String?> = _pendingInstallUrl.asStateFlow()

    fun stagePendingInstallUrl(url: String) {
        val normalized = url.trim()
        if (normalized.isBlank()) return
        _pendingInstallUrl.value = normalized
    }

    fun consumePendingInstallUrl(): String? {
        val staged = _pendingInstallUrl.value
        _pendingInstallUrl.value = null
        return staged
    }

    private var initialized = false
    private var currentProfileId: Int = 1
    private val activeRefreshJobs = mutableMapOf<String, Job>()
    private val pushJobsByProfile = mutableMapOf<Int, Job>()
    private var seedJob: Job? = null
    private val syncStatesByProfile = mutableMapOf<Int, AddonSyncState>()
    private val pushGenerationsByProfile = mutableMapOf<Int, Int>()
    private val conflictRetriesByProfile = mutableMapOf<Int, Int>()

    /**
     * Serializes the addon sync bookkeeping above. The mutation path
     * (mark dirty + bump push generation) and the push acknowledgement
     * path (verify generation + overwrite sync state) must each run
     * atomically under this lock: if a mutation landed between an ack's
     * generation check and its state overwrite, the ack would clear the
     * dirty flag with a pre-mutation snapshot, and a later failure of
     * the mutation's own push would leave its change recorded as synced
     * (audit M11 race). Pattern per WatchProgressSourceCoordinator.
     */
    private val syncStateLock = SynchronizedObject()

    fun initialize() {
        val effectiveProfileId = resolveEffectiveProfileId(ProfileRepository.activeProfileId)
        if (initialized) return
        initialized = true
        currentProfileId = effectiveProfileId
        log.d { "initialize() — loading local addons for profile $currentProfileId" }
        ensurePreinstalledAddonsSeeded()

        val storedUrls = dedupeManifestUrls(AddonStorage.loadInstalledAddonUrls(currentProfileId))
        val enabledByUrl = loadLocalEnabledStates()
        log.d { "initialize() — local addon count: ${storedUrls.size}" }
        if (storedUrls.isEmpty()) return

        val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
        _uiState.value = AddonsUiState(
            addons = storedUrls.map { manifestUrl ->
                existingByUrl[manifestUrl].toPendingAddon(
                    manifestUrl = manifestUrl,
                    enabled = enabledByUrl[manifestUrl],
                )
            },
        )

        storedUrls.forEach { manifestUrl ->
            val existing = existingByUrl[manifestUrl]
            val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == manifestUrl }
            if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                refreshAddon(manifestUrl)
            }
        }
    }

    fun onProfileChanged(profileId: Int) {
        val effectiveProfileId = resolveEffectiveProfileId(profileId)
        if (effectiveProfileId == currentProfileId && initialized) return
        cancelActiveRefreshes()
        seedJob?.cancel()
        currentProfileId = effectiveProfileId
        initialized = false
        _pendingInstallUrl.value = null
        _uiState.value = AddonsUiState()
    }

    fun clearLocalState() {
        cancelActiveRefreshes()
        seedJob?.cancel()
        pushJobsByProfile.values.forEach(Job::cancel)
        pushJobsByProfile.clear()
        synchronized(syncStateLock) {
            syncStatesByProfile.clear()
            pushGenerationsByProfile.clear()
            conflictRetriesByProfile.clear()
        }
        currentProfileId = 1
        initialized = false
        _pendingInstallUrl.value = null
        _uiState.value = AddonsUiState()
    }

    suspend fun pullFromServer(profileId: Int) {
        currentProfileId = resolveEffectiveProfileId(profileId)
        log.i { "pullFromServer() — profileId=$profileId, initialized=$initialized" }
        runCatching {
            val rows = SupabaseProvider.client.postgrest
                .from("addons")
                .select {
                    filter { eq("profile_id", currentProfileId) }
                    order("sort_order", Order.ASCENDING)
                }
                .decodeList<AddonRow>()

            val rowsByUrl = linkedMapOf<String, AddonRow>()
            rows.forEach { row ->
                val manifestUrl = ensureManifestSuffix(row.url)
                if (!rowsByUrl.containsKey(manifestUrl)) {
                    rowsByUrl[manifestUrl] = row.copy(url = manifestUrl)
                }
            }

            val serverItems = rowsByUrl.values.map { row ->
                AddonPushItem(
                    url = row.url,
                    name = row.name ?: "",
                    enabled = row.enabled,
                    sortOrder = row.sortOrder,
                )
            }
            val serverRevision = fetchServerAddonsRevision(currentProfileId)
            val stateBeforePull = syncState(currentProfileId)
            val appliedItems = if (stateBeforePull.dirty) {
                // Local changes exist that the server has not acknowledged.
                // Merge them over the server list instead of letting this
                // pull silently restore the older server state (audit M11).
                mergeServerAddonsWithPendingLocal(
                    serverItems = serverItems,
                    localItems = currentPushItems(),
                    syncedItems = stateBeforePull.syncedItems,
                )
            } else {
                serverItems
            }

            val urls = appliedItems.map { it.url }
            log.i { "pullFromServer() — server returned ${rows.size} addons, applying ${urls.size}" }
            urls.forEachIndexed { i, u -> log.d { "  applied[$i]: $u" } }

            val existingByUrl = _uiState.value.addons.associateBy(ManagedAddon::manifestUrl)
            _uiState.value = AddonsUiState(
                addons = appliedItems.map { item ->
                    existingByUrl[item.url].toPendingAddon(
                        manifestUrl = item.url,
                        userSetName = item.name.takeIf { it.isNotBlank() },
                        enabled = item.enabled,
                    )
                },
            )
            persist()
            urls.forEach { url ->
                val existing = existingByUrl[url]
                val addon = _uiState.value.addons.firstOrNull { it.manifestUrl == url }
                if (addon?.enabled == true && (existing == null || (addon.manifest == null && !addon.isRefreshing))) {
                    refreshAddon(url)
                }
            }
            initialized = true
            log.i { "pullFromServer() — applied ${urls.size} addons to state" }

            // The pulled list is the acknowledged server state as of now.
            // Pending local changes stay dirty (only a successful push
            // clears that), but the base revision advances so the retry
            // below pushes against the revision it just merged with.
            updateSyncState(
                currentProfileId,
                stateBeforePull.copy(
                    baseRevision = serverRevision ?: stateBeforePull.baseRevision,
                    syncedItems = serverItems,
                ),
            )
            if (stateBeforePull.dirty) {
                // Retry the pending push — this is also how a push that
                // failed earlier (offline, server down) gets retried on
                // the next sync opportunity instead of being lost.
                pushToServer()
            }
        }.onFailure { e ->
            log.e(e) { "pullFromServer() — FAILED" }
        }
    }

    suspend fun awaitManifestsLoaded() {
        if (_uiState.value.addons.isEmpty()) return
        uiState.first { state ->
            state.addons.isEmpty() ||
                state.addons.any { it.manifest != null } ||
                state.addons.none { it.isRefreshing }
        }
    }

    /**
     * One-time migration: installs the default addons (Torrentio + Cinemeta) unless an
     * addon of the same family is already installed. Runs at most once per profile and
     * never re-adds an addon the user removed afterwards. Failures (e.g. offline on
     * first start) leave the flag unset so the next start retries.
     */
    private fun ensurePreinstalledAddonsSeeded() {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        val profileId = currentProfileId
        if (AddonStorage.loadPreinstalledAddonsSeeded(profileId)) return
        seedJob?.cancel()
        seedJob = scope.launch {
            val missing = missingPreinstalledAddonUrls(_uiState.value.addons.map(ManagedAddon::manifestUrl))
            for (url in missing) {
                if (currentProfileId != profileId) return@launch
                val result = addAddon(url)
                if (result is AddAddonResult.Error) {
                    // A concurrent install may have beaten us; only give up on real failures.
                    val stillMissing =
                        missingPreinstalledAddonUrls(_uiState.value.addons.map(ManagedAddon::manifestUrl))
                    if (url in stillMissing) {
                        log.w { "ensurePreinstalledAddonsSeeded() — failed for $url: ${result.message}" }
                        return@launch
                    }
                }
            }
            if (currentProfileId == profileId) {
                AddonStorage.savePreinstalledAddonsSeeded(profileId, true)
            }
        }
    }

    suspend fun addAddon(rawUrl: String): AddAddonResult {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) {
            return AddAddonResult.Error(getString(Res.string.profile_primary_addons_required))
        }
        log.i { "addAddon() — rawUrl=$rawUrl" }
        val manifestUrl = try {
            normalizeManifestUrl(rawUrl)
        } catch (error: IllegalArgumentException) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_invalid_url))
        }

        if (_uiState.value.addons.any { it.manifestUrl == manifestUrl }) {
            return AddAddonResult.Error(getString(Res.string.addon_already_installed))
        }

        val manifest = try {
            withContext(Dispatchers.Default) {
                val payload = fetchAddonResponseText(manifestUrl)
                AddonManifestParser.parse(
                    manifestUrl = manifestUrl,
                    payload = payload,
                )
            }
        } catch (error: Throwable) {
            return AddAddonResult.Error(error.message ?: getString(Res.string.addon_load_manifest_failed))
        }

        _uiState.update { current ->
            current.copy(
                addons = current.addons + ManagedAddon(
                    manifestUrl = manifestUrl,
                    manifest = manifest,
                    isRefreshing = false,
                    errorMessage = null,
                ),
            )
        }
        persist()
        pushToServer()
        return AddAddonResult.Success(manifest)
    }

    fun removeAddon(manifestUrl: String) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        log.i { "removeAddon() — $manifestUrl" }
        var changed = false
        _uiState.update { current ->
            val updatedAddons = current.addons.filterNot { it.manifestUrl == manifestUrl }
            changed = updatedAddons.size != current.addons.size
            if (changed) current.copy(addons = updatedAddons) else current
        }
        if (!changed) return
        persist()
        pushToServer()
    }

    fun moveAddon(fromIndex: Int, toIndex: Int) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var changed = false
        _uiState.update { current ->
            val addons = current.addons
            if (
                fromIndex !in addons.indices ||
                toIndex !in addons.indices ||
                fromIndex == toIndex
            ) {
                return@update current
            }

            val reordered = addons.toMutableList()
            val movingAddon = reordered.removeAt(fromIndex)
            reordered.add(toIndex, movingAddon)
            changed = true
            current.copy(addons = reordered)
        }
        if (!changed) return
        persist()
        pushToServer()
    }

    fun setAddonEnabled(manifestUrl: String, enabled: Boolean) {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        var shouldRefresh = false
        var changed = false
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl != manifestUrl || addon.enabled == enabled) {
                        addon
                    } else {
                        changed = true
                        shouldRefresh = enabled && addon.manifest == null && !addon.isRefreshing
                        addon.copy(enabled = enabled)
                    }
                },
            )
        }
        if (!changed) return
        persist()
        pushToServer()
        if (shouldRefresh) {
            refreshAddon(manifestUrl)
        }
    }

    fun refreshAll() {
        _uiState.value.addons.filter { it.enabled }.distinctBy { it.manifestUrl }.forEach { addon ->
            refreshAddon(
                manifestUrl = addon.manifestUrl,
                forceRefresh = true,
            )
        }
    }

    fun refreshAddon(
        manifestUrl: String,
        forceRefresh: Boolean = false,
    ) {
        val existingJob = activeRefreshJobs[manifestUrl]
        if (existingJob?.isActive == true) return

        markRefreshing(manifestUrl)
        var refreshJob: Job? = null
        refreshJob = scope.launch {
            try {
                val result = runCatching {
                    val payload = fetchAddonResponseText(
                        url = manifestUrl,
                        forceRefresh = forceRefresh,
                    )
                    AddonManifestParser.parse(
                        manifestUrl = manifestUrl,
                        payload = payload,
                    )
                }

                _uiState.update { current ->
                    current.copy(
                        addons = current.addons.map { addon ->
                            if (addon.manifestUrl != manifestUrl) {
                                addon
                            } else {
                                result.fold(
                                    onSuccess = { manifest ->
                                        addon.copy(
                                            manifest = manifest,
                                            isRefreshing = false,
                                            errorMessage = null,
                                        )
                                    },
                                    onFailure = { error ->
                                        addon.copy(
                                            isRefreshing = false,
                                            errorMessage = error.message ?: getString(Res.string.addon_load_manifest_failed),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            } finally {
                if (activeRefreshJobs[manifestUrl] === refreshJob) {
                    activeRefreshJobs.remove(manifestUrl)
                }
            }
        }
        activeRefreshJobs[manifestUrl] = refreshJob
    }

    private fun currentPushItems(): List<AddonPushItem> =
        _uiState.value.addons
            .distinctBy { it.manifestUrl }
            .mapIndexed { index, addon ->
                AddonPushItem(
                    url = addon.manifestUrl,
                    name = addon.userSetName?.takeIf { it.isNotBlank() } ?: addon.manifest?.name ?: "",
                    enabled = addon.enabled,
                    sortOrder = index,
                )
            }

    private fun syncState(profileId: Int): AddonSyncState =
        syncStatesByProfile.getOrPut(profileId) {
            AddonStorage.loadAddonSyncState(profileId)
                ?.let { payload ->
                    runCatching { json.decodeFromString<AddonSyncState>(payload) }.getOrNull()
                }
                ?: AddonSyncState()
        }

    private fun updateSyncState(profileId: Int, state: AddonSyncState) {
        syncStatesByProfile[profileId] = state
        AddonStorage.saveAddonSyncState(
            profileId,
            runCatching { json.encodeToString(state) }.getOrNull(),
        )
    }

    /**
     * The server's current addons revision for [profileId], or null when
     * it can't be read (a server predating migration 07 has no
     * addon_sync_state table — pushes then run without a base revision,
     * i.e. the legacy unguarded behaviour, until the server is updated).
     */
    private suspend fun fetchServerAddonsRevision(profileId: Int): Long? =
        runCatching {
            SupabaseProvider.client.postgrest
                .from("addon_sync_state")
                .select {
                    filter { eq("profile_id", profileId) }
                }
                .decodeList<AddonSyncStateRow>()
                .firstOrNull()
                ?.revision
                ?: 0L
        }.getOrNull()

    private fun pushToServer() {
        if (isUsingPrimaryAddonsFromSecondaryProfile()) return
        val profileId = currentProfileId
        val addons = currentPushItems()
        // Every local mutation funnels through here: from now on the list
        // is dirty (persisted) until the server acknowledges a push, so a
        // failed push is never silently overwritten by a later pull.
        // The dirty-mark and the generation bump run under syncStateLock,
        // atomically against the push acknowledgement further below: this
        // mutation either bumps the generation before an in-flight ack
        // checks it (that ack is then skipped), or it runs after the
        // ack's overwrite and marks the state dirty again — never in
        // between the ack's check and its overwrite.
        val generation = synchronized(syncStateLock) {
            val stateBeforePush = syncState(profileId)
            if (!stateBeforePush.dirty) {
                updateSyncState(profileId, stateBeforePush.copy(dirty = true))
            }
            val nextGeneration = (pushGenerationsByProfile[profileId] ?: 0) + 1
            pushGenerationsByProfile[profileId] = nextGeneration
            nextGeneration
        }
        pushJobsByProfile[profileId]?.cancel()
        var pushJob: Job? = null
        pushJob = scope.launch {
            try {
                delay(ADDON_PUSH_DEBOUNCE_MS)
                log.d { "pushToServer() — profileId=$profileId, pushing ${addons.size} addons" }
                val baseRevision = syncState(profileId).baseRevision
                val params = buildJsonObject {
                    put("p_profile_id", profileId)
                    put("p_addons", json.encodeToJsonElement(addons))
                    if (baseRevision != null) {
                        put("p_base_revision", baseRevision)
                    }
                    putSyncOriginClientId()
                }
                val result = SupabaseProvider.client.postgrest.rpc("sync_push_addons", params)
                // Servers predating the revision guard return no body; the
                // push still counts as acknowledged, the revision just
                // stays unknown until the next pull reads it.
                val newRevision = runCatching { result.decodeAs<Long>() }.getOrNull()
                // Generation check and state overwrite under the same
                // lock as the mutation path in pushToServer(): no
                // mutation can slip in between them and have its dirty
                // flag cleared by this ack's pre-mutation snapshot.
                synchronized(syncStateLock) {
                    if (pushGenerationsByProfile[profileId] == generation) {
                        conflictRetriesByProfile.remove(profileId)
                        val latest = syncState(profileId)
                        updateSyncState(
                            profileId,
                            latest.copy(
                                baseRevision = newRevision ?: latest.baseRevision,
                                dirty = false,
                                syncedItems = addons,
                            ),
                        )
                    }
                }
                log.d { "pushToServer() — success" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.e(error) { "pushToServer() — FAILED" }
                val generationIsCurrent = synchronized(syncStateLock) {
                    pushGenerationsByProfile[profileId] == generation
                }
                if (isAddonsRevisionConflict(error) && generationIsCurrent) {
                    // Another device pushed first and the server rejected
                    // our stale base revision. Pull — which merges the
                    // pending local changes over the newer server list —
                    // and let the pull re-push against the fresh revision.
                    // Bounded: if conflicts keep recurring we stay dirty
                    // and retry on the next regular sync opportunity
                    // instead of spinning.
                    val retries = (conflictRetriesByProfile[profileId] ?: 0) + 1
                    conflictRetriesByProfile[profileId] = retries
                    if (retries <= MAX_ADDON_CONFLICT_RETRIES) {
                        pullFromServer(profileId)
                    }
                }
            } finally {
                if (pushJobsByProfile[profileId] === pushJob) {
                    pushJobsByProfile.remove(profileId)
                }
            }
        }
        pushJobsByProfile[profileId] = pushJob
    }

    private fun markRefreshing(manifestUrl: String) {
        _uiState.update { current ->
            current.copy(
                addons = current.addons.map { addon ->
                    if (addon.manifestUrl == manifestUrl) {
                        addon.copy(
                            isRefreshing = true,
                            errorMessage = null,
                        )
                    } else {
                        addon
                    }
                },
            )
        }
    }

    private fun persist() {
        val addons = _uiState.value.addons
        AddonStorage.saveInstalledAddonUrls(
            currentProfileId,
            dedupeManifestUrls(addons.map { it.manifestUrl }),
        )
        AddonStorage.saveAddonEnabledStates(
            currentProfileId,
            addons.associate { it.manifestUrl to it.enabled },
        )
    }

    private fun loadLocalEnabledStates(): Map<String, Boolean> =
        AddonStorage.loadAddonEnabledStates(currentProfileId)
            .mapKeys { (url, _) -> ensureManifestSuffix(url) }

    private fun cancelActiveRefreshes() {
        activeRefreshJobs.values.forEach(Job::cancel)
        activeRefreshJobs.clear()
    }

    private fun resolveEffectiveProfileId(profileId: Int): Int {
        val active = ProfileRepository.state.value.activeProfile
        return if (active != null && active.profileIndex != 1 && active.usesPrimaryAddons) 1 else profileId
    }

    private fun isUsingPrimaryAddonsFromSecondaryProfile(): Boolean {
        val active = ProfileRepository.state.value.activeProfile
        return active != null && active.profileIndex != 1 && active.usesPrimaryAddons
    }
}

private fun ManagedAddon?.toPendingAddon(
    manifestUrl: String,
    userSetName: String? = null,
    enabled: Boolean? = null,
): ManagedAddon =
    when {
        this == null -> ManagedAddon(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: true,
            userSetName = userSetName,
            enabled = enabled ?: true,
        )
        manifest != null -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = false,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        isRefreshing -> copy(
            manifestUrl = manifestUrl,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
        else -> copy(
            manifestUrl = manifestUrl,
            isRefreshing = enabled ?: this.enabled,
            errorMessage = null,
            userSetName = userSetName ?: this.userSetName,
            enabled = enabled ?: this.enabled,
        )
    }

private fun dedupeManifestUrls(urls: List<String>): List<String> =
    urls.map(::ensureManifestSuffix).distinct()

/**
 * Merges the server's addon list with unacknowledged local changes
 * (audit M11). [syncedItems] is the last server-acknowledged list:
 *  - local items come first, in local order — pending adds, field edits
 *    and reorders win for the items they touch;
 *  - a local item identical to its acknowledged version but gone on the
 *    server was removed by another device — follow the server and drop
 *    it instead of resurrecting it with the pending push;
 *  - server items that were acknowledged before and are absent locally
 *    were removed locally — the pending removal wins, drop them;
 *  - server items never acknowledged locally and absent locally were
 *    added by another device — keep them, after the local items.
 */
internal fun mergeServerAddonsWithPendingLocal(
    serverItems: List<AddonPushItem>,
    localItems: List<AddonPushItem>,
    syncedItems: List<AddonPushItem>,
): List<AddonPushItem> {
    val syncedByUrl = syncedItems.associateBy { it.url }
    val serverByUrl = serverItems.associateBy { it.url }
    val merged = mutableListOf<AddonPushItem>()
    val seen = mutableSetOf<String>()
    for (item in localItems) {
        if (!seen.add(item.url)) continue
        val synced = syncedByUrl[item.url]
        val untouchedLocally = synced != null &&
            synced.name == item.name &&
            synced.enabled == item.enabled
        if (untouchedLocally && item.url !in serverByUrl) {
            continue
        }
        merged += if (item.name.isBlank()) {
            // A not-yet-loaded manifest derives an empty name locally;
            // don't let it erase the name the server already has.
            item.copy(name = serverByUrl[item.url]?.name ?: synced?.name ?: item.name)
        } else {
            item
        }
    }
    for (item in serverItems) {
        if (!seen.add(item.url)) continue
        if (item.url in syncedByUrl) {
            continue
        }
        merged += item
    }
    return merged.mapIndexed { index, item -> item.copy(sortOrder = index) }
}

private fun isAddonsRevisionConflict(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current.message?.contains(ADDONS_REVISION_CONFLICT_MARKER) == true) return true
        current = current.cause
    }
    return false
}

private fun ensureManifestSuffix(url: String): String {
    val path = url.substringBefore("?").trimEnd('/')
    val query = url.substringAfter("?", "")
    val withSuffix = if (path.endsWith("/manifest.json")) path else "$path/manifest.json"
    return if (query.isEmpty()) withSuffix else "$withSuffix?$query"
}

private fun normalizeManifestUrl(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    require(trimmed.isNotEmpty()) { runBlocking { getString(Res.string.addons_error_enter_url) } }

    val normalizedScheme = when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.startsWith("stremio://") -> "https://${trimmed.removePrefix("stremio://")}"
        else -> "https://$trimmed"
    }

    val withoutFragment = normalizedScheme.substringBefore("#")
    val query = withoutFragment.substringAfter("?", "")
    val path = withoutFragment.substringBefore("?").trimEnd('/')
    val manifestPath = if (path.endsWith("/manifest.json")) {
        path
    } else {
        "$path/manifest.json"
    }

    return if (query.isEmpty()) manifestPath else "$manifestPath?$query"
}

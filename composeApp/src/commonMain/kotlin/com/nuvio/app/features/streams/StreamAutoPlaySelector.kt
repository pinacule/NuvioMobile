package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy

object StreamAutoPlaySelector {

    fun orderAddonStreams(
        groups: List<AddonStreamGroup>,
        installedOrder: List<String>,
        preferredGroupIds: Set<String> = emptySet(),
    ): List<AddonStreamGroup> {
        if (groups.isEmpty()) return groups
        if (preferredGroupIds.isNotEmpty()) {
            val (preferred, rest) = groups.partition { it.addonId in preferredGroupIds }
            return preferred + orderAddonStreams(rest, installedOrder)
        }

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = groups.partition { group ->
            group.addonId.startsWith("debrid:") ||
                group.streams.any { stream -> stream.isAddonDebridCandidate && stream.isDirectDebridStream }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries

        val (addonEntries, pluginEntries) = remainingEntries.partition { group ->
            group.addonName in addonRankByName
        }
        val orderedAddons = addonEntries.sortedBy { group ->
            addonRankByName.getValue(group.addonName)
        }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    fun selectAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
        preferEnglishAudio: Boolean = false,
        requireHdrSeeders: Boolean = false,
    ): StreamItem? =
        evaluateAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = regexPattern,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferBingeGroupInSelection,
            bingeGroupOnly = bingeGroupOnly,
            debridEnabled = debridEnabled,
            activeResolverProviderId = activeResolverProviderId,
            preferEnglishAudio = preferEnglishAudio,
            requireHdrSeeders = requireHdrSeeders,
        ).stream

    fun evaluateAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
        preferEnglishAudio: Boolean = false,
        requireHdrSeeders: Boolean = false,
    ): StreamAutoPlayEvaluation {
        if (streams.isEmpty()) return StreamAutoPlayEvaluation()

        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.serverTarget != null || it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (stream.serverTarget != null) {
                true
            } else if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return StreamAutoPlayEvaluation()
        if (mode == StreamAutoPlayMode.MANUAL && !bingeGroupOnly) {
            return StreamAutoPlayEvaluation()
        }

        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        val bingeGroupCandidates = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            candidateStreams.filter { stream -> stream.behaviorHints.bingeGroup == targetBingeGroup }
        } else {
            emptyList()
        }
        // Dub over sub, always: within the binge group, English-audio releases
        // rank first (stable sort, so the group's own order is otherwise kept).
        val rankedBingeGroupCandidates = if (preferEnglishAudio) {
            bingeGroupCandidates.sortedWith(
                compareByDescending { AdaptiveStreamPicker.hasEnglishAudio(it) },
            )
        } else {
            bingeGroupCandidates
        }
        val preferredReadyStream = rankedBingeGroupCandidates.firstOrNull { stream ->
            stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
        }
        if (bingeGroupOnly) {
            val readyStreams = preferredReadyStream?.let(::listOf).orEmpty()
            return StreamAutoPlayEvaluation(
                stream = preferredReadyStream,
                readyStreams = readyStreams,
                hasPendingDebridCandidate = preferredReadyStream == null &&
                    bingeGroupCandidates.any {
                        it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
                    },
            )
        }
        if (mode == StreamAutoPlayMode.MANUAL) {
            return StreamAutoPlayEvaluation()
        }
        val preferredStream = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            candidateStreams.firstOrNull { stream ->
                stream.behaviorHints.bingeGroup == targetBingeGroup &&
                    stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
            }
        } else {
            null
        }
        val matchingStreams = when (mode) {
            StreamAutoPlayMode.MANUAL -> emptyList()
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()

                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return StreamAutoPlayEvaluation()

                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex(
                        "\\b(${exclusionWords.joinToString("|") { Regex.escape(it) }})\\b",
                        RegexOption.IGNORE_CASE,
                    )
                } else null

                candidateStreams.filter { stream ->
                    val url = stream.playableDirectUrl.orEmpty()

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.streamLabel).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(url)
                    }

                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }
            }
        }
        if (matchingStreams.isEmpty() && preferredStream == null) return StreamAutoPlayEvaluation()

        val readyStreams = buildList {
            preferredStream?.let(::add)
            matchingStreams
                .filter { it.isAutoPlayable(debridEnabled, activeResolverProviderId) }
                .filterNot { it == preferredStream }
                .forEach(::add)
        }
        // Dub over sub, always: English-audio releases outrank everything else
        // and are exempt from the HDR seeder gate (mirrors
        // AdaptiveStreamPicker.pickBestSeeded). HDR needs enough seeders for
        // smooth playback; the gate applies to the non-English pool and falls
        // back to the ungated pool when nothing qualifies so we never pick
        // nothing. Both partitions keep their existing order (stable). The
        // ordinary seeder floor gate composes the same way: English releases
        // are exempt (dub preference outranks seeder requirements), the rest
        // of the pool is floored with the same never-pick-nothing fallback.
        val rankedReadyStreams = if (preferEnglishAudio) {
            val (englishDubStreams, otherStreams) = readyStreams.partition {
                AdaptiveStreamPicker.hasEnglishAudio(it)
            }
            if (englishDubStreams.isEmpty()) {
                applySeederFloorGate(applyHdrSeederGate(otherStreams, requireHdrSeeders))
            } else {
                englishDubStreams +
                    applySeederFloorGate(applyHdrSeederGate(otherStreams, requireHdrSeeders))
            }
        } else {
            applySeederFloorGate(applyHdrSeederGate(readyStreams, requireHdrSeeders))
        }
        val selected = rankedReadyStreams.firstOrNull()
        if (selected != null) {
            return StreamAutoPlayEvaluation(
                stream = selected,
                readyStreams = rankedReadyStreams,
            )
        }

        return StreamAutoPlayEvaluation(
            readyStreams = rankedReadyStreams,
            hasPendingDebridCandidate = matchingStreams.any {
                it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            },
        )
    }

    /**
     * HDR streams need at least [AdaptiveStreamPicker.MIN_SEEDERS_FOR_HDR_AUTOPLAY]
     * known seeders to be eligible; if the gate leaves zero candidates it falls
     * back to the ungated list so we never pick nothing when streams exist.
     */
    private fun applyHdrSeederGate(
        streams: List<StreamItem>,
        requireHdrSeeders: Boolean,
    ): List<StreamItem> {
        if (!requireHdrSeeders) return streams
        return streams.filter { stream ->
            !AdaptiveStreamPicker.isHdrStream(stream) ||
                (AdaptiveStreamPicker.parseSeeders(stream) ?: -1) >=
                AdaptiveStreamPicker.MIN_SEEDERS_FOR_HDR_AUTOPLAY
        }.ifEmpty { streams }
    }

    /**
     * Ordinary seeder floor: when at least one candidate counts as healthy
     * (known seeders at/above [AdaptiveStreamPicker.MIN_SEEDERS_FOR_AUTOPLAY],
     * or an unknown count — neutral, no evidence of weakness), candidates
     * KNOWN to sit below the floor drop out, so a nearly dead stream listed
     * first by an addon no longer beats a healthy one further down. If every
     * candidate is known-below-floor the list is returned unchanged so we
     * never pick nothing (mirrors [applyHdrSeederGate]'s fallback). Relative
     * order is preserved (stable filter).
     */
    private fun applySeederFloorGate(streams: List<StreamItem>): List<StreamItem> {
        if (streams.isEmpty()) return streams
        return streams.filter { stream ->
            val seeders = AdaptiveStreamPicker.parseSeeders(stream)
            seeders == null || seeders >= AdaptiveStreamPicker.MIN_SEEDERS_FOR_AUTOPLAY
        }.ifEmpty { streams }
    }

    private fun StreamItem.isAutoPlayable(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean =
        playableDirectUrl != null ||
            serverTarget != null ||
            (
                AppFeaturePolicy.p2pEnabled &&
                    needsLocalDebridResolve &&
                    p2pInfoHash != null &&
                    !isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            ) ||
            (debridEnabled && isAddonDebridCandidate && isReadyDebridAutoPlay(activeResolverProviderId))

    private fun StreamItem.isReadyDebridAutoPlay(activeResolverProviderId: String?): Boolean =
        when {
            isDirectDebridStream -> clientResolve?.service.matchesResolver(activeResolverProviderId)
            isCachedDebridTorrentStream -> debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)
            else -> false
        }

    private fun StreamItem.isPendingDebridAutoPlay(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean {
        if (!debridEnabled || !isInstalledAddonStream || !needsLocalDebridResolve) return false
        if (!debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)) return false
        val state = debridCacheStatus?.state
        return state == null || state == StreamDebridCacheState.CHECKING
    }

    private fun String?.matchesResolver(activeResolverProviderId: String?): Boolean {
        val active = activeResolverProviderId?.trim().orEmpty()
        return active.isBlank() || this == null || equals(active, ignoreCase = true)
    }
}

data class StreamAutoPlayEvaluation(
    val stream: StreamItem? = null,
    val readyStreams: List<StreamItem> = emptyList(),
    val hasPendingDebridCandidate: Boolean = false,
)

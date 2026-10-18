package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.QueueTier
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.QueueCoordinator.asQueueEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Most AutoPlay-suggested tracks kept queued ahead of the current one at once. */
const val MAX_QUEUED_AUTOPLAY = 10

/**
 * Whether AutoPlay is on for the music this device is playing.
 *
 * In a party it is the party's setting and not this phone's: AutoPlay is shared
 * there, so one member's preference cannot be what decides. [localPreference] is
 * `AppSettings.autoplay` and applies everywhere else.
 *
 * Shared by the playback service and by every surface that draws the toggle,
 * because they went their own ways once and the result was a button that lied:
 * the player read the local preference while the service obeyed the party's, so
 * a listener could sit under "AutoPlay on" getting no suggestions at all, press
 * it, and watch the label stay exactly as it was.
 */
fun autoplayEnabledFor(party: ListenTogether.State, localPreference: Boolean): Boolean =
    if (party.inParty) party.playback.autoplayEnabled else localPreference

/**
 * The one device in a party that tops the queue up, or null if there is none.
 *
 * Everybody runs this over the same membership and gets the same answer, which
 * is what stops five phones appending five different sets of suggestions to one
 * queue. The host supplies while it is connected; otherwise the lowest member id
 * takes over, chosen for being stable rather than for meaning anything.
 *
 * Except in a party locked to its host, where there is no stand-in. The server
 * refuses a `queueAdd` from a listener outright — `host_only`, whatever the
 * listener believes it is doing — so electing one produced a control that was
 * always refused, an error on that listener's phone and suggestions for nobody.
 * Returning null instead lets AutoPlay wait for the host, which is what the
 * party's own policy already says should happen.
 */
fun autoplaySupplierId(party: ListenTogether.State): String? {
    if (!party.inParty) return null
    val host = party.members.firstOrNull { it.isHost && it.connected }?.memberId
    if (party.hostOnlyControl) return host
    return host ?: party.members.asSequence()
        .filter { it.connected }
        .minByOrNull { it.memberId }
        ?.memberId
}

/**
 * Whether AutoPlay should forget its last seed and try the current track again.
 *
 * A completed load normally leaves its seed behind to de-duplicate the several
 * player callbacks caused by one queue edit. If that edit leaves the current
 * track with nothing after it, however, the seed is no longer useful: keeping
 * it would make an enabled AutoPlay queue stay empty forever.
 */
fun autoplayQueueNeedsRefresh(
    enabled: Boolean,
    repeatAll: Boolean,
    currentIndex: Int,
    itemCount: Int,
    loadInProgress: Boolean,
): Boolean = enabled &&
    !repeatAll &&
    !loadInProgress &&
    currentIndex >= 0 &&
    currentIndex == itemCount - 1

/**
 * Finds the YouTube id that should seed AutoPlay for a song. Module tracks do not
 * carry YouTube ids, so they are matched on YouTube before the radio request.
 */
suspend fun youtubeSeedFor(song: Song): String? {
    if (SourceRegistry.parseTrackKey(song.videoId) == null) return song.videoId
    val target = TrackMatcher.targetOf(song)
    val query = TrackMatcher.queries(target).firstOrNull() ?: return null
    return YtMusicRepository.search(query, SearchFilter.SONGS)
        .getOrNull()
        ?.filterIsInstance<SearchResult.Track>()
        ?.map { it.song }
        ?.let { TrackMatcher.best(it, target) }
        ?.videoId
}

/**
 * Loads, de-duplicates and resolves one AutoPlay batch. The playback service is
 * the only caller for the AutoPlay toggle; the player UI's explicit radio start
 * uses this same helper for its initial station batch.
 */
suspend fun loadAutoplayTracks(
    existing: List<Song>,
    seedSong: Song,
    limit: Int = MAX_QUEUED_AUTOPLAY,
    avoidAlternateVersions: Boolean = false,
    tasteSeeds: List<Song> = emptyList(),
    styleOf: (Song) -> Set<String> = { emptySet() },
    exploreFromTaste: Boolean = false,
    adaptiveExploration: Boolean = false,
    longTermSeeds: List<Song> = emptyList(),
    avoidArtists: Set<String> = emptySet(),
    sourceOffset: Int = 0,
    excludeVideoIds: Set<String> = emptySet(),
): Result<List<Song>> {
    val recentSeeds = tasteSeeds.asReversed()
        .filter { it.videoId != seedSong.videoId }
        .distinctBy { it.videoId }
        .take(MAX_TASTE_SEEDS - 1)
    // Every few refills, let an older song the listener actually stayed with
    // lead the candidate pool. The current song remains included, so this is
    // exploration rather than abandoning relevance or hard-coding genres.
    val recentStyleSets = (listOf(seedSong) + recentSeeds)
        .map(styleOf)
        .filter { it.isNotEmpty() }
    val sharedRecentStyles = recentStyleSets
        .drop(1)
        .fold(recentStyleSets.firstOrNull().orEmpty()) { shared, styles -> shared intersect styles }
    val persistentCandidates = longTermSeeds
        .filter { it.videoId != seedSong.videoId }
        .filterNot { candidate -> recentSeeds.any { it.videoId == candidate.videoId } }
    // When recent seeds collapse into one known lane, use an older seed outside
    // that lane. This is based only on the listener's learned facts; it never
    // names or blocks an artist or genre.
    val persistentSeed = persistentCandidates.firstOrNull { candidate ->
        sharedRecentStyles.isNotEmpty() &&
            styleOf(candidate).none { it in sharedRecentStyles }
    } ?: persistentCandidates.firstOrNull()
    val seeds = if (exploreFromTaste && persistentSeed != null) {
        listOf(persistentSeed, seedSong) + recentSeeds.take(MAX_TASTE_SEEDS - 2)
    } else if (exploreFromTaste && recentSeeds.isNotEmpty()) {
        listOf(recentSeeds.last(), seedSong) + recentSeeds.dropLast(1)
    } else {
        listOf(seedSong) + recentSeeds
    }
    val resolvedSeeds = seeds.map { it to youtubeSeedFor(it) }
        .filter { it.second != null }
    if (resolvedSeeds.isEmpty()) {
        AutoplayDiagnostics.recordFailure("no recommendation seeds could be resolved")
        return Result.success(emptyList())
    }
    val responses = coroutineScope {
        resolvedSeeds.map { (_, seed) ->
            async { YtMusicRepository.radio(seed!!) }
        }.awaitAll()
    }
    val failure = responses.firstNotNullOfOrNull { it.exceptionOrNull() }
    if (responses.none { it.isSuccess }) {
        AutoplayDiagnostics.recordFailure("all recommendation requests failed")
        return Result.failure(failure ?: Exception("AutoPlay radio unavailable"))
    }
    val pools = responses.map { it.getOrDefault(emptyList()) }
    val related = pools.flatten()
    val extra = if (pools.size <= 1) {
        QueueBuilder.extend(
            existing = existing,
            candidates = related.filterNot { it.videoId in excludeVideoIds },
            limit = limit,
            avoidAlternateVersions = avoidAlternateVersions,
            styleOf = styleOf,
            avoidArtists = avoidArtists,
        )
    } else {
        // Do not merge all seed responses before ranking. A single dominant
        // recommendation lane can otherwise win every refill. Taking one
        // candidate per source in rotation preserves relevance while forcing
        // the current, recent, and older personal-taste lanes to share space.
        val sourceOrder = if (exploreFromTaste && longTermSeeds.isNotEmpty()) {
            // Keep one personal-history anchor in every explored refill. Rotate
            // the remaining current/recent pools, rather than rotating the
            // anchor away and letting the current chain take over again.
            val rotating = pools.indices.drop(1)
            listOf(0) + rotating.drop(sourceOffset.mod(rotating.size.coerceAtLeast(1))) +
                rotating.take(sourceOffset.mod(rotating.size.coerceAtLeast(1)))
        } else {
            pools.indices
                .drop(sourceOffset.mod(pools.size)) + pools.indices.take(sourceOffset.mod(pools.size))
        }
        val chosen = mutableListOf<Song>()
        var round = 0
        while (chosen.size < limit && round < pools.size * limit) {
            val source = sourceOrder[round % sourceOrder.size]
            val pick = QueueBuilder.extend(
                existing = existing + chosen,
                candidates = pools[source].filterNot { it.videoId in excludeVideoIds },
                limit = 1,
                avoidAlternateVersions = avoidAlternateVersions,
                styleOf = styleOf,
                avoidArtists = avoidArtists,
            ).firstOrNull()
            if (pick != null && chosen.none { QueueBuilder.isSameRecording(it, pick) }) {
                chosen += pick
            }
            round++
        }
        chosen
    }
    AutoplayDiagnostics.recordBatch(
        seed = seedSong,
        tasteSeeds = seeds.drop(1),
        candidateCount = related.size,
        selected = extra,
        existingCount = existing.size,
        adaptiveExploration = adaptiveExploration || (exploreFromTaste && persistentSeed != null &&
            sharedRecentStyles.isNotEmpty()),
        sourceAwareSelection = pools.size > 1,
        longTermAnchor = exploreFromTaste && longTermSeeds.isNotEmpty(),
    )
    if (extra.isEmpty()) return Result.success(emptyList())

    return Result.success(extra.map {
        it.copy(
            radioName = seedSong.radioName,
            playbackSource = seedSong.playbackSource,
            playbackSourceType = seedSong.playbackSourceType,
            playbackSourceId = seedSong.playbackSourceId,
        ).asQueueEntry(QueueTier.AUTOPLAY)
    })
}

/** More than one seed prevents a single autoplay chain from defining the whole queue. */
private const val MAX_TASTE_SEEDS = 4

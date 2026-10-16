package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import java.util.Locale

/**
 * Builds the station that plays on after a one-off song.
 *
 * Shared: Android and the desktop assemble the same station from the same
 * watch queue, so the de-duplication and the per-artist caps are one
 * implementation rather than two that drift.
 *
 * Two problems this exists to solve:
 *
 *  - YouTube's watch queues routinely carry the same recording twice — the
 *    official audio and the music video are separate videoIds with near
 *    identical titles — so de-duping on id alone lets a track play, then play
 *    again as its video. Songs are matched on a normalised title plus lead
 *    artist instead.
 *
 *  - A radio mix leans hard on the seed's own artist. Capping how many tracks
 *    any one artist contributes keeps a station from turning into a single
 *    artist's discography.
 */
object QueueBuilder {

    /** No more than this many tracks by one artist in a single batch. */
    const val PER_ARTIST_LIMIT = 2

    /** The artist the station was seeded on gets more room, but not the run of it. */
    const val SEED_ARTIST_LIMIT = 4

    /**
     * The subset of [candidates] worth appending after [existing]: nothing
     * already queued, nothing repeated, at most [limit] tracks.
     */
    fun extend(
        existing: List<Song>,
        candidates: List<Song>,
        limit: Int,
        avoidAlternateVersions: Boolean = false,
        styleOf: (Song) -> Set<String> = { emptySet() },
    ): List<Song> {
        val taken = existing.toMutableList()
        val perArtist = mutableMapOf<String, Int>()
        val seedArtists = existing.lastOrNull()?.artist?.let(::artistSet).orEmpty()
        val out = mutableListOf<Song>()
        val recentArtists = existing
            .takeLast(RECENT_ARTIST_WINDOW)
            .flatMap { artistSet(it.artist) }
            .toSet()
        val recentStyles = existing
            .takeLast(RECENT_STYLE_WINDOW)
            .flatMap(styleOf)
            .toSet()

        // A mix pairs all but every track with its own music-video upload —
        // the same recording under a different id, titled far enough apart
        // ("Dildaara (Stand By Me)" against "Lyrical Video: Dildara Song")
        // that no title match will catch it. The catalogue cut is the one a
        // music app wants; videos only stand in when there is nothing else.
        val songsOnly = candidates.filterNot { it.isVideo }
        // The normal release gets first pick over a remix when both are in the
        // watch queue. Among otherwise equal candidates, prefer an artist that
        // has not appeared recently and an artist not already used in this
        // batch. This is a soft cooldown: if the radio only offers one artist,
        // the candidate is still allowed through, subject to the existing cap.
        val remaining = (songsOnly.ifEmpty { candidates }).toMutableList()

        while (remaining.isNotEmpty() && out.size < limit) {
            val candidate = remaining.minWithOrNull(
                compareBy<Song> { song ->
                    styleDistance(song, recentStyles, styleOf)
                }.thenBy { song ->
                    if (artistSet(song.artist).any { it in recentArtists }) 1 else 0
                }.thenBy { song ->
                    artistSet(song.artist).maxOfOrNull { perArtist[it] ?: 0 } ?: 0
                }.thenBy { isRemix(it.title) },
            ) ?: break
            remaining.remove(candidate)
            if (taken.any {
                    isSameRecording(it, candidate) ||
                        (avoidAlternateVersions && isSameSongAcrossMixVersions(it, candidate))
                }
            ) continue

            val artists = artistSet(candidate.artist)
            val key = artists.minOrNull()
            if (key != null) {
                val cap = if (artists.any { it in seedArtists }) {
                    SEED_ARTIST_LIMIT
                } else {
                    PER_ARTIST_LIMIT
                }
                val played = perArtist[key] ?: 0
                if (played >= cap) continue
                perArtist[key] = played + 1
            }

            taken += candidate
            out += candidate
        }
        return out
    }

    /**
     * Prefer a known style outside the recent lane, but never let an unknown
     * style displace a known relevant candidate. With no style facts available
     * this returns zero, preserving the existing ranking exactly.
     */
    private fun styleDistance(
        song: Song,
        recentStyles: Set<String>,
        styleOf: (Song) -> Set<String>,
    ): Int {
        if (recentStyles.isEmpty()) return 0
        val styles = styleOf(song)
        if (styles.isEmpty()) return 2
        return if (styles.any { it !in recentStyles }) 0 else 1
    }

    /**
     * Whether two entries are the same recording. Ids differ between the audio
     * and video cuts of a track, which is exactly the case id equality misses.
     */
    fun isSameRecording(a: Song, b: Song): Boolean {
        if (a.videoId == b.videoId) return true
        val title = normalisedTitle(a.title)
        if (title.isEmpty() || title != normalisedTitle(b.title)) return false
        // One shared name is enough. The two cuts of a track get billed in
        // whatever order the upload used — "Pritam, Arijit Singh & Shilpa Rao"
        // against "Shilpa Rao, Arijit Singh, & Pritam" is one song, twice.
        val left = artistSet(a.artist)
        val right = artistSet(b.artist)
        return left.isEmpty() || right.isEmpty() || left.any { it in right }
    }

    /**
     * Whether two entries belong to the same title family under the no-repeat
     * preference. Version subtitles and remix artists vary between uploads,
     * e.g. "Savage Love (Laxed - Siren Beat)" by Jawsh 685 / Jason Derulo
     * versus "Savage Love" credited to Viral DJs.
     */
    private fun isSameSongAcrossMixVersions(a: Song, b: Song): Boolean {
        val leftTitle = mixFamilyTitle(a.title)
        if (leftTitle.isEmpty() || leftTitle != mixFamilyTitle(b.title)) return false
        // Equal bare titles with no version marker can be different songs by
        // different artists. Keep the ordinary artist guard for that case;
        // once a subtitle/version annotation differs, treat it as an alternate
        // cut even if the remix/cover upload has different billing.
        if (normalisedTitle(a.title) == normalisedTitle(b.title) &&
            !isRemix(a.title) && !isRemix(b.title)
        ) {
            val leftArtists = artistSet(a.artist)
            val rightArtists = artistSet(b.artist)
            return leftArtists.isEmpty() || rightArtists.isEmpty() || leftArtists.any { it in rightArtists }
        }
        return true
    }

    private fun isRemix(title: String): Boolean = MIX_LABEL.containsMatchIn(title)

    /** Removes version subtitles from title-family comparisons only. */
    private fun mixFamilyTitle(title: String): String = normalisedTitle(
        title.replace(VERSION_PARENTHETICAL, " "),
    ).replace(TRAILING_MIX_LABEL, "").trim()

    /**
     * `Kesariya (From "Brahmastra") | Official Video` and `Kesariya` are one
     * recording as far as a queue is concerned. Remix and cover markers are
     * deliberately left in — those really are different tracks.
     */
    fun normalisedTitle(raw: String): String = raw.lowercase(Locale.ROOT)
        .substringBefore(" | ")
        .replace(NOISE, " ")
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()

    /** The cast behind a credit, split out so billing order stops mattering. */
    fun artistSet(raw: String): Set<String> = raw.lowercase(Locale.ROOT)
        .replace(TOPIC, " ")
        .split(",", "&", "·", "•", ";", " feat", " ft.", " ft ", " x ", " with ")
        .map { it.replace(PUNCTUATION, " ").replace(SPACES, " ").trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val NOISE = Regex(
        """\((?:official|lyric|lyrics|lyrical|audio|video|visuali[sz]er|full song|hd|4k)[^)]*\)""" +
            """|\(from[^)]*\)""" +
            """|\[[^]]*]""" +
            """|\b(?:official (?:video|audio|music video)|lyrical video|full video|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MIX_LABEL = Regex(
        """\b(?:remix|re[\s-]?mix|(?:club|extended|dance|radio|house|dub|vip)\s+mix)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val VERSION_PARENTHETICAL = Regex("""\s*[\(\[][^)\]]*[)\]]""")
    private val TRAILING_MIX_LABEL = Regex(
        """\s+(?:(?:[\p{L}\p{N}'’&.]+\s+){0,4})?(?:remix|re[\s-]?mix|mix)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    /** Recent enough to reduce a streak without making an artist disappear. */
    private const val RECENT_ARTIST_WINDOW = 6
    private const val RECENT_STYLE_WINDOW = 5
    private val TOPIC = Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE)
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}

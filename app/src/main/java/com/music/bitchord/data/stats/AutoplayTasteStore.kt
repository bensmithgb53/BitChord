package com.music.bitchord.data.stats

import android.content.Context
import com.music.bitchord.data.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small persistent taste sample used only for AutoPlay exploration.
 *
 * Replay remains the complete listening record. This separate sample deliberately
 * excludes explicit Radio items so a long focused Radio session cannot dominate
 * later AutoPlay. It stores only the metadata needed to seed recommendations and
 * is bounded to keep both disk use and recommendation influence predictable.
 */
object AutoplayTasteStore {
    private const val PREFS = "autoplay_taste_store"
    private const val KEY_SEEDS = "seeds"
    /** Keep enough history for a long focused session not to erase older taste. */
    private const val MAX_SEEDS = 96
    /** Preserve artist breadth when one session contains many songs by one act. */
    private const val MAX_SEEDS_PER_ARTIST = 6

    private var preferences: android.content.SharedPreferences? = null
    private val lock = Any()
    private val seeds = ArrayList<Song>()

    fun init(context: Context) {
        synchronized(lock) {
            if (preferences != null) return
            preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            loadLocked()
        }
    }

    /** Records a meaningful non-Radio listen; Radio items are intentionally ignored. */
    fun recordMeaningfulListen(song: Song) {
        if (song.videoId.isBlank() || song.radioName != null) return
        synchronized(lock) {
            if (preferences == null) return
            seeds.removeAll { it.videoId == song.videoId }
            seeds += song.copy(queueTier = com.music.bitchord.data.model.QueueTier.CONTEXT)
            val artistKey = artistKey(song.artist)
            val sameArtist = seeds.withIndex()
                .filter { artistKey(it.value.artist) == artistKey }
                .dropLast(MAX_SEEDS_PER_ARTIST)
            sameArtist.asReversed().forEach { (index, _) -> seeds.removeAt(index) }
            while (seeds.size > MAX_SEEDS) seeds.removeAt(0)
            saveLocked()
        }
    }

    /** Most recent eligible seeds, newest first for AutoPlay exploration. */
    fun recentSeeds(): List<Song> = synchronized(lock) { seeds.asReversed().toList() }

    private fun artistKey(raw: String): String = raw
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun loadLocked() {
        seeds.clear()
        val raw = preferences?.getString(KEY_SEEDS, null) ?: return
        runCatching {
            val json = JSONArray(raw)
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                seeds += Song(
                    videoId = id,
                    title = item.optString("title"),
                    artist = item.optString("artist"),
                    thumbnailUrl = item.optString("art").takeIf { it.isNotBlank() },
                )
            }
        }.onFailure { seeds.clear() }
        while (seeds.size > MAX_SEEDS) seeds.removeAt(0)
    }

    private fun saveLocked() {
        val json = JSONArray()
        seeds.forEach { song ->
            json.put(
                JSONObject()
                    .put("id", song.videoId)
                    .put("title", song.title)
                    .put("artist", song.artist)
                    .put("art", song.thumbnailUrl ?: ""),
            )
        }
        preferences?.edit()?.putString(KEY_SEEDS, json.toString())?.apply()
    }
}

package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Small, in-memory AutoPlay report for troubleshooting recommendation quality.
 * Deliberately never stores video IDs, URLs, tokens, cookies, account IDs, or
 * file paths. It is cleared when the process is killed or by the Settings UI.
 */
object AutoplayDiagnostics {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    fun recordBatch(
        seed: Song,
        tasteSeeds: List<Song>,
        candidateCount: Int,
        selected: List<Song>,
        existingCount: Int,
        adaptiveExploration: Boolean = false,
        sourceAwareSelection: Boolean = false,
        longTermAnchor: Boolean = false,
        personalLanes: Int = 0,
    ) {
        val block = buildList {
            add("AutoPlay batch")
            add("seed: ${describe(seed)}")
            add("taste seeds used: ${tasteSeeds.size}; existing queue/history checked: $existingCount")
            if (adaptiveExploration) add("adaptive lane exploration: older personal-taste seed added")
            if (sourceAwareSelection) add("source-aware selection: recommendation pools rotated")
            if (longTermAnchor) add("long-term taste anchor: included in this refill")
            if (personalLanes > 0) add("personal taste lanes reserved: $personalLanes")
            add("related candidates received: $candidateCount")
            add("selected: ${selected.size}")
            selected.forEachIndexed { index, song -> add("  ${index + 1}. ${describe(song)}") }
            add("---")
        }
        _lines.value += block
    }

    fun recordFailure(message: String) {
        _lines.value += "AutoPlay failure: ${redact(message)}"
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun report(): String = buildString {
        appendLine("BitChord AutoPlay diagnostics")
        appendLine("Privacy: no video IDs, URLs, tokens, cookies, account IDs, or file paths are included.")
        appendLine("The report is kept in memory only and is not uploaded by this feature.")
        appendLine()
        if (_lines.value.isEmpty()) appendLine("No AutoPlay batches recorded yet.")
        else _lines.value.forEach(::appendLine)
    }

    private fun describe(song: Song): String =
        "${redact(song.title).take(100)} — ${redact(song.artist).take(80)}"

    private fun redact(value: String): String = value
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[url removed]")
        .replace(Regex("(?i)(token|cookie|auth|session|bearer)\\s*[:=]\\s*\\S+"), "[private value removed]")
        .replace(Regex("\\s+"), " ")
        .trim()
}

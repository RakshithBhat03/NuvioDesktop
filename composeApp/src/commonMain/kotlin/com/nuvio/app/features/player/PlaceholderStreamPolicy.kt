package com.nuvio.app.features.player

import com.nuvio.app.features.details.parseRuntimeMinutes

/**
 * Spots a "stream" that is really a provider error card. Debrid services and scrapers
 * sometimes answer with HTTP 200 and a short playable clip announcing the failure, so the
 * only tell is the file's shape against what the title should be.
 *
 * Both thresholds must agree, so a mis-scraped runtime alone cannot reject a real file, and
 * titles with no known runtime, or short ones, are never judged. Ported from NuvioTV's
 * PlaceholderStreamPolicy (without its byte-size floor, which needs the HTTP content length).
 */
internal object PlaceholderStreamPolicy {
    /** Only titles at least this long are judged; clips and extras are left alone. */
    const val MIN_GUARDED_RUNTIME_MS = 20L * 60L * 1000L

    /** A file shorter than this cannot be the feature for a guarded title. */
    const val MIN_PLAUSIBLE_DURATION_MS = 3L * 60L * 1000L

    /** A file must also be under this fraction of the expected runtime. */
    const val MAX_IMPLAUSIBLE_DURATION_RATIO = 0.33

    /** The episode's own runtime when the metadata has it, else the title-level runtime text. */
    fun expectedRuntimeMs(episodeRuntimeMinutes: Int?, titleRuntime: String?): Long? {
        val minutes = episodeRuntimeMinutes?.takeIf { it > 0 }
            ?: parseRuntimeMinutes(titleRuntime)?.takeIf { it > 0 }
            ?: return null
        return minutes * 60_000L
    }

    fun isPlaceholder(durationMs: Long, expectedRuntimeMs: Long?): Boolean {
        if (expectedRuntimeMs == null || expectedRuntimeMs < MIN_GUARDED_RUNTIME_MS) return false
        if (durationMs <= 0L) return false
        return durationMs < MIN_PLAUSIBLE_DURATION_MS &&
            durationMs < expectedRuntimeMs * MAX_IMPLAUSIBLE_DURATION_RATIO
    }
}

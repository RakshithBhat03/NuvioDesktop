package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamItem

internal const val MaxPlaybackRetries = 2
internal const val MaxSourceFailovers = 3
internal const val PlaybackRetryDelayMs = 1_500L

internal enum class PlayerRecoveryAction {
    /** Re-open the same source where it stopped. */
    Retry,

    /** Give up on this source and switch to the next playable one. */
    FailOver,

    /** Nothing automatic is left; show the error. */
    GiveUp,
}

internal data class PlayerRecoveryDecision(
    val action: PlayerRecoveryAction,
    /** Remember the source as dead for this title so no later failover lands on it. */
    val markSourceDead: Boolean = false,
)

/**
 * A source that will answer the same way every time: removed (404), expired (410), or not
 * media at all. 429 and timeouts are deliberately not dead; a rate limit or a slow
 * first byte says nothing about whether the link works.
 */
internal fun PlayerPlaybackFailure.isDeadSource(): Boolean =
    kind == PlayerPlaybackFailureKind.UnknownFormat ||
        kind == PlayerPlaybackFailureKind.NothingToPlay ||
        httpStatus == 404 ||
        httpStatus == 410

/** Local output problems (no audio device, GPU) look the same on every source, so switching cannot help. */
private fun PlayerPlaybackFailure.isSourceRelated(): Boolean =
    kind != PlayerPlaybackFailureKind.Other || httpStatus != null

/** Refusals that a second request will not change, though they are not proof the link is gone. */
private fun PlayerPlaybackFailure.isPermanentRefusal(): Boolean =
    httpStatus == 400 || httpStatus == 401 || httpStatus == 403

internal fun decidePlayerRecovery(
    failure: PlayerPlaybackFailure,
    retriesUsed: Int,
    failoversUsed: Int,
): PlayerRecoveryDecision {
    if (!failure.isSourceRelated()) return PlayerRecoveryDecision(PlayerRecoveryAction.GiveUp)
    val dead = failure.isDeadSource()
    if (!dead && !failure.isPermanentRefusal() && retriesUsed < MaxPlaybackRetries) {
        return PlayerRecoveryDecision(PlayerRecoveryAction.Retry)
    }
    if (failoversUsed >= MaxSourceFailovers) return PlayerRecoveryDecision(PlayerRecoveryAction.GiveUp)
    return PlayerRecoveryDecision(PlayerRecoveryAction.FailOver, markSourceDead = dead)
}

/**
 * Chooses which list entry the player may switch to on its own after a source fails.
 *
 * Only entries that start playing without side effects qualify: direct http(s) links, and
 * debrid entries the service already reports as cached. Torrents (they would turn into peer
 * to peer streaming), external-open links, and anything a debrid service has not confirmed as
 * cached (resolving it could start a download) are skipped.
 */
internal object PlayerSourceFailoverSelection {
    fun failoverKey(stream: StreamItem): String? = stream.playerSourceIdentityKey()

    fun isAutoFailoverPlayable(
        stream: StreamItem,
        season: Int?,
        episode: Int?,
        debridResolveAvailable: Boolean,
    ): Boolean {
        if (stream.shouldOpenExternally) return false
        when (stream.debridCacheStatus?.state) {
            null, StreamDebridCacheState.CACHED -> Unit
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.NOT_CACHED,
            StreamDebridCacheState.UNKNOWN,
            -> return false
        }
        val url = stream.playableDirectUrl
        if (url != null) {
            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                return false
            }
        } else {
            val cachedDebrid = stream.isInstalledAddonStream &&
                (stream.isDirectDebridStream || stream.isCachedDebridTorrentStream)
            if (!debridResolveAvailable || !cachedDebrid) return false
        }
        val resolve = stream.clientResolve
        if (resolve != null) {
            if (season != null && resolve.season != null && resolve.season != season) return false
            if (episode != null && resolve.episode != null && resolve.episode != episode) return false
        }
        return true
    }

    /**
     * The first qualifying entry after the one now playing, keeping the displayed order, or
     * from the top when the playing source is not in [streams] (a resolved debrid link is
     * played under a different URL than its list entry).
     */
    fun selectNext(
        streams: List<StreamItem>,
        currentKey: String?,
        currentUrl: String?,
        excludedKeys: Set<String>,
        season: Int?,
        episode: Int?,
        debridResolveAvailable: Boolean,
    ): StreamItem? {
        val currentIndex = streams.indexOfFirst { stream ->
            (currentKey != null && failoverKey(stream) == currentKey) ||
                (currentUrl != null && stream.playableDirectUrl == currentUrl)
        }
        for (index in (currentIndex + 1) until streams.size) {
            val candidate = streams[index]
            if (!isAutoFailoverPlayable(candidate, season, episode, debridResolveAvailable)) continue
            val key = failoverKey(candidate) ?: continue
            if (key in excludedKeys) continue
            return candidate
        }
        return null
    }
}

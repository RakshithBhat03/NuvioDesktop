package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.isDesktop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.player_recovery_retrying
import nuvio.composeapp.generated.resources.player_recovery_switching_source
import org.jetbrains.compose.resources.getString

private val log = Logger.withTag("PlayerRecovery")

private const val FailoverSourceListWaitMs = 15_000L
private const val MaxFailoverResolveAttempts = 5

/** Playback past this after a retry shows the source is healthy again, so the retry budget refills. */
private const val StableProgressResetMs = 15_000L

/**
 * Bookkeeping for automatic recovery. Retries are per source; failovers, dead sources and
 * tried sources are per title, so a flaky release cannot be cycled back to after it failed.
 */
internal class PlayerFailureRecoveryState {
    private var titleKey: String? = null
    private var sourceKey: String? = null

    var retryCount = 0
    var failoverCount = 0
    val deadSourceKeys = mutableSetOf<String>()
    val attemptedSourceKeys = mutableSetOf<String>()
    var retryResumePositionMs = 0L
    var job: Job? = null

    fun scopeTo(titleKey: String, sourceKey: String) {
        if (this.titleKey != titleKey) {
            this.titleKey = titleKey
            failoverCount = 0
            retryCount = 0
            deadSourceKeys.clear()
            attemptedSourceKeys.clear()
        } else if (this.sourceKey != sourceKey) {
            retryCount = 0
        }
        this.sourceKey = sourceKey
    }
}

private val PlayerScreenRuntime.recoveryTitleKey: String
    get() = "$parentMetaId|${activeVideoId.orEmpty()}|$activeSeasonNumber|$activeEpisodeNumber"

private val PlayerScreenRuntime.recoveryController: PlayerEngineController?
    get() = (playerController ?: playerLifecycleController)?.takeIf { it.reportsPlaybackFailures }

/**
 * Where a recovered source should start: the last played position, or for a source that
 * never got going the position this title was opened at.
 */
internal fun PlayerScreenRuntime.recoveryResumePositionMs(): Long {
    val played = playbackSnapshot.positionMs.takeIf {
        it > 0L && initialLoadCompleted && playbackSnapshotKey == activePlaybackKey
    }
    return played ?: activeInitialPositionMs.coerceAtLeast(0L)
}

internal fun PlayerScreenRuntime.showPlaybackError(message: String?) {
    errorMessage = message
    if (message != null) {
        scrubbingPositionMs = null
        controlsVisible = !playerControlsLocked
        removeFailedStreamFromCache()
    }
}

internal fun PlayerScreenRuntime.showPlayerRecoveryNotice(text: String) {
    if (isDesktop) {
        playerNotificationMessage = text
        playerNotificationToken += 1L
    } else {
        NuvioToastController.show(text)
    }
}

/**
 * Handles an engine error by retrying or switching source instead of showing the error card.
 * Returns true when recovery took over; false leaves the caller to surface the error.
 * Engines that do not report a typed failure never reach past the first guard.
 */
internal fun PlayerScreenRuntime.tryRecoverFromPlaybackError(message: String?): Boolean {
    if (message == null) return false
    val controller = recoveryController ?: return false
    val failure = controller.takePlaybackFailure() ?: return false
    // A second report for a failure already being handled must not spend more of the budget.
    if (failureRecovery.job?.isActive == true) return true

    failureRecovery.scopeTo(recoveryTitleKey, activePlaybackIdentity)
    val decision = decidePlayerRecovery(
        failure = failure,
        retriesUsed = failureRecovery.retryCount,
        failoversUsed = failureRecovery.failoverCount,
    )
    log.w {
        "playback failure kind=${failure.kind} http=${failure.httpStatus} -> ${decision.action} " +
            "retries=${failureRecovery.retryCount} failovers=${failureRecovery.failoverCount}"
    }
    return when (decision.action) {
        PlayerRecoveryAction.GiveUp -> false
        PlayerRecoveryAction.Retry -> {
            startSameSourceRetry(controller, message)
            true
        }
        PlayerRecoveryAction.FailOver -> {
            startSourceFailover(message, markSourceDead = decision.markSourceDead)
            true
        }
    }
}

/** Refills the retry budget once a retried source has played on for a while. */
internal fun PlayerScreenRuntime.noteRecoveryPlaybackProgress(snapshot: PlayerPlaybackSnapshot) {
    val state = failureRecovery
    if (state.retryCount == 0 || !snapshot.isPlaying) return
    if (snapshot.positionMs - state.retryResumePositionMs >= StableProgressResetMs) {
        state.retryCount = 0
    }
}

private fun PlayerScreenRuntime.startSameSourceRetry(controller: PlayerEngineController, message: String) {
    val attempt = ++failureRecovery.retryCount
    val sourceKey = activePlaybackIdentity
    val resumeMs = recoveryResumePositionMs()
    // Honour a pause the user made before the connection dropped.
    val playWhenReady = shouldPlay
    failureRecovery.retryResumePositionMs = resumeMs
    failureRecovery.job = scope.launch {
        showPlayerRecoveryNotice(getString(Res.string.player_recovery_retrying, attempt, MaxPlaybackRetries))
        delay(PlaybackRetryDelayMs)
        if (activePlaybackIdentity != sourceKey) return@launch
        // The re-attached engine starts with its default tracks, like any freshly opened source.
        resetTrackSelectionState()
        autoFetchedAddonSubtitlesForKey = null
        if (!controller.retryAt(resumeMs, playWhenReady)) {
            showPlaybackError(message)
        }
    }
}

private fun PlayerScreenRuntime.startSourceFailover(message: String, markSourceDead: Boolean) {
    val state = failureRecovery
    val failedKey = activeSourceIdentityKey
    if (failedKey != null) {
        state.attemptedSourceKeys += failedKey
        if (markSourceDead) state.deadSourceKeys += failedKey
    }
    val failedUrl = activeSourceUrl
    val sourceKey = activePlaybackIdentity
    val resumeMs = recoveryResumePositionMs()
    state.job = scope.launch {
        val handled = failOverToNextSource(sourceKey = sourceKey, failedUrl = failedUrl, resumeMs = resumeMs)
        if (!handled) showPlaybackError(message)
    }
}

/**
 * Returns true when a switch happened or the user changed source meanwhile, false when no
 * playable alternative exists and the error should be shown.
 */
private suspend fun PlayerScreenRuntime.failOverToNextSource(
    sourceKey: String,
    failedUrl: String,
    resumeMs: Long,
): Boolean {
    val videoId = activeVideoId ?: return false
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    val state = failureRecovery
    try {
        PlayerStreamsRepository.loadSources(
            type = contentType ?: parentMetaType,
            videoId = videoId,
            season = season,
            episode = episode,
        )
        // The list is only in its final order once every addon has answered.
        withTimeoutOrNull(FailoverSourceListWaitMs) {
            PlayerStreamsRepository.sourceState.first { listState ->
                !listState.isAnyLoading && (listState.groups.isNotEmpty() || listState.emptyStateReason != null)
            }
        }

        var resolveAttempts = 0
        while (true) {
            if (activePlaybackIdentity != sourceKey) return true
            val candidate = PlayerSourceFailoverSelection.selectNext(
                streams = PlayerStreamsRepository.sourceState.value.groups.flatMap { it.streams },
                currentKey = activeSourceIdentityKey,
                currentUrl = failedUrl,
                excludedKeys = state.deadSourceKeys + state.attemptedSourceKeys,
                season = season,
                episode = episode,
                debridResolveAvailable = DebridSettingsRepository.snapshot().canResolvePlayableLinks,
            ) ?: return false
            PlayerSourceFailoverSelection.failoverKey(candidate)?.let { state.attemptedSourceKeys += it }

            val playable = resolveFailoverCandidate(candidate, season, episode)
            if (playable?.playableDirectUrl == null || playable.playableDirectUrl == failedUrl) {
                if (++resolveAttempts >= MaxFailoverResolveAttempts) return false
                continue
            }
            if (activePlaybackIdentity != sourceKey) return true

            state.failoverCount++
            log.w { "failing over to '${playable.streamLabel}' (${state.failoverCount}/$MaxSourceFailovers)" }
            showPlayerRecoveryNotice(
                getString(Res.string.player_recovery_switching_source, state.failoverCount, MaxSourceFailovers),
            )
            switchToSource(playable, resumePositionMs = resumeMs)
            return true
        }
    } finally {
        PlayerStreamsRepository.stopSourcesLoading()
    }
}

/**
 * Entries the player can start without a debrid lookup play as they are; cached debrid
 * entries are resolved here, and anything that would fall through to peer to peer is dropped.
 */
private suspend fun resolveFailoverCandidate(stream: StreamItem, season: Int?, episode: Int?): StreamItem? {
    if (!DirectDebridPlaybackResolver.shouldResolveToPlayableStream(stream)) {
        return stream.takeIf { it.playableDirectUrl != null }
    }
    val result = DirectDebridPlaybackResolver.resolveToPlayableStream(stream, season, episode)
    return (result as? DirectDebridPlayableResult.Success)?.stream
}

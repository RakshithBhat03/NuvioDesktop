package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsUiState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerScreenRuntimeFailureRecoveryTest {
    private class FailingController(
        override val reportsPlaybackFailures: Boolean = true,
        var failure: PlayerPlaybackFailure? = null,
    ) : PlayerEngineController {
        val retries = mutableListOf<Pair<Long, Boolean>>()
        var paused = false

        override fun takePlaybackFailure(): PlayerPlaybackFailure? = failure.also { failure = null }
        override fun retryAt(positionMs: Long, playWhenReady: Boolean): Boolean {
            retries += positionMs to playWhenReady
            return true
        }

        override fun play() = Unit
        override fun pause() {
            paused = true
        }
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun selectSubtitleTrack(index: Int) = Unit
        override fun setSubtitleUri(url: String) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }

    private val networkCutOff = PlayerPlaybackFailure(PlayerPlaybackFailureKind.PrematureEnd)

    @Test
    fun `network failure retries the same source at the last position after a delay`() = runTest {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply { scope = backgroundScope }
        val controller = FailingController(failure = networkCutOff)
        runtime.playerController = controller
        runtime.initialLoadCompleted = true
        runtime.updatePlaybackSnapshot(
            PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 123_000L, durationMs = 600_000L),
        )

        assertTrue(runtime.tryRecoverFromPlaybackError("stream ended early"))
        assertNull(runtime.errorMessage)
        assertTrue(controller.retries.isEmpty())

        runtime.failureRecovery.job?.join()
        assertEquals(listOf(123_000L to true), controller.retries)
    }

    @Test
    fun `retry keeps the paused state the user left the player in`() = runTest {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply { scope = backgroundScope }
        val controller = FailingController(failure = networkCutOff)
        runtime.playerController = controller
        runtime.shouldPlay = false

        assertTrue(runtime.tryRecoverFromPlaybackError("stream ended early"))
        runtime.failureRecovery.job?.join()

        assertEquals(listOf(0L to false), controller.retries)
    }

    @Test
    fun `a failure reported twice while recovering spends one retry`() = runTest {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply { scope = backgroundScope }
        val controller = FailingController(failure = networkCutOff)
        runtime.playerController = controller

        assertTrue(runtime.tryRecoverFromPlaybackError("first"))
        controller.failure = networkCutOff
        assertTrue(runtime.tryRecoverFromPlaybackError("duplicate"))

        runtime.failureRecovery.job?.join()
        assertEquals(1, controller.retries.size)
        assertEquals(1, runtime.failureRecovery.retryCount)
    }

    @Test
    fun `retry budget refills after the source plays on`() = runTest {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply { scope = backgroundScope }
        val controller = FailingController(failure = networkCutOff)
        runtime.playerController = controller
        runtime.initialLoadCompleted = true
        runtime.updatePlaybackSnapshot(PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 50_000L))
        runtime.tryRecoverFromPlaybackError("cut off")
        runtime.failureRecovery.job?.join()
        assertEquals(1, runtime.failureRecovery.retryCount)

        runtime.noteRecoveryPlaybackProgress(
            PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 55_000L),
        )
        assertEquals(1, runtime.failureRecovery.retryCount)
        runtime.noteRecoveryPlaybackProgress(
            PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 66_000L),
        )
        assertEquals(0, runtime.failureRecovery.retryCount)
    }

    private fun runtimeWithTitleRuntime(runtime: String?, scope: kotlinx.coroutines.CoroutineScope) =
        PlayerScreenRuntime(testPlayerScreenArgs()).apply {
            this.scope = scope
            metaUiState = MetaDetailsUiState(
                meta = MetaDetails(id = "tt1234567", type = "movie", name = "Title", runtime = runtime),
            )
        }

    private fun loadedSnapshot(durationMs: Long) =
        PlayerPlaybackSnapshot(isLoading = false, isPlaying = true, positionMs = 1_000L, durationMs = durationMs)

    @Test
    fun `a short clip for a feature length title is paused and reported once failovers run out`() = runTest {
        val runtime = runtimeWithTitleRuntime("120 min", backgroundScope)
        val controller = FailingController()
        runtime.playerController = controller
        runtime.failureRecovery.scopeTo("tt1234567|tt1234567|null|null", runtime.activePlaybackIdentity)
        runtime.failureRecovery.failoverCount = MaxSourceFailovers

        assertTrue(runtime.rejectPlaceholderSourceIfNeeded(loadedSnapshot(durationMs = 30_000L)))
        assertTrue(controller.paused)
        // The check does not run again for the same source.
        assertFalse(runtime.rejectPlaceholderSourceIfNeeded(loadedSnapshot(durationMs = 30_000L)))
    }

    @Test
    fun `full length files and unknown runtimes are not rejected`() = runTest {
        val known = runtimeWithTitleRuntime("120 min", backgroundScope)
        known.playerController = FailingController()
        assertFalse(known.rejectPlaceholderSourceIfNeeded(loadedSnapshot(durationMs = 7_000_000L)))

        val unknown = runtimeWithTitleRuntime(null, backgroundScope)
        val controller = FailingController()
        unknown.playerController = controller
        assertFalse(unknown.rejectPlaceholderSourceIfNeeded(loadedSnapshot(durationMs = 30_000L)))
        assertFalse(controller.paused)
    }

    @Test
    fun `loading snapshots and engines without failure reporting are ignored`() = runTest {
        val runtime = runtimeWithTitleRuntime("120 min", backgroundScope)
        runtime.playerController = FailingController()
        assertFalse(
            runtime.rejectPlaceholderSourceIfNeeded(PlayerPlaybackSnapshot(isLoading = true, durationMs = 30_000L)),
        )

        runtime.playerController = FailingController(reportsPlaybackFailures = false)
        assertFalse(runtime.rejectPlaceholderSourceIfNeeded(loadedSnapshot(durationMs = 30_000L)))
    }

    @Test
    fun `engines that do not report failures keep the plain error path`() = runTest {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply { scope = backgroundScope }
        runtime.playerController = FailingController(reportsPlaybackFailures = false, failure = networkCutOff)

        assertFalse(runtime.tryRecoverFromPlaybackError("boom"))
    }

    @Test
    fun `failures with no typed cause or no source relation are not recovered`() = runTest {
        val runtime = PlayerScreenRuntime(testPlayerScreenArgs()).apply { scope = backgroundScope }
        val controller = FailingController()
        runtime.playerController = controller

        assertFalse(runtime.tryRecoverFromPlaybackError("no cause"))
        controller.failure = PlayerPlaybackFailure(PlayerPlaybackFailureKind.Other)
        assertFalse(runtime.tryRecoverFromPlaybackError("audio device"))
        assertFalse(runtime.tryRecoverFromPlaybackError(null))
    }

    private fun testPlayerScreenArgs() = PlayerScreenArgs(
        profileId = 1,
        title = "Title",
        sourceUrl = "https://example.com/video.mp4",
        sourceAudioUrl = null,
        sourceHeaders = emptyMap(),
        sourceResponseHeaders = emptyMap(),
        streamType = null,
        providerName = "Provider",
        streamTitle = "Source",
        streamSubtitle = null,
        initialBingeGroup = null,
        pauseDescription = null,
        onBack = {},
        onOpenInExternalPlayer = null,
        onOpenExternalUrl = null,
        modifier = Modifier,
        logo = null,
        poster = null,
        background = null,
        seasonNumber = null,
        episodeNumber = null,
        episodeTitle = null,
        episodeThumbnail = null,
        contentType = "movie",
        videoId = "tt1234567",
        parentMetaId = "tt1234567",
        parentMetaType = "movie",
        providerAddonId = null,
        torrentInfoHash = null,
        torrentFileIdx = null,
        torrentFilename = null,
        torrentTrackers = emptyList(),
        initialPositionMs = 0L,
        initialProgressFraction = null,
    )
}

package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamDebridCacheState
import com.nuvio.app.features.streams.StreamDebridCacheStatus
import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerFailureRecoveryPolicyTest {
    private fun failure(kind: PlayerPlaybackFailureKind, status: Int? = null) =
        PlayerPlaybackFailure(kind, status)

    @Test
    fun `network failures retry the same source twice before failing over`() {
        val failure = failure(PlayerPlaybackFailureKind.PrematureEnd)

        assertEquals(PlayerRecoveryAction.Retry, decidePlayerRecovery(failure, 0, 0).action)
        assertEquals(PlayerRecoveryAction.Retry, decidePlayerRecovery(failure, 1, 0).action)
        assertEquals(
            PlayerRecoveryDecision(PlayerRecoveryAction.FailOver, markSourceDead = false),
            decidePlayerRecovery(failure, 2, 0),
        )
    }

    @Test
    fun `missing or unplayable sources skip retries and are marked dead`() {
        val dead = listOf(
            failure(PlayerPlaybackFailureKind.Loading, 404),
            failure(PlayerPlaybackFailureKind.Loading, 410),
            failure(PlayerPlaybackFailureKind.UnknownFormat),
            failure(PlayerPlaybackFailureKind.NothingToPlay),
        )
        dead.forEach { failure ->
            assertTrue(failure.isDeadSource())
            assertEquals(
                PlayerRecoveryDecision(PlayerRecoveryAction.FailOver, markSourceDead = true),
                decidePlayerRecovery(failure, 0, 0),
            )
        }
    }

    @Test
    fun `rate limits and timeouts are retried and never marked dead`() {
        val rateLimited = failure(PlayerPlaybackFailureKind.Loading, 429)
        val timedOut = failure(PlayerPlaybackFailureKind.Loading)

        listOf(rateLimited, timedOut).forEach { failure ->
            assertFalse(failure.isDeadSource())
            assertEquals(PlayerRecoveryAction.Retry, decidePlayerRecovery(failure, 0, 0).action)
            assertEquals(
                PlayerRecoveryDecision(PlayerRecoveryAction.FailOver, markSourceDead = false),
                decidePlayerRecovery(failure, MaxPlaybackRetries, 0),
            )
        }
    }

    @Test
    fun `refused requests fail over without retrying or being marked dead`() {
        listOf(400, 401, 403).forEach { status ->
            assertEquals(
                PlayerRecoveryDecision(PlayerRecoveryAction.FailOver, markSourceDead = false),
                decidePlayerRecovery(failure(PlayerPlaybackFailureKind.Loading, status), 0, 0),
            )
        }
    }

    @Test
    fun `failovers are capped per title`() {
        assertEquals(
            PlayerRecoveryAction.GiveUp,
            decidePlayerRecovery(failure(PlayerPlaybackFailureKind.Loading, 404), 0, MaxSourceFailovers).action,
        )
        assertEquals(
            PlayerRecoveryAction.GiveUp,
            decidePlayerRecovery(failure(PlayerPlaybackFailureKind.PrematureEnd), MaxPlaybackRetries, MaxSourceFailovers).action,
        )
        assertEquals(
            PlayerRecoveryAction.FailOver,
            decidePlayerRecovery(failure(PlayerPlaybackFailureKind.Loading, 404), 0, MaxSourceFailovers - 1).action,
        )
    }

    @Test
    fun `local output failures are left to the error screen`() {
        assertEquals(
            PlayerRecoveryAction.GiveUp,
            decidePlayerRecovery(failure(PlayerPlaybackFailureKind.Other), 0, 0).action,
        )
        assertEquals(
            PlayerRecoveryAction.Retry,
            decidePlayerRecovery(failure(PlayerPlaybackFailureKind.Other, 503), 0, 0).action,
        )
    }

    private fun direct(name: String, url: String? = "https://cdn.example/$name.mkv") = StreamItem(
        name = name,
        url = url,
        addonName = "Addon",
        addonId = "addon:test",
    )

    private fun select(
        streams: List<StreamItem>,
        current: StreamItem? = null,
        excluded: Set<String> = emptySet(),
        season: Int? = null,
        episode: Int? = null,
        debrid: Boolean = false,
    ): StreamItem? = PlayerSourceFailoverSelection.selectNext(
        streams = streams,
        currentKey = current?.let(PlayerSourceFailoverSelection::failoverKey),
        currentUrl = current?.playableDirectUrl,
        excludedKeys = excluded,
        season = season,
        episode = episode,
        debridResolveAvailable = debrid,
    )

    @Test
    fun `selects the next playable entry after the current one in displayed order`() {
        val first = direct("first")
        val second = direct("second")
        val third = direct("third")

        assertEquals(third, select(listOf(first, second, third), current = second))
        assertEquals(second, select(listOf(first, second, third), current = first))
        assertNull(select(listOf(first, second, third), current = third))
    }

    @Test
    fun `starts from the top when the playing source is not listed`() {
        val first = direct("first")
        val second = direct("second")

        assertEquals(first, select(listOf(first, second), current = direct("unlisted")))
        assertEquals(first, select(listOf(first, second), current = null))
    }

    @Test
    fun `skips sources already marked dead or tried`() {
        val first = direct("first")
        val second = direct("second")
        val third = direct("third")
        val excluded = setOf(PlayerSourceFailoverSelection.failoverKey(second)!!)

        assertEquals(third, select(listOf(first, second, third), current = first, excluded = excluded))
    }

    @Test
    fun `skips torrents magnets and external links`() {
        val current = direct("current")
        val torrent = StreamItem(infoHash = "a".repeat(40), addonName = "Addon", addonId = "addon:test")
        val magnet = StreamItem(url = "magnet:?xt=urn:btih:${"b".repeat(40)}", addonName = "Addon", addonId = "addon:test")
        val external = StreamItem(externalUrl = "https://example.com/watch", addonName = "Addon", addonId = "addon:test")
        val playable = direct("playable")

        assertEquals(playable, select(listOf(current, torrent, magnet, external, playable), current = current))
        assertNull(select(listOf(current, torrent, magnet, external), current = current, debrid = true))
    }

    @Test
    fun `skips links that are not http`() {
        val current = direct("current")
        val ftp = direct("ftp", url = "ftp://host/file.mkv")

        assertNull(select(listOf(current, ftp), current = current))
    }

    private fun debridStream(
        name: String,
        state: StreamDebridCacheState?,
        url: String? = "https://debrid.example/$name.mkv",
    ) = StreamItem(
        name = name,
        url = url,
        addonName = "Addon",
        addonId = "addon:test",
        debridCacheStatus = state?.let { StreamDebridCacheStatus("rd", "Real-Debrid", it) },
    )

    @Test
    fun `only debrid entries confirmed cached are used`() {
        val current = direct("current")
        val cached = debridStream("cached", StreamDebridCacheState.CACHED)

        listOf(
            StreamDebridCacheState.CHECKING,
            StreamDebridCacheState.NOT_CACHED,
            StreamDebridCacheState.UNKNOWN,
        ).forEach { state ->
            assertNull(select(listOf(current, debridStream("x", state)), current = current))
        }
        assertEquals(cached, select(listOf(current, debridStream("x", StreamDebridCacheState.NOT_CACHED), cached), current = current))
    }

    @Test
    fun `cached debrid entries without a link need debrid resolving to be available`() {
        val current = direct("current")
        val unresolved = StreamItem(
            name = "unresolved",
            addonName = "Addon",
            addonId = "addon:test",
            clientResolve = StreamClientResolve(type = "debrid", service = "realdebrid", isCached = true),
        )

        assertNull(select(listOf(current, unresolved), current = current, debrid = false))
        assertEquals(unresolved, select(listOf(current, unresolved), current = current, debrid = true))
    }

    @Test
    fun `skips entries for another season or episode`() {
        val current = direct("current")
        val wrongEpisode = StreamItem(
            name = "wrong",
            url = "https://cdn.example/wrong.mkv",
            addonName = "Addon",
            addonId = "addon:test",
            clientResolve = StreamClientResolve(season = 1, episode = 4),
        )
        val right = direct("right")

        assertEquals(right, select(listOf(current, wrongEpisode, right), current = current, season = 1, episode = 5))
    }
}

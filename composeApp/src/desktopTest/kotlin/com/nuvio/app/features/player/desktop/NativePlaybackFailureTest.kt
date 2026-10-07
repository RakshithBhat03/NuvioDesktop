package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerPlaybackFailure
import com.nuvio.app.features.player.PlayerPlaybackFailureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NativePlaybackFailureTest {
    @Test
    fun `decodes kind and http status packed by the bridge`() {
        assertEquals(
            PlayerPlaybackFailure(PlayerPlaybackFailureKind.Loading, httpStatus = 404),
            decodeNativePlaybackFailure(1404.0),
        )
        assertEquals(
            PlayerPlaybackFailure(PlayerPlaybackFailureKind.Loading, httpStatus = 429),
            decodeNativePlaybackFailure(1429.0),
        )
    }

    @Test
    fun `failures without a status carry none`() {
        assertEquals(
            PlayerPlaybackFailure(PlayerPlaybackFailureKind.UnknownFormat),
            decodeNativePlaybackFailure(2000.0),
        )
        assertEquals(
            PlayerPlaybackFailure(PlayerPlaybackFailureKind.PrematureEnd),
            decodeNativePlaybackFailure(4000.0),
        )
    }

    @Test
    fun `a status outside the http error range is ignored`() {
        assertEquals(
            PlayerPlaybackFailure(PlayerPlaybackFailureKind.Loading),
            decodeNativePlaybackFailure(1200.0),
        )
    }

    @Test
    fun `unknown kinds and malformed values are rejected`() {
        assertNull(decodeNativePlaybackFailure(0.0))
        assertNull(decodeNativePlaybackFailure(404.0))
        assertNull(decodeNativePlaybackFailure(9000.0))
        assertNull(decodeNativePlaybackFailure(Double.NaN))
    }
}

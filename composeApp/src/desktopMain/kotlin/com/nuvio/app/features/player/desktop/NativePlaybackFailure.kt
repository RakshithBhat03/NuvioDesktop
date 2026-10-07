package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerPlaybackFailure
import com.nuvio.app.features.player.PlayerPlaybackFailureKind

/** Event type the macOS bridge raises when mpv fails or a network stream is cut off. */
internal const val NativePlaybackFailureEvent = "playbackFailure"

private const val KIND_SCALE = 1000

/**
 * The bridge packs `kind * 1000 + httpStatus` into the event's double so the failure
 * needs no extra JNI entry point (which the Windows and Linux bridges would lack).
 */
internal fun decodeNativePlaybackFailure(code: Double): PlayerPlaybackFailure? {
    if (!code.isFinite() || code < KIND_SCALE) return null
    val packed = code.toLong()
    val kind = when ((packed / KIND_SCALE).toInt()) {
        1 -> PlayerPlaybackFailureKind.Loading
        2 -> PlayerPlaybackFailureKind.UnknownFormat
        3 -> PlayerPlaybackFailureKind.NothingToPlay
        4 -> PlayerPlaybackFailureKind.PrematureEnd
        5 -> PlayerPlaybackFailureKind.Other
        else -> return null
    }
    val status = (packed % KIND_SCALE).toInt().takeIf { it in 400..599 }
    return PlayerPlaybackFailure(kind = kind, httpStatus = status)
}

/** User-facing text for the error card once recovery gives up. */
internal fun PlayerPlaybackFailure.describe(): String = when (kind) {
    PlayerPlaybackFailureKind.Loading ->
        httpStatus?.let { "Playback failed: the server answered HTTP $it." }
            ?: "Playback failed: the stream could not be loaded."
    PlayerPlaybackFailureKind.UnknownFormat -> "Playback failed: unrecognized file format."
    PlayerPlaybackFailureKind.NothingToPlay -> "Playback failed: the file has no playable audio or video."
    PlayerPlaybackFailureKind.PrematureEnd -> "Playback stopped: the stream ended before the video did."
    PlayerPlaybackFailureKind.Other ->
        httpStatus?.let { "Playback failed: the server answered HTTP $it." }
            ?: "Playback failed."
}

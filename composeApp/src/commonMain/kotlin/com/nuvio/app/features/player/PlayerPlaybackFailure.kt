package com.nuvio.app.features.player

/**
 * Why an engine stopped playing a source, for engines that can tell. Only the
 * macOS desktop engine reports this today; every other engine leaves it unset
 * and keeps the plain error-message path.
 */
enum class PlayerPlaybackFailureKind {
    /** The source could not be opened or its connection broke while reading. */
    Loading,

    /** The data was fetched but is not a media container the engine recognises. */
    UnknownFormat,

    /** The container opened but holds nothing playable. */
    NothingToPlay,

    /** A network stream stopped far short of its declared duration. */
    PrematureEnd,

    /** Anything local to the machine (audio/video output, unsupported feature). */
    Other,
}

data class PlayerPlaybackFailure(
    val kind: PlayerPlaybackFailureKind,
    /** HTTP status the server answered with, when the engine saw one. */
    val httpStatus: Int? = null,
)

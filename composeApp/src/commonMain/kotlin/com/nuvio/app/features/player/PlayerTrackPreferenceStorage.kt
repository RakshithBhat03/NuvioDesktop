package com.nuvio.app.features.player

data class PersistedPlayerTrackPreference(
    val subtitleType: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleName: String? = null,
    val subtitleTrackId: String? = null,
    val addonSubtitleId: String? = null,
    val addonSubtitleUrl: String? = null,
    val addonSubtitleItemId: String? = null,
    val addonSubtitleAddonName: String? = null,
    val audioLanguage: String? = null,
    val audioName: String? = null,
    val audioTrackId: String? = null,
    val subtitleIsForced: Boolean? = null,
)

internal data class ResolvedPlayerTrackPreferences(
    val audio: List<PersistedPlayerTrackPreference>,
    val subtitle: List<PersistedPlayerTrackPreference>,
)

internal fun PersistedPlayerTrackPreference.hasAudioSelection(): Boolean =
    !audioTrackId.isNullOrBlank() || !audioLanguage.isNullOrBlank() || !audioName.isNullOrBlank()

internal fun PersistedPlayerTrackPreference.hasSubtitleSelection(): Boolean =
    !subtitleType.isNullOrBlank() || !subtitleTrackId.isNullOrBlank() ||
        !subtitleLanguage.isNullOrBlank() || !subtitleName.isNullOrBlank()

internal fun resolvePlayerTrackPreferences(
    episode: PersistedPlayerTrackPreference?,
    show: PersistedPlayerTrackPreference?,
): ResolvedPlayerTrackPreferences = ResolvedPlayerTrackPreferences(
    audio = listOfNotNull(
        episode?.takeIf { it.hasAudioSelection() },
        show?.takeIf { it.hasAudioSelection() },
    ).distinct(),
    subtitle = listOfNotNull(
        episode?.takeIf { it.hasSubtitleSelection() },
        show?.takeIf { it.hasSubtitleSelection() },
    ).distinct(),
)

internal fun PersistedPlayerTrackPreference.asShowFallback(): PersistedPlayerTrackPreference = copy(
    subtitleTrackId = null,
    addonSubtitleId = null,
    addonSubtitleUrl = null,
    addonSubtitleItemId = null,
    audioTrackId = null,
)

internal fun buildEpisodeTrackPreferenceId(
    parentMetaId: String,
    videoId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = "episode|${parentMetaId.trim()}|${videoId.trim()}|${seasonNumber ?: -1}|${episodeNumber ?: -1}"

object PersistedSubtitleSelectionType {
    const val INTERNAL = "INTERNAL"
    const val ADDON = "ADDON"
    const val DISABLED = "DISABLED"
}

internal expect object PlayerTrackPreferenceStorage {
    fun load(contentId: String): PersistedPlayerTrackPreference?
    fun save(contentId: String, preference: PersistedPlayerTrackPreference)
    fun loadSubtitleDelayMs(videoId: String): Int?
    fun saveSubtitleDelayMs(videoId: String, delayMs: Int)
}

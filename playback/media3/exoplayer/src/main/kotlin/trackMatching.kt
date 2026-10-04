package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.Format
import androidx.media3.common.util.Util
import org.jellyfin.playback.core.mediastream.MediaStreamTrack

/**
 * Tells two of the container's formats apart by the second attribute both sides state.
 */
internal typealias TrackDiscriminator<T> = (track: T, format: Format) -> Boolean

/**
 * The container title of a track, which the server passes on unchanged as [MediaStreamTrack.name].
 * Two tracks of one language and codec are often only told apart by it ("SDH", "Commentary").
 * Untitled on both sides counts as a match, so an untitled track still stands out from a titled one.
 */
internal fun <T : MediaStreamTrack> sameTitle(): TrackDiscriminator<T> = { track, format ->
	format.label.orEmpty().trim().equals(track.name.orEmpty().trim(), ignoreCase = true)
}

/**
 * Whether the media source and the container describe the same language. The media source spells
 * languages out in three letters where the container has normalised them to two, so both sides go
 * through the same normalisation. A track without a language only matches a format without one.
 */
internal fun sameLanguage(mediaSource: String?, container: String?): Boolean =
	mediaSource?.let(Util::normalizeLanguageCode) == container?.let(Util::normalizeLanguageCode)

/**
 * The position in [formats] of the one format that describes [track], or null when they cannot be
 * matched up with certainty.
 *
 * Language first, then each discriminator in turn for as long as more than one candidate is left. A
 * discriminator that rules every candidate out has failed to tell them apart rather than proved the
 * track absent, so it leaves the candidates as they were. Anything still ambiguous at the end is
 * null: playing a track nobody asked for is worse than keeping the default.
 */
internal fun <T : MediaStreamTrack> matchTrackFormat(
	track: T,
	formats: List<Format>,
	discriminators: List<TrackDiscriminator<T>>,
): Int? {
	var candidates = formats.indices.filter { sameLanguage(track.language, formats[it].language) }

	for (discriminator in discriminators) {
		if (candidates.size <= 1) break
		candidates = candidates
			.filter { discriminator(track, formats[it]) }
			.ifEmpty { candidates }
	}

	return candidates.singleOrNull()
}

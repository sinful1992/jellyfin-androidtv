package org.jellyfin.playback.media3.exoplayer

import android.content.Context
import android.util.Pair
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo

/**
 * A track selector that picks a surround track the audio output takes as a bitstream over one that
 * can only be decoded, when both are in the same language.
 *
 * Measured on a Bravia with no TrueHD passthrough and no multichannel PCM output: a TrueHD 7.1
 * track flagged default won over an AC3 5.1 track, was decoded, and reached the soundbar as 2.0.
 * Here the TrueHD track counts as unsupported for the automatic pick, so the AC3 track wins and
 * the soundbar gets 5.1. A track the user picks is an override and is not affected.
 */
@OptIn(UnstableApi::class)
class PassthroughFirstTrackSelector(private val context: Context) : DefaultTrackSelector(context) {
	override fun selectAudioTrack(
		mappedTrackInfo: MappedTrackInfo,
		rendererFormatSupports: Array<Array<IntArray>>,
		rendererMixedMimeTypeAdaptationSupports: IntArray,
		params: Parameters,
	): Pair<ExoTrackSelection.Definition, Int>? {
		val capabilities = AudioCapabilities.getCapabilities(context)
		val supports = demoteDecodeOnly(mappedTrackInfo, rendererFormatSupports) { format ->
			capabilities.isPassthroughPlaybackSupported(format)
		}
		return super.selectAudioTrack(mappedTrackInfo, supports, rendererMixedMimeTypeAdaptationSupports, params)
	}

	private fun demoteDecodeOnly(
		mappedTrackInfo: MappedTrackInfo,
		rendererFormatSupports: Array<Array<IntArray>>,
		canPassThrough: (Format) -> Boolean,
	): Array<Array<IntArray>> {
		val audioRenderers = (0 until mappedTrackInfo.rendererCount)
			.filter { mappedTrackInfo.getRendererType(it) == C.TRACK_TYPE_AUDIO }
		// Every audio renderer maps the same groups, so the first one lists the tracks.
		val groups = audioRenderers.firstOrNull()?.let(mappedTrackInfo::getTrackGroups) ?: return rendererFormatSupports
		val formats = (0 until groups.length).map { group ->
			(0 until groups[group].length).map { track -> groups[group].getFormat(track) }
		}

		val demoted = decodeOnlyTracks(formats, canPassThrough)
		if (demoted.isEmpty()) return rendererFormatSupports

		val supports = Array(rendererFormatSupports.size) { renderer ->
			Array(rendererFormatSupports[renderer].size) { group -> rendererFormatSupports[renderer][group].copyOf() }
		}
		for (renderer in audioRenderers) for ((group, track) in demoted) {
			val support = supports[renderer][group][track]
			supports[renderer][group][track] = RendererCapabilities.create(
				C.FORMAT_UNSUPPORTED_SUBTYPE,
				RendererCapabilities.getAdaptiveSupport(support),
				RendererCapabilities.getTunnelingSupport(support),
			)
		}
		return supports
	}

	internal companion object {
		private const val SURROUND_CHANNELS = 6

		// A track without a language and one marked undetermined count as the same language.
		private val Format.languageOrNull
			get() = language?.takeUnless { it.equals(C.LANGUAGE_UNDETERMINED, ignoreCase = true) }

		/**
		 * The (group, track) positions of tracks that can't be passed through while a surround track
		 * in the same language can.
		 */
		fun decodeOnlyTracks(groups: List<List<Format>>, canPassThrough: (Format) -> Boolean): Set<kotlin.Pair<Int, Int>> {
			val all = groups.flatMapIndexed { group, tracks -> tracks.mapIndexed { track, format -> Triple(group, track, format) } }
			val passthroughLanguages = all
				.filter { (_, _, format) -> format.channelCount >= SURROUND_CHANNELS && canPassThrough(format) }
				.map { (_, _, format) -> format.languageOrNull }
				.toSet()

			return all
				.filter { (_, _, format) -> format.languageOrNull in passthroughLanguages && !canPassThrough(format) }
				.map { (group, track, _) -> group to track }
				.toSet()
		}
	}
}

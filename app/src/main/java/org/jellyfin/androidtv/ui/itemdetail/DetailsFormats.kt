package org.jellyfin.androidtv.ui.itemdetail

import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType

/**
 * The premium formats of one version of a title, for the details page's meta line: 4K or 8K, the
 * HDR kind (Dolby Vision before HDR10, one tag only) and lossless or object audio (TrueHD, Atmos,
 * DTS-HD, DTS:X). Ordinary formats (1080p, SDR, AAC, AC3, EAC3, stereo, subtitles) are left out:
 * the line names only what makes this file worth noticing.
 *
 * [label] turns a string resource into its text, so this stays a plain function to test.
 */
fun detailsPremiumFormats(mediaSource: MediaSourceInfo?, label: (Int) -> String): List<String> {
	val streams = mediaSource?.mediaStreams ?: return emptyList()
	val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
	val audio = streams.defaultAudio(mediaSource.defaultAudioStreamIndex)

	return listOfNotNull(
		video?.let(::resolutionTag),
		video?.videoRangeType?.let { rangeTag(it) }?.let(label),
		audio?.let { audioTag(it) }?.let(label),
	)
}

private fun List<MediaStream>.defaultAudio(defaultIndex: Int?): MediaStream? {
	if (defaultIndex != null) {
		firstOrNull { it.index == defaultIndex && it.type == MediaStreamType.AUDIO }?.let { return it }
	}
	return firstOrNull { it.type == MediaStreamType.AUDIO }
}

private fun resolutionTag(video: MediaStream): String? {
	val width = video.width ?: 0
	val height = video.height ?: 0
	return when {
		width >= 7600 || height >= 4300 -> "8K"
		width >= 3800 || height >= 2000 -> "4K"
		else -> null
	}
}

private fun rangeTag(range: VideoRangeType): Int? = when (range) {
	VideoRangeType.DOVI_WITH_EL,
	VideoRangeType.DOVI_WITH_ELHDR10_PLUS -> R.string.dolby_vision_with_el
	VideoRangeType.DOVI,
	VideoRangeType.DOVI_WITH_HDR10,
	VideoRangeType.DOVI_WITH_HLG,
	VideoRangeType.DOVI_WITH_SDR,
	VideoRangeType.DOVI_WITH_HDR10_PLUS,
	VideoRangeType.DOVI_INVALID -> R.string.dolby_vision
	VideoRangeType.HDR10_PLUS -> R.string.hdr10_plus
	VideoRangeType.HDR10 -> R.string.hdr10
	VideoRangeType.HLG -> R.string.hlg
	else -> null
}

private fun audioTag(audio: MediaStream): Int? {
	val profile = audio.profile.orEmpty()
	val codec = audio.codec?.uppercase()
	return when {
		profile.contains("Dolby Atmos", ignoreCase = true) -> when (codec) {
			"TRUEHD" -> R.string.truehd_atmos
			"EAC3" -> R.string.eac3_atmos
			else -> R.string.dolby_atmos
		}
		profile.contains("DTS:X", ignoreCase = true) -> R.string.dts_x
		profile.contains("DTS-HD", ignoreCase = true) || profile.contains("DTS:HD", ignoreCase = true) -> R.string.dts_hd
		codec == "TRUEHD" -> R.string.truehd
		else -> null
	}
}

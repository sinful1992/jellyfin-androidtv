package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.MediaStreamVideoTrack
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType

fun MediaInfo.getMediaStreamContainer() = MediaStreamContainer(
	format = requireNotNull(mediaSource.container)
)

fun MediaInfo.getTracks() =
	mediaSource.mediaStreams
		.orEmpty()
		.mapNotNull(MediaStream::getMediaStreamTrack)

fun MediaStream.getMediaStreamTrack() = when (type) {
	MediaStreamType.AUDIO -> getAudioTrack(this)
	MediaStreamType.VIDEO -> getVideoTrack(this)
	MediaStreamType.SUBTITLE -> getSubtitleTrack(this)

	// Ignore other track types
	MediaStreamType.EMBEDDED_IMAGE,
	MediaStreamType.DATA,
	MediaStreamType.LYRIC -> null
}

private fun getAudioTrack(stream: MediaStream) = MediaStreamAudioTrack(
	index = stream.index,
	codec = requireNotNull(stream.codec),
	title = stream.displayTitle ?: stream.title,
	language = stream.language,
	isDefault = stream.isDefault,
	bitrate = stream.bitRate ?: 0,
	channels = stream.channels ?: 1,
	sampleRate = stream.sampleRate ?: 0,
)

private fun getVideoTrack(stream: MediaStream) = MediaStreamVideoTrack(
	index = stream.index,
	codec = requireNotNull(stream.codec),
	title = stream.displayTitle ?: stream.title,
	language = stream.language,
	isDefault = stream.isDefault,
	bitrate = stream.bitRate ?: 0,
	width = stream.width ?: 0,
	height = stream.height ?: 0,
	videoRange = stream.videoRangeType.name,
)

// External subtitles are delivered as a separate file and can have no codec set, so unlike audio
// and video an absent codec is not an error here.
private fun getSubtitleTrack(stream: MediaStream) = MediaStreamSubtitleTrack(
	index = stream.index,
	codec = stream.codec.orEmpty(),
	title = stream.displayTitle ?: stream.title,
	language = stream.language,
	isDefault = stream.isDefault,
	isForced = stream.isForced,
	isExternal = stream.isExternal,
)

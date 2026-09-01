package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.queue.QueueEntry

interface MediaStream {
	val identifier: String
	val conversionMethod: MediaConversionMethod
	val container: MediaStreamContainer
	val tracks: Collection<MediaStreamTrack>
}

data class BasicMediaStream(
	override val identifier: String,
	override val conversionMethod: MediaConversionMethod,
	override val container: MediaStreamContainer,
	override val tracks: Collection<MediaStreamTrack>,
) : MediaStream {
	fun toPlayableMediaStream(
		queueEntry: QueueEntry,
		url: String,
	) = PlayableMediaStream(
		identifier = identifier,
		conversionMethod = conversionMethod,
		container = container,
		tracks = tracks,
		queueEntry = queueEntry,
		url = url,
	)
}

data class PlayableMediaStream(
	override val identifier: String,
	override val conversionMethod: MediaConversionMethod,
	override val container: MediaStreamContainer,
	override val tracks: Collection<MediaStreamTrack>,
	val queueEntry: QueueEntry,
	val url: String,
) : MediaStream

data class MediaStreamContainer(
	val format: String,
)

sealed interface MediaStreamTrack {
	/**
	 * Index of this track within the media source it belongs to. This is the value the server
	 * expects when requesting a specific audio or subtitle track.
	 */
	val index: Int
	val codec: String
	val title: String?
	val language: String?
	val isDefault: Boolean
}

data class MediaStreamAudioTrack(
	override val index: Int,
	override val codec: String,
	override val title: String? = null,
	override val language: String? = null,
	override val isDefault: Boolean = false,
	val bitrate: Int,
	val channels: Int,
	val sampleRate: Int,
) : MediaStreamTrack

data class MediaStreamVideoTrack(
	override val index: Int,
	override val codec: String,
	override val title: String? = null,
	override val language: String? = null,
	override val isDefault: Boolean = false,
	val bitrate: Int,
	val width: Int,
	val height: Int,
	val videoRange: String?,
) : MediaStreamTrack

data class MediaStreamSubtitleTrack(
	override val index: Int,
	override val codec: String,
	override val title: String? = null,
	override val language: String? = null,
	override val isDefault: Boolean = false,
	/**
	 * Whether the server considers this track forced, meaning it should be shown even when the
	 * user did not ask for subtitles.
	 */
	val isForced: Boolean = false,
	/**
	 * Whether this track lives outside the media container. External tracks cannot be selected by
	 * the player alone and always require the stream to be resolved again.
	 */
	val isExternal: Boolean = false,
) : MediaStreamTrack {
	companion object {
		/**
		 * Index used to explicitly request no subtitles at all, matching the value the server
		 * expects. Distinct from a null selection, which means "use the server default".
		 */
		const val INDEX_NONE = -1
	}
}

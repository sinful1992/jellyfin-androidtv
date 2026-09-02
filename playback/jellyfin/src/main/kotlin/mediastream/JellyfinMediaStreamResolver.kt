package org.jellyfin.playback.jellyfin.mediastream

import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.preferredAudioLanguage
import org.jellyfin.playback.core.mediastream.selectedAudioStreamIndex
import org.jellyfin.playback.core.mediastream.selectedSubtitleStreamIndex
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlaybackInfoDto

class JellyfinMediaStreamResolver(
	private val api: ApiClient,
	private val deviceProfileBuilder: () -> DeviceProfile,
) : MediaStreamResolver {
	companion object {
		private val supportedMediaTypes = arrayOf(MediaType.VIDEO, MediaType.AUDIO)
	}

	override suspend fun getStream(queueEntry: QueueEntry): PlayableMediaStream? {
		val baseItem = queueEntry.baseItem
		if (baseItem == null || !supportedMediaTypes.contains(baseItem.mediaType)) return null

		// An explicit choice for this entry wins; otherwise fall back to the language the user
		// picked on an earlier entry, which has to be resolved to an index for this item.
		val audioStreamIndex = queueEntry.selectedAudioStreamIndex
			?: queueEntry.preferredAudioLanguage?.let { language ->
				baseItem.getAudioStreamIndex(queueEntry.mediaSourceId, language)
			}

		val mediaInfo = getPlaybackInfo(
			item = baseItem,
			mediaSourceId = queueEntry.mediaSourceId,
			audioStreamIndex = audioStreamIndex,
			subtitleStreamIndex = queueEntry.selectedSubtitleStreamIndex,
		)

		return when {
			// Direct play video
			mediaInfo.mediaSource.supportsDirectPlay && baseItem.mediaType == MediaType.VIDEO -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.None,
				url = api.videosApi.getVideoStreamUrl(
					itemId = baseItem.id,
					container = mediaInfo.mediaSource.container,
					mediaSourceId = mediaInfo.mediaSource.id,
					static = true,
					tag = mediaInfo.mediaSource.eTag,
					liveStreamId = mediaInfo.mediaSource.liveStreamId,
				)
			)

			// Direct play audio
			mediaInfo.mediaSource.supportsDirectPlay && baseItem.mediaType == MediaType.AUDIO -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.None,
				url = api.audioApi.getAudioStreamUrl(
					itemId = baseItem.id,
					container = mediaInfo.mediaSource.container,
					mediaSourceId = mediaInfo.mediaSource.id,
					static = true,
					tag = mediaInfo.mediaSource.eTag,
					liveStreamId = mediaInfo.mediaSource.liveStreamId,
				)
			)

			// Remux (direct stream)
			mediaInfo.mediaSource.supportsDirectStream && mediaInfo.mediaSource.transcodingUrl != null -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.Remux,
				url = api.createUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl), ignorePathParameters = true)
			)

			// Transcode
			mediaInfo.mediaSource.supportsTranscoding && mediaInfo.mediaSource.transcodingUrl != null -> mediaInfo.toStream(
				queueEntry = queueEntry,
				conversionMethod = MediaConversionMethod.Transcode,
				url = api.createUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl), ignorePathParameters = true)
			)

			// No compatible stream found
			else -> null
		}
	}

	/**
	 * The index of the first audio stream in [language], within the media source that will be
	 * played. Null when the item has no such stream, which leaves the server default in place.
	 *
	 * The item has to have been fetched with the media source and media stream fields for this to
	 * find anything; the source is picked the same way [getPlaybackInfo] picks it.
	 */
	private fun BaseItemDto.getAudioStreamIndex(mediaSourceId: String?, language: String) = mediaSources
		.orEmpty()
		.filter { it.protocol == MediaProtocol.FILE && !it.isRemote }
		.firstOrNull { mediaSourceId == null || it.id == mediaSourceId }
		?.mediaStreams
		?.firstOrNull { it.type == MediaStreamType.AUDIO && it.language == language }
		?.index

	private suspend fun getPlaybackInfo(
		item: BaseItemDto,
		mediaSourceId: String? = null,
		audioStreamIndex: Int? = null,
		subtitleStreamIndex: Int? = null,
	): MediaInfo {
		val profile = deviceProfileBuilder()
		val response by api.mediaInfoApi.getPostedPlaybackInfo(
			itemId = item.id,
			data = PlaybackInfoDto(
				mediaSourceId = mediaSourceId,
				deviceProfile = profile,
				// Null means no explicit choice was made, letting the server pick its default.
				audioStreamIndex = audioStreamIndex,
				subtitleStreamIndex = subtitleStreamIndex,
				enableDirectPlay = true,
				enableDirectStream = true,
				enableTranscoding = true,
				allowVideoStreamCopy = true,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = false,
			)
		)

		if (response.errorCode != null) {
			error("Failed to get media info for item ${item.id} source ${mediaSourceId}: ${response.errorCode}")
		}

		val mediaSource = response.mediaSources
			// Filter out invalid streams (like strm files)
			.filter { it.protocol == MediaProtocol.FILE && !it.isRemote }
			// Select first media source
			.firstOrNull { mediaSourceId == null || it.id == mediaSourceId }

		requireNotNull(mediaSource) {
			"Failed to get media info for item ${item.id} source ${mediaSourceId}: media source missing in response"
		}

		return MediaInfo(
			playSessionId = response.playSessionId.orEmpty(),
			mediaSource = mediaSource
		)
	}

	private fun MediaInfo.toStream(
		queueEntry: QueueEntry,
		conversionMethod: MediaConversionMethod,
		url: String,
	) = PlayableMediaStream(
		identifier = playSessionId,
		conversionMethod = conversionMethod,
		container = getMediaStreamContainer(),
		tracks = getTracks(),
		queueEntry = queueEntry,
		url = url,
	)
}

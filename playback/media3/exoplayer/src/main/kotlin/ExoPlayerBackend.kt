package org.jellyfin.playback.media3.exoplayer

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.core.content.getSystemService
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView
import org.jellyfin.playback.core.backend.BasePlayerBackend
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.MediaStreamTrack
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediatype.MediaType
import org.jellyfin.playback.core.mediastream.mediatype.mediaType
import org.jellyfin.playback.core.mediastream.normalizationGain
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import org.jellyfin.playback.media3.exoplayer.mapping.getFfmpegAudioMimeType
import org.jellyfin.playback.media3.exoplayer.mapping.getFfmpegSubtitleMimeType
import org.jellyfin.playback.media3.exoplayer.support.getPlaySupportReport
import org.jellyfin.playback.media3.exoplayer.support.toFormats
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(UnstableApi::class)
class ExoPlayerBackend(
	private val context: Context,
	private val exoPlayerOptions: ExoPlayerOptions,
) : BasePlayerBackend() {
	companion object {
		const val TS_SEARCH_BYTES_LM = TsExtractor.TS_PACKET_SIZE * 1800
		const val TS_SEARCH_BYTES_HM = TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
		const val MEDIA_ITEM_COUNT_MAX = 10
	}

	private var currentStream: PlayableMediaStream? = null
	private var subtitleView: SubtitleView? = null
	private val audioPipeline = ExoPlayerAudioPipeline()
	private val audioAttributeState = AudioAttributeState()
	private val timedEventState = TimedEventState()
	private var lastKnownDuration: Duration? = null

	/**
	 * How long playback may sit in [Player.STATE_BUFFERING] while still being reported as playing.
	 *
	 * Buffering with the intent to play is normally a moment of waiting, but a renderer that never
	 * recovers stays in that state forever, and reporting it as playing leaves a frozen picture the
	 * controls insist is playing. Past this point the truth is more useful than the smoothing.
	 *
	 * Only counted once the stream has produced a picture, so this is the bound on a stall rather
	 * than on opening a stream, which legitimately takes longer. Still generous: rebuffering mid
	 * playback is normal, and calling that paused brings back the flashing pause icon this
	 * smoothing exists to prevent.
	 */
	private val stallTimeout = 15.seconds

	private val stallHandler by lazy { Handler(exoPlayer.applicationLooper) }

	/**
	 * Whether the stream being played has produced a picture yet.
	 *
	 * Opening a stream can wait far longer than a stall mid-playback ever should: a large direct
	 * play took sixteen seconds to hand over its first frame on the hardware this was measured on,
	 * which any bound tight enough to be useful later would have called stalled. Nothing is stuck
	 * until something has run.
	 */
	private var hasPlayedCurrentStream = false

	private val reportStalled = Runnable {
		Timber.w("Still buffering after $stallTimeout with playback requested, reporting it as paused")
		listener?.onPlayStateChange(PlayState.PAUSED)
	}

	private val assHandler by lazy {
		AssHandler(AssRenderType.OVERLAY_OPEN_GL)
	}

	private val exoPlayer by lazy {
		val dataSourceFactory = DefaultDataSource.Factory(
			context,
			exoPlayerOptions.baseDataSourceFactory,
		)
		val extractorsFactory = DefaultExtractorsFactory().apply {
			val isLowRamDevice = context.getSystemService<ActivityManager>()?.isLowRamDevice == true
			setTsExtractorTimestampSearchBytes(
				when (isLowRamDevice) {
					true -> TS_SEARCH_BYTES_LM
					false -> TS_SEARCH_BYTES_HM
				}
			)
			setConstantBitrateSeekingEnabled(true)
			setConstantBitrateSeekingAlwaysEnabled(true)
		}

		val mediaSourceFactory = if (exoPlayerOptions.enableLibass) {
			val assSubtitleParserFactory = AssSubtitleParserFactory(assHandler)
			val assExtractorsFactory = extractorsFactory.withAssMkvSupport(assSubtitleParserFactory, assHandler)
			DefaultMediaSourceFactory(dataSourceFactory, assExtractorsFactory).apply {
				setSubtitleParserFactory(assSubtitleParserFactory)
			}
		} else DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

		val renderersFactory = DefaultRenderersFactory(context).apply {
			setEnableDecoderFallback(true)
			setExtensionRendererMode(
				when (exoPlayerOptions.preferFfmpeg) {
					true -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
					false -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
				}
			)
		}.let { renderersFactory ->
			if (exoPlayerOptions.enableLibass) AssRenderersFactory(assHandler, renderersFactory)
			else renderersFactory
		}

		val loadControl = DefaultLoadControl.Builder()
			.setBufferDurationsMs(
				exoPlayerOptions.minBufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
				exoPlayerOptions.maxBufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
				exoPlayerOptions.bufferForPlaybackDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
				exoPlayerOptions.bufferForPlaybackAfterRebufferDuration?.inWholeMilliseconds?.toInt() ?: DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
			)
			.build()

		ExoPlayer.Builder(context)
			.setLoadControl(loadControl)
			.setRenderersFactory(renderersFactory)
			.setTrackSelector(DefaultTrackSelector(context).apply {
				setParameters(buildUponParameters().apply {
					setAudioOffloadPreferences(
						TrackSelectionParameters.AudioOffloadPreferences.DEFAULT.buildUpon().apply {
							setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
						}.build()
					)
					setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
				})
			})
			.setMediaSourceFactory(mediaSourceFactory)
			.setPauseAtEndOfMediaItems(true)
			.build()
			.also { player ->
				player.addListener(PlayerListener())

				if (exoPlayerOptions.enableDebugLogging) {
					player.addAnalyticsListener(EventLogger())
				}

				if (exoPlayerOptions.enableLibass) {
					assHandler.init(player)
				}
			}
	}

	inner class PlayerListener : Player.Listener {
		override fun onIsPlayingChanged(isPlaying: Boolean) {
			// Every path below decides afresh whether playback is stalled, so drop the pending verdict
			// from the previous one before arming a new one.
			stallHandler.removeCallbacks(reportStalled)
			if (isPlaying) hasPlayedCurrentStream = true

			val state = when {
				isPlaying -> PlayState.PLAYING

				exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED -> PlayState.STOPPED

				// Waiting for data is not the same as being stopped, and the difference shows:
				// a pause icon is flashed over the picture and the controls come up and stay,
				// every time playback has to buffer. Loading the next entry buffers, which is
				// why taking the next up card's offer looked like it paused first.
				//
				// The intent to play is what separates the two. Anything that really did stop
				// playback clears it, so a genuine pause and a loss of audio focus both still
				// report as paused.
				exoPlayer.playbackState == Player.STATE_BUFFERING && exoPlayer.playWhenReady -> {
					if (hasPlayedCurrentStream) stallHandler.postDelayed(reportStalled, stallTimeout.inWholeMilliseconds)
					PlayState.PLAYING
				}

				else -> PlayState.PAUSED
			}
			listener?.onPlayStateChange(state)
		}

		override fun onPlayerError(error: PlaybackException) {
			stallHandler.removeCallbacks(reportStalled)
			listener?.onPlayStateChange(PlayState.ERROR)
		}

		override fun onVideoSizeChanged(size: VideoSize) {
			if (size != VideoSize.UNKNOWN) {
				listener?.onVideoSizeChange(size.width, size.height)
			}
		}

		override fun onCues(cueGroup: CueGroup) {
			subtitleView?.setCues(cueGroup.cues)
		}

		override fun onPlaybackStateChanged(playbackState: Int) {
			onIsPlayingChanged(exoPlayer.isPlaying)
		}

		override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
			if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
				listener?.onMediaStreamEnd(requireNotNull(currentStream))
			}
		}

		override fun onAudioSessionIdChanged(audioSessionId: Int) {
			audioPipeline.setAudioSessionId(audioSessionId)
		}

		override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
			val queueEntry = mediaItem?.localConfiguration?.tag as? QueueEntry
			audioPipeline.normalizationGain = queueEntry?.normalizationGain
		}

		override fun onTimelineChanged(timeline: Timeline, reason: Int) {
			val duration = exoPlayer.duration.takeUnless { it == C.TIME_UNSET }?.milliseconds
			if (duration == lastKnownDuration) return
			timedEventState.onDurationChange(exoPlayer, duration)
			lastKnownDuration = duration
		}

		override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
			timedEventState.onSeek(oldPosition.positionMs.milliseconds, newPosition.positionMs.milliseconds, lastKnownDuration ?: Duration.ZERO)
		}
	}

	override fun supportsStream(
		stream: MediaStream
	): PlaySupportReport = exoPlayer.getPlaySupportReport(stream.toFormats())

	override fun setSurfaceView(surfaceView: PlayerSurfaceView?) {
		exoPlayer.setVideoSurfaceView(surfaceView?.surface)
	}

	override fun setSubtitleView(surfaceView: PlayerSubtitleView?) {
		if (surfaceView != null) {
			if (subtitleView == null) {
				subtitleView = SubtitleView(surfaceView.context).apply {
					if (exoPlayerOptions.enableLibass) {
						addView(AssSubtitleView(surfaceView.context, assHandler))
					}
				}
			}

			surfaceView.addView(subtitleView)
		} else {
			(subtitleView?.parent as? ViewGroup)?.removeView(subtitleView)
			subtitleView = null
		}
	}

	override fun prepareItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream)
		val mediaItem = MediaItem.Builder().apply {
			setTag(item)
			setMediaId(stream.hashCode().toString())
			setUri(stream.url)
		}.build()

		// Remove any excessive items from the start
		while (exoPlayer.mediaItemCount > MEDIA_ITEM_COUNT_MAX - 1) exoPlayer.removeMediaItem(0)

		// Add new item to the end of the media item list
		exoPlayer.addMediaItem(mediaItem)

		// Instruct exoplayer to prepare
		exoPlayer.prepare()
	}

	override fun playItem(item: QueueEntry) {
		val stream = requireNotNull(item.mediaStream)
		if (currentStream == stream) return

		currentStream = stream
		hasPlayedCurrentStream = false

		// Track overrides belong to the stream they were chosen for, and the player keeps them
		// across items, so drop them before playing a different one.
		exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
			.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
			.clearOverridesOfType(C.TRACK_TYPE_TEXT)
			.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
			.build()

		var preparedItemIndex = (0 until exoPlayer.mediaItemCount).firstOrNull { index ->
			exoPlayer.getMediaItemAt(index).mediaId == stream.hashCode().toString()
		}

		// Prepare the item now if it doesn't exist yet
		if (preparedItemIndex == null) {
			prepareItem(item)
			preparedItemIndex = exoPlayer.mediaItemCount - 1
		}

		// Seek to prepared media item
		when (preparedItemIndex) {
			exoPlayer.currentMediaItemIndex - 1 -> exoPlayer.seekToPreviousMediaItem()
			exoPlayer.currentMediaItemIndex + 1 -> exoPlayer.seekToNextMediaItem()
			exoPlayer.currentMediaItemIndex -> Unit
			else -> exoPlayer.seekTo(preparedItemIndex, 0)
		}

		// Update audio attributes
		val contentType = when (item.mediaType) {
			MediaType.Video -> C.AUDIO_CONTENT_TYPE_MOVIE
			MediaType.Audio -> C.AUDIO_CONTENT_TYPE_MUSIC
			MediaType.Unknown -> C.AUDIO_CONTENT_TYPE_UNKNOWN
		}

		audioAttributeState.updateAudioAttributes(
			builder = {
				setContentType(contentType)
				setUsage(C.USAGE_MEDIA)
			},
			onChange = { audioAttributes ->
				exoPlayer.setAudioAttributes(audioAttributes, true)
			}
		)

		// Enjoy!
		Timber.i("Playing ${item.mediaStream?.url}")
		exoPlayer.play()
	}

	override fun selectAudioTrack(index: Int): Boolean {
		val group = findTrackGroup<MediaStreamAudioTrack>(C.TRACK_TYPE_AUDIO, index) { track, format ->
			format.sampleMimeType == getFfmpegAudioMimeType(track.codec)
		} ?: return false

		exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
			.setOverrideForType(TrackSelectionOverride(group, 0))
			.build()

		Timber.i("Selected audio track $index client side (${group.getFormat(0).language}, ${group.getFormat(0).sampleMimeType})")
		return true
	}

	override fun selectSubtitleTrack(index: Int): Boolean {
		if (index == MediaStreamSubtitleTrack.INDEX_NONE) {
			// Subtitles burned into a transcode cannot be turned off by selecting tracks, and that
			// shows up the same way any other rewritten stream does.
			if (!containerMatchesMediaSource<MediaStreamSubtitleTrack>(C.TRACK_TYPE_TEXT)) return false

			exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
				.clearOverridesOfType(C.TRACK_TYPE_TEXT)
				.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
				.build()

			Timber.i("Disabled subtitles client side")
			return true
		}

		val group = findTrackGroup<MediaStreamSubtitleTrack>(C.TRACK_TYPE_TEXT, index) { track, format ->
			format.sampleMimeType == getFfmpegSubtitleMimeType(track.codec)
		} ?: return false

		exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
			.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
			.setOverrideForType(TrackSelectionOverride(group, 0))
			.build()

		Timber.i("Selected subtitle track $index client side (${group.getFormat(0).language})")
		return true
	}

	/**
	 * Whether the tracks of [trackType] in the stream being played line up with the ones the media
	 * source describes, meaning a track can be picked without resolving the stream again.
	 *
	 * They line up while both sides describe the same tracks: a transcode collapses them to one,
	 * and an external subtitle exists in the media source but not in the container. Both show up as
	 * a differing count, which is the signal to leave the change to the caller instead of guessing.
	 */
	private inline fun <reified T : MediaStreamTrack> containerMatchesMediaSource(trackType: Int): Boolean {
		val tracks = currentStream?.tracks.orEmpty().filterIsInstance<T>().size
		val groups = exoPlayer.currentTracks.groups.count { it.type == trackType }

		if (groups != tracks) {
			Timber.d("Cannot select tracks of type $trackType client side: $tracks in the media source but $groups in the container")
			return false
		}

		return true
	}

	/**
	 * Whether the media source and the container describe the same track, matched on what both
	 * sides actually state rather than on the order they happen to list things in.
	 *
	 * The media source spells languages out in three letters where the container has normalised
	 * them to two, so both sides go through the same normalisation before they are compared. A
	 * track without a language only matches a group without one.
	 */
	private fun sameLanguage(mediaSource: String?, container: String?): Boolean =
		mediaSource?.let(Util::normalizeLanguageCode) == container?.let(Util::normalizeLanguageCode)

	/**
	 * Find the track group holding the track the media source lists at [index], or null when the
	 * two cannot be matched up with certainty.
	 *
	 * Matching is by identity, not by position. The container was assumed to expose its tracks in
	 * the order the media source lists them, and it does not: resolving the stream again can hand
	 * back a differently ordered list, so the same index selected a different language depending on
	 * what had happened earlier in the session.
	 *
	 * Language alone settles most files. Where several tracks share a language the codec decides
	 * between them, and anything still ambiguous returns null so the caller resolves the stream
	 * again rather than playing a track nobody asked for.
	 */
	private inline fun <reified T : MediaStreamTrack> findTrackGroup(
		trackType: Int,
		index: Int,
		matchesFormat: (track: T, format: Format) -> Boolean,
	): TrackGroup? {
		if (!containerMatchesMediaSource<T>(trackType)) return null

		val track = currentStream?.tracks.orEmpty()
			.filterIsInstance<T>()
			.firstOrNull { it.index == index }

		if (track == null) {
			Timber.d("Cannot select track $index client side: the media source does not list it")
			return null
		}

		val groups = exoPlayer.currentTracks.groups.filter { it.type == trackType }
		val sharingLanguage = groups.filter { sameLanguage(track.language, it.getTrackFormat(0).language) }

		val group = sharingLanguage.singleOrNull()
			?: sharingLanguage.singleOrNull { matchesFormat(track, it.getTrackFormat(0)) }

		if (group == null) {
			Timber.d(
				"Cannot select track $index client side: ${sharingLanguage.size} of ${groups.size} " +
					"groups in the container match language ${track.language} and codec ${track.codec}"
			)
			return null
		}

		if (!group.isSupported) {
			Timber.d("Cannot select track $index client side: the player cannot decode ${group.getTrackFormat(0).sampleMimeType}")
			return null
		}

		return group.mediaTrackGroup
	}

	override fun play() {
		// If the item has ended, revert first so the item will start over again
		if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0)
		exoPlayer.play()
	}

	override fun pause() {
		exoPlayer.pause()
	}

	override fun stop() {
		stallHandler.removeCallbacks(reportStalled)
		exoPlayer.stop()
		currentStream = null
		hasPlayedCurrentStream = false
	}

	override fun seekTo(position: Duration) {
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !exoPlayer.isCurrentMediaItemSeekable) {
			Timber.w("Trying to seek but ExoPlayer doesn't support it for the current item")
		}

		exoPlayer.seekTo(position.inWholeMilliseconds)
	}

	override fun setScrubbing(scrubbing: Boolean) {
		exoPlayer.isScrubbingModeEnabled = scrubbing
	}

	override fun setSpeed(speed: Float) {
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) {
			Timber.w("Trying to change speed but ExoPlayer doesn't support it for the current item")
		}

		exoPlayer.setPlaybackSpeed(speed)
	}

	override fun getPositionInfo(): PositionInfo = PositionInfo(
		active = exoPlayer.currentPosition.milliseconds,
		buffer = exoPlayer.bufferedPosition.milliseconds,
		duration = lastKnownDuration ?: Duration.ZERO,
	)

	override fun setTimedEvents(timedEvents: List<TimedEvent>) {
		timedEventState.setTimedEvents(exoPlayer, timedEvents)
	}
}

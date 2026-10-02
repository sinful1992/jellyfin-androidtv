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
import androidx.media3.common.Tracks
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
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.MediaStreamTrack
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.mediatype.MediaType
import org.jellyfin.playback.core.mediastream.mediatype.mediaType
import org.jellyfin.playback.core.mediastream.normalizationGain
import org.jellyfin.playback.core.mediastream.preferredAudioLanguage
import org.jellyfin.playback.core.mediastream.selectedAudioStreamIndex
import org.jellyfin.playback.core.mediastream.selectedSubtitleStreamIndex
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

	/**
	 * Where playback had reached when it was stopped, reported in place of the player's own
	 * position until playback starts again. See [stop].
	 */
	private var stoppedPosition: Duration? = null

	/**
	 * The stream the player last left, by identifier, and where it had reached when it was left.
	 *
	 * Taken at every point the player stops answering for a stream: the end of an entry, a stream
	 * being replaced by another, and a stop. Kept by identifier rather than by stream so nothing
	 * of the entry that owned it is held on to afterwards.
	 *
	 * @see getFinalPosition
	 */
	private var endedStream: Pair<String, Duration>? = null

	/**
	 * Tracks the entry had chosen, to apply to [stream] once the player knows its tracks. Applied
	 * once, through the backend's own selection only: a choice that cannot be made client side
	 * leaves the default track rather than resolving the stream again, which would come back here.
	 */
	private class PendingTrackSelection(
		val stream: PlayableMediaStream,
		val audioIndex: Int?,
		val subtitleIndex: Int?,
	)

	private var pendingTrackSelection: PendingTrackSelection? = null

	private fun applyPendingTrackSelection(tracks: Tracks) {
		val pending = pendingTrackSelection ?: return
		if (pending.stream !== currentStream || (pending.audioIndex == null && pending.subtitleIndex == null)) {
			pendingTrackSelection = null
			return
		}

		// Also reached during the switch, with the previous item's tracks or none at all.
		if (exoPlayer.currentMediaItem?.mediaId != pending.stream.hashCode().toString()) return
		if (tracks.groups.isEmpty()) return

		// Cleared first: applying an override calls back into this.
		pendingTrackSelection = null
		pending.audioIndex?.let { selectAudioTrack(it) }
		pending.subtitleIndex?.let { selectSubtitleTrack(it) }
	}

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
			if (isPlaying) {
				hasPlayedCurrentStream = true
				// Playing again, so the player's own position is the truthful one once more.
				stoppedPosition = null

				// A stream being played again is not one the player has left, and repeating an
				// entry does exactly that: the end is read as it ends, and playback then carries on
				// in the same stream. Left in place, the next report about it would say it ended
				// however long ago rather than where the viewer is now.
				if (endedStream?.first == currentStream?.identifier) endedStream = null
			}

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
				val stream = requireNotNull(currentStream)

				// Read before anything is told the entry ended, because what they do about it is
				// advance the queue, and the player's position answers for the next entry from the
				// moment it does.
				endedStream = stream.identifier to exoPlayer.currentPosition.milliseconds

				listener?.onMediaStreamEnd(stream)
			}
		}

		override fun onTracksChanged(tracks: Tracks) = applyPendingTrackSelection(tracks)

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

	override fun playItem(item: QueueEntry, startPosition: Duration) {
		val stream = requireNotNull(item.mediaStream)
		if (currentStream == stream) return

		// The stream being replaced stops being the one the player's position answers for as soon
		// as the seek below happens, so take where it reached while it still is. Skipped when its
		// end was already read, which is the more accurate of the two.
		currentStream?.let { previous ->
			if (endedStream?.first != previous.identifier) {
				endedStream = previous.identifier to exoPlayer.currentPosition.milliseconds
			}
		}

		currentStream = stream
		hasPlayedCurrentStream = false

		// Track overrides belong to the stream they were chosen for, and the player keeps them
		// across items, so drop them before playing a different one.
		exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
			.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
			.clearOverridesOfType(C.TRACK_TYPE_TEXT)
			.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
			// A direct-play URL carries no audio index, so the language carried over from an earlier
			// entry only reaches the player this way. Set on every item so a stale one never lingers.
			.setPreferredAudioLanguage(item.preferredAudioLanguage.takeIf { item.selectedAudioStreamIndex == null })
			.build()

		// Clearing the overrides also drops the tracks chosen for this entry when its stream was
		// resolved again (another track change, a profile change). Put them back once the new
		// stream's tracks are known.
		pendingTrackSelection = PendingTrackSelection(stream, item.selectedAudioStreamIndex, item.selectedSubtitleStreamIndex)

		var preparedItemIndex = (0 until exoPlayer.mediaItemCount).firstOrNull { index ->
			exoPlayer.getMediaItemAt(index).mediaId == stream.hashCode().toString()
		}

		// Prepare the item now if it doesn't exist yet
		if (preparedItemIndex == null) {
			prepareItem(item)
			preparedItemIndex = exoPlayer.mediaItemCount - 1
		}

		// Seek to prepared media item. A start position is applied here, in the same seek that
		// selects the item, so playback begins there instead of at zero. The adjacent-item shorthand
		// is skipped in that case because it cannot carry a position.
		if (startPosition > Duration.ZERO) {
			exoPlayer.seekTo(preparedItemIndex, startPosition.inWholeMilliseconds)
		} else when (preparedItemIndex) {
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

		// An item prepared ahead may already have its tracks, and then no change is announced.
		applyPendingTrackSelection(exoPlayer.currentTracks)

		// Enjoy!
		Timber.i("Playing ${item.mediaStream?.url}")
		exoPlayer.play()
	}

	override fun selectAudioTrack(index: Int): Boolean {
		val tracks = currentStream?.tracks.orEmpty().filterIsInstance<MediaStreamAudioTrack>()

		val group = findTrackGroup<MediaStreamAudioTrack>(
			trackType = C.TRACK_TYPE_AUDIO,
			index = index,
			mediaSourceTracks = tracks,
			discriminators = listOf(
				{ track, format -> format.sampleMimeType == getFfmpegAudioMimeType(track.codec) },
			),
		) ?: return false

		exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
			.setOverrideForType(TrackSelectionOverride(group, 0))
			.build()

		Timber.i("Selected audio track $index client side (${group.getFormat(0).language}, ${group.getFormat(0).sampleMimeType})")
		return true
	}

	override fun selectSubtitleTrack(index: Int): Boolean {
		// Only the tracks inside the container reach the player. The media source lists external
		// subtitles alongside them, and counting those against the container made every file that
		// has one refuse client-side selection - including turning subtitles off.
		val embedded = currentStream?.tracks.orEmpty()
			.filterIsInstance<MediaStreamSubtitleTrack>()
			.filterNot { it.isExternal }

		if (index == MediaStreamSubtitleTrack.INDEX_NONE) {
			if (!isDirectPlay()) return false
			if (!containerMatchesMediaSource(C.TRACK_TYPE_TEXT, embedded.size)) return false

			exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
				.clearOverridesOfType(C.TRACK_TYPE_TEXT)
				.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
				.build()

			Timber.i("Disabled subtitles client side")
			return true
		}

		// An external track is not in the container at all, so there is nothing to select. Say so
		// here rather than letting the matcher report it as an ambiguity it is not.
		val isExternal = currentStream?.tracks.orEmpty()
			.filterIsInstance<MediaStreamSubtitleTrack>()
			.any { it.index == index && it.isExternal }

		if (isExternal) {
			Timber.d("Cannot select subtitle track $index client side: it lives outside the container")
			return false
		}

		val group = findTrackGroup<MediaStreamSubtitleTrack>(
			trackType = C.TRACK_TYPE_TEXT,
			index = index,
			mediaSourceTracks = embedded,
			discriminators = listOf(
				{ track, format -> format.sampleMimeType == getFfmpegSubtitleMimeType(track.codec) },
				// Several tracks of one language is the normal case for subtitles - a forced
				// track beside a full one, or SDH beside plain - so the flags the container
				// carries are what tells them apart once the codec cannot.
				{ track, format -> format.hasSelectionFlag(C.SELECTION_FLAG_FORCED) == track.isForced },
				{ track, format -> format.hasSelectionFlag(C.SELECTION_FLAG_DEFAULT) == track.isDefault },
			),
		) ?: return false

		exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
			.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
			.setOverrideForType(TrackSelectionOverride(group, 0))
			.build()

		Timber.i("Selected subtitle track $index client side (${group.getFormat(0).language})")
		return true
	}

	/**
	 * Whether the container being played holds [mediaSourceTrackCount] tracks of [trackType],
	 * meaning a track can be picked without resolving the stream again.
	 *
	 * A differing count means the stream is not the one the media source describes - a transcode
	 * collapses the tracks to the single one it baked in - and is the signal to leave the change to
	 * the caller instead of guessing. The count passed in must only cover tracks that can reach the
	 * player, which for subtitles excludes the external ones.
	 */
	private fun containerMatchesMediaSource(trackType: Int, mediaSourceTrackCount: Int): Boolean {
		val groups = exoPlayer.currentTracks.groups.count { it.type == trackType }

		if (groups != mediaSourceTrackCount) {
			Timber.d(
				"Cannot select tracks of type $trackType client side: $mediaSourceTrackCount in the " +
					"media source but $groups in the container"
			)
			return false
		}

		return true
	}

	/**
	 * Only a direct-played file carries the tracks its media source lists. A remux or transcode
	 * holds what the server chose to put in it, which can include an external subtitle delivered
	 * in the HLS manifest, so a matching count there is a coincidence and not proof the groups
	 * line up. Those streams switch tracks by resolving again.
	 */
	private fun isDirectPlay(): Boolean {
		val method = currentStream?.conversionMethod
		if (method != MediaConversionMethod.None) {
			Timber.d("Cannot select tracks client side on a $method stream")
			return false
		}

		return true
	}

	/**
	 * Whether this format carries one of the container's selection flags, such as
	 * [C.SELECTION_FLAG_FORCED].
	 */
	private fun Format.hasSelectionFlag(flag: Int) = (selectionFlags and flag) != 0

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
	private fun <T : MediaStreamTrack> findTrackGroup(
		trackType: Int,
		index: Int,
		mediaSourceTracks: List<T>,
		discriminators: List<(track: T, format: Format) -> Boolean>,
	): TrackGroup? {
		if (!isDirectPlay()) return null
		if (!containerMatchesMediaSource(trackType, mediaSourceTracks.size)) return null

		val track = mediaSourceTracks.firstOrNull { it.index == index }

		if (track == null) {
			Timber.d("Cannot select track $index client side: the media source does not list it")
			return null
		}

		val groups = exoPlayer.currentTracks.groups.filter { it.type == trackType }

		// Language first, then each discriminator in turn for as long as more than one candidate
		// is left. A discriminator that rules every candidate out has failed to tell them apart
		// rather than proved the track absent, so it leaves the candidates as they were.
		var candidates = groups.filter { sameLanguage(track.language, it.getTrackFormat(0).language) }

		for (discriminator in discriminators) {
			if (candidates.size <= 1) break
			candidates = candidates
				.filter { discriminator(track, it.getTrackFormat(0)) }
				.ifEmpty { candidates }
		}

		val group = candidates.singleOrNull()

		if (group == null) {
			Timber.d(
				"Cannot select track $index client side: ${candidates.size} of ${groups.size} " +
					"groups in the container are still a match for language ${track.language} " +
					"and codec ${track.codec}"
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

		// An error leaves the player idle, and play() alone does nothing there. The controls offer
		// play as the action for a failed item — pressing it did nothing at all, which is a dead
		// end on exactly the titles most likely to fail, and leaves restarting playback from the
		// beginning as the only way out. Preparing again retries from the current position.
		if (exoPlayer.playbackState == Player.STATE_IDLE) {
			Timber.i("Play requested while idle, preparing again to retry")
			exoPlayer.prepare()
		}

		exoPlayer.play()
	}

	override fun pause() {
		exoPlayer.pause()
	}

	override fun stop() {
		stallHandler.removeCallbacks(reportStalled)

		// Read before stopping, so a stop cannot be what loses it. Everything that asks where the
		// viewer got to asks after the player has stopped: the media session, and the report that
		// tells the server where to resume from.
		//
		// This does NOT close the whole gap. Measured on a Chromecast: 795128 ms sampled a second
		// before leaving the player, 781371 ms reported afterwards even with this in place, and the
		// server stored the same 13:01 - so about fourteen seconds are already gone by the time
		// stop() is called, and the fallback happens earlier, most likely at the pause that
		// precedes it. Always behind, never ahead, so the cost is re-watching a few seconds rather
		// than losing them. Settling it needs the position logged at each transition in a debug
		// build.
		val position = exoPlayer.currentPosition.milliseconds
		stoppedPosition = position
		currentStream?.let { stream -> endedStream = stream.identifier to position }

		exoPlayer.stop()
		currentStream = null
		hasPlayedCurrentStream = false
	}

	override fun seekTo(position: Duration) {
		if (!exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !exoPlayer.isCurrentMediaItemSeekable) {
			Timber.w("Trying to seek but ExoPlayer doesn't support it for the current item")
		}

		// The seek is now where playback is, so a position remembered from a stop is stale.
		stoppedPosition = null

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

	override fun getFinalPosition(stream: MediaStream): Duration? =
		endedStream?.takeIf { (identifier, _) -> identifier == stream.identifier }?.second

	override fun getPositionInfo(): PositionInfo = PositionInfo(
		active = stoppedPosition ?: exoPlayer.currentPosition.milliseconds,
		buffer = exoPlayer.bufferedPosition.milliseconds,
		duration = lastKnownDuration ?: Duration.ZERO,
	)

	override fun setTimedEvents(timedEvents: List<TimedEvent>) {
		timedEventState.setTimedEvents(exoPlayer, timedEvents)
	}
}

package org.jellyfin.playback.core.backend

import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.support.PlaySupportReport
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.ui.PlayerSubtitleView
import org.jellyfin.playback.core.ui.PlayerSurfaceView
import kotlin.time.Duration

/**
 * Implementation for a media player backend. A backend is unaware of queues and can only play or
 * preload items.
 */
interface PlayerBackend {
	// Testing
	fun supportsStream(stream: MediaStream): PlaySupportReport

	// UI
	fun setSurfaceView(surfaceView: PlayerSurfaceView?)
	fun setSubtitleView(surfaceView: PlayerSubtitleView?)

	// Data retrieval

	fun setListener(eventListener: PlayerBackendEventListener?)
	fun getPositionInfo(): PositionInfo

	/**
	 * Where [stream] had reached when the player left it, or null when it is not the stream the
	 * player last left.
	 *
	 * [getPositionInfo] answers for whatever is being played now, which stops being an answer about
	 * a stream the moment the queue moves on: the player seeks into the next entry, and an entry
	 * watched to its end reads as having stopped at the beginning of the one after it. Anything
	 * reporting on a stream after playback has left it has to ask for it by name.
	 */
	fun getFinalPosition(stream: MediaStream): Duration? = null

	// Mutation

	fun prepareItem(item: QueueEntry)

	/**
	 * Play [item], beginning at [startPosition] rather than at its start. Applied as the item is
	 * prepared, so nothing of the opening is shown and no seek can be dropped for arriving before
	 * the timeline is seekable.
	 */
	fun playItem(item: QueueEntry, startPosition: Duration = Duration.ZERO)

	/**
	 * Play the audio track with the given media source [index] from the stream that is already
	 * playing. Returns false when the backend cannot do this, in which case the caller has to
	 * resolve the stream again to apply the change.
	 */
	fun selectAudioTrack(index: Int): Boolean = false

	/**
	 * Show the subtitle track with the given media source [index] from the stream that is already
	 * playing, or hide subtitles when given [MediaStreamSubtitleTrack.INDEX_NONE]. Returns false
	 * when the backend cannot do this, in which case the caller has to resolve the stream again to
	 * apply the change.
	 */
	fun selectSubtitleTrack(index: Int): Boolean = false

	fun play()
	fun pause()
	fun stop()

	fun seekTo(position: Duration)
	fun setScrubbing(scrubbing: Boolean)

	fun setSpeed(speed: Float)

	fun setTimedEvents(timedEvents: List<TimedEvent>)
}


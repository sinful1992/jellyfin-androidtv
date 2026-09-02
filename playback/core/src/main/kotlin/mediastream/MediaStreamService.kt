package org.jellyfin.playback.core.mediastream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.timedevent.addTimedEvent
import org.jellyfin.playback.core.timedevent.timedEvents
import timber.log.Timber
import kotlin.time.Duration

class MediaStreamService internal constructor(
	private val mediaStreamResolvers: Collection<MediaStreamResolver>,
	private val preloadDuration: Duration,
) : PlayerService() {
	private companion object {
		private const val TIMED_EVENT_PRELOAD = "MediaStreamServicePreloadNext"
	}

	override suspend fun onInitialize() {
		manager.queue.entry.onEach { entry ->
			Timber.d("Queue entry changed to $entry")

			if (entry == null) {
				val backend = requireNotNull(manager.backend)
				backend.stop()
			} else {
				playEntry(entry)
				entry.ensurePreloadTimedEvent()
			}
		}.launchIn(coroutineScope + Dispatchers.Main)
	}

	/**
	 * Apply the audio track the current entry has selected. The backend gets first refusal, because
	 * it can switch between the tracks of a stream it is already playing without interrupting it.
	 * Only when it cannot is the stream resolved again, which is what a transcode needs since the
	 * server bakes the chosen track into it.
	 *
	 * Runs on this service's scope rather than the caller's, so a caller that goes away while the
	 * change is in flight cannot cancel it.
	 */
	fun applyAudioTrackSelection(index: Int) = coroutineScope.launch(Dispatchers.Main) {
		if (manager.backend.selectAudioTrack(index)) return@launch
		reloadCurrentStream()
	}

	/**
	 * Apply the subtitle track the current entry has selected.
	 * @see applyAudioTrackSelection
	 */
	fun applySubtitleTrackSelection(index: Int) = coroutineScope.launch(Dispatchers.Main) {
		if (manager.backend.selectSubtitleTrack(index)) return@launch
		reloadCurrentStream()
	}

	/**
	 * Resolve the current stream again after something the resolvers read from outside the queue
	 * entry changed, such as the device profile the server is asked to match.
	 *
	 * @see applyAudioTrackSelection
	 */
	fun applyStreamOptionsChange() = coroutineScope.launch(Dispatchers.Main) {
		reloadCurrentStream()
	}

	/**
	 * Resolve the stream for the current entry again and resume where playback was, keeping the
	 * play state it had. Call this after changing something the resolvers read from the entry,
	 * such as the selected audio or subtitle track.
	 *
	 * Does nothing when there is no current entry. The current stream is only replaced once the
	 * new one resolves, so a failure leaves playback alone instead of stranding the entry without
	 * a stream.
	 */
	suspend fun reloadCurrentStream(keepPosition: Boolean = true) = withContext(Dispatchers.Main) {
		val entry = manager.queue.entry.value ?: return@withContext
		val backend = requireNotNull(manager.backend)

		val position = if (keepPosition) state.positionInfo.active else Duration.ZERO
		val wasPaused = state.playState.value == PlayState.PAUSED

		val stream = resolveMediaStream(entry)
		if (stream == null) {
			Timber.e("Unable to re-resolve stream for entry $entry, keeping the current one")
			return@withContext
		}

		entry.mediaStream = stream

		backend.playItem(entry)
		if (position > Duration.ZERO) backend.seekTo(position)
		if (wasPaused) backend.pause()
	}

	private suspend fun resolveMediaStream(entry: QueueEntry): PlayableMediaStream? =
		mediaStreamResolvers.firstNotNullOfOrNull { resolver ->
			runCatching {
				withContext(Dispatchers.IO) {
					resolver.getStream(entry)
				}
			}.onFailure {
				Timber.e(it, "Media stream resolver failed for $entry")
			}.getOrNull()
		}

	private suspend fun QueueEntry.ensureMediaStream(): Boolean {
		if (mediaStream == null) mediaStream = resolveMediaStream(this)

		return mediaStream != null
	}

	private fun QueueEntry.ensurePreloadTimedEvent() {
		if (preloadDuration <= Duration.ZERO) return

		val hasEvent = timedEvents?.any { it.key == TIMED_EVENT_PRELOAD } == true
		if (hasEvent) return

		addTimedEvent(
			TimedEvent.Block(
				key = TIMED_EVENT_PRELOAD,
				start = -preloadDuration,
				end = Duration.INFINITE,
				onActivate = { preloadNextEntry() }
			)
		)
	}

	private suspend fun playEntry(entry: QueueEntry) {
		val backend = requireNotNull(manager.backend)
		val hasMediaStream = entry.ensureMediaStream()

		if (hasMediaStream) {
			backend.playItem(entry)
		} else {
			Timber.e("Unable to resolve stream for entry $entry")

			// TODO: Somehow notify the user that we skipped an unplayable entry
			if (manager.queue.peekNext() != null) {
				manager.queue.next(usePlaybackOrder = true, useRepeatMode = false)
			} else {
				backend.stop()
			}
		}
	}

	private fun preloadNextEntry() = coroutineScope.launch(Dispatchers.Main) {
		// Peek into the next item to preload
		val nextItem = manager.queue.peekNext() ?: return@launch

		// Preload media stream information
		val hasMediaStream = nextItem.ensureMediaStream()

		if (hasMediaStream) {
			// Preload media in backend
			val backend = requireNotNull(manager.backend)
			backend.prepareItem(nextItem)
		}
	}
}

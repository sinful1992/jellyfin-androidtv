package org.jellyfin.playback.core.mediastream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.PlaybackEvent
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.core.queue.startPosition
import org.jellyfin.playback.core.timedevent.TimedEvent
import org.jellyfin.playback.core.timedevent.addTimedEvent
import org.jellyfin.playback.core.timedevent.timedEvents
import timber.log.Timber
import kotlin.time.Duration

class MediaStreamService internal constructor(
	private val mediaStreamResolvers: Collection<MediaStreamResolver>,
	private val preloadDuration: Duration,
	private val trackCarryStore: TrackCarryStore? = null,
) : PlayerService() {
	private companion object {
		private const val TIMED_EVENT_PRELOAD = "MediaStreamServicePreloadNext"
	}

	/**
	 * The audio and subtitle tracks the user last picked, applied to every entry played after them.
	 * Null until a choice is made, leaving the server default in place.
	 */
	class CarriedTracks(
		audio: TrackPreference? = null,
		subtitle: TrackPreference? = null,
		private val onChange: (CarriedTracks) -> Unit = {},
	) {
		var audio = audio
			set(value) {
				field = value
				onChange(this)
			}

		var subtitle = subtitle
			set(value) {
				field = value
				onChange(this)
			}
	}

	/**
	 * Choices made in a [trackCarryGroup], kept in [trackCarryStore] so they outlive the app. An
	 * episode is usually started on its own from its details page, which replaces the queue, and
	 * the TV closes an idle app, so a choice kept any shorter never reached the next episode.
	 */
	private val groupCarriedTracks = mutableMapOf<String, CarriedTracks>()

	/**
	 * Choices made on entries in no group, reset when the queue ends so they cannot carry into
	 * unrelated playback.
	 */
	private var queueCarriedTracks = CarriedTracks()

	/**
	 * The carried choices that apply to [entry]: those of its group, or of the queue when it has none.
	 */
	fun carriedTracks(entry: QueueEntry): CarriedTracks = entry.trackCarryGroup
		?.let { group -> groupCarriedTracks.getOrPut(group) { loadCarriedTracks(group) } }
		?: queueCarriedTracks

	private fun loadCarriedTracks(group: String) = CarriedTracks(
		audio = trackCarryStore?.read(group, TrackCarryStore.Kind.AUDIO),
		subtitle = trackCarryStore?.read(group, TrackCarryStore.Kind.SUBTITLE),
		onChange = { tracks ->
			trackCarryStore?.write(group, TrackCarryStore.Kind.AUDIO, tracks.audio)
			trackCarryStore?.write(group, TrackCarryStore.Kind.SUBTITLE, tracks.subtitle)
		},
	)

	override suspend fun onInitialize() {
		manager.queue.entry.onEach { entry ->
			Timber.d("Queue entry changed to $entry")

			if (entry == null) {
				// The queue ended or was cleared, so the choices made within it no longer apply.
				queueCarriedTracks = CarriedTracks()

				val backend = requireNotNull(manager.backend)
				backend.stop()
			} else {
				playEntry(entry)
				entry.ensurePreloadTimedEvent()
			}
		}.launchIn(coroutineScope + Dispatchers.Main)

		manager.backendService.addListener(object : PlayerBackendEventListener() {
			override fun onMediaStreamUnplayable(mediaStream: PlayableMediaStream) {
				coroutineScope.launch(Dispatchers.Main) { fallBackFromDirectPlay(mediaStream) }
			}
		})
	}

	/**
	 * Ask the server to convert a file the player refused to read, resuming where it was meant to
	 * be. Once per entry: a converted stream that fails too is left to the error it raised.
	 */
	private suspend fun fallBackFromDirectPlay(mediaStream: PlayableMediaStream) {
		val entry = mediaStream.queueEntry
		if (mediaStream.conversionMethod != MediaConversionMethod.None) return
		if (entry.directPlayFailed == true || manager.queue.entry.value !== entry) return
		if (entry.mediaStream !== mediaStream) return

		Timber.w("Direct play of $entry was refused by the player, asking the server to convert it")
		entry.directPlayFailed = true
		reloadCurrentStream()
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

	private var reloadGeneration = 0

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

		// Resolves run in parallel and can finish out of order, so two quick track changes on a
		// transcode could end on the first one. Only the latest reload may replace the stream.
		val generation = ++reloadGeneration
		val stream = resolveMediaStream(entry)
		if (generation != reloadGeneration || manager.queue.entry.value !== entry) {
			Timber.d("Dropping a stale re-resolve for entry $entry")
			return@withContext
		}

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
		if (mediaStream == null) {
			this.preferredAudioLanguage = carriedTracks(this).audio?.language
			mediaStream = resolveMediaStream(this)
		}

		return mediaStream != null
	}

	/**
	 * Select the carried tracks on this entry. Done as it starts playing rather than when its stream
	 * is resolved: the next entry is resolved ahead of time, and a choice made after that would
	 * otherwise miss it.
	 *
	 * A stream that already holds every track gets the selection applied by the backend. One the
	 * server converted has the old choice baked in, so it is resolved again with the new one.
	 */
	private suspend fun QueueEntry.applyCarriedTracks() {
		val stream = mediaStream ?: return
		val carried = carriedTracks(this)
		this.preferredAudioLanguage = carried.audio?.language

		val audioIndex = carried.audio?.pick(stream.tracks.filterIsInstance<MediaStreamAudioTrack>())
		val subtitleIndex = carried.subtitle?.pick(stream.tracks.filterIsInstance<MediaStreamSubtitleTrack>())

		var changed = false
		if (audioIndex != null && audioIndex != selectedAudioStreamIndex) {
			selectedAudioStreamIndex = audioIndex
			changed = true
		}
		if (subtitleIndex != null && subtitleIndex != selectedSubtitleStreamIndex) {
			selectedSubtitleStreamIndex = subtitleIndex
			changed = true
		}

		if (changed && stream.conversionMethod != MediaConversionMethod.None) {
			resolveMediaStream(this)?.let { mediaStream = it }
		}
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
			entry.applyCarriedTracks()

			// Taken rather than read, so replaying the same entry later starts at its beginning.
			val startPosition = entry.startPosition ?: Duration.ZERO
			entry.startPosition = null

			backend.playItem(entry, startPosition)
		} else {
			Timber.e("Unable to resolve stream for entry $entry")
			manager.emitEvent(PlaybackEvent.EntryUnplayable(entry))

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

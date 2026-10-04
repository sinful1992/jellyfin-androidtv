package org.jellyfin.playback.jellyfin.playsession

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.audioStreamIndexPicked
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.mediastream.selectedAudioStreamIndex
import org.jellyfin.playback.core.mediastream.selectedSubtitleStreamIndex
import org.jellyfin.playback.core.mediastream.subtitleStreamIndexPicked
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.RepeatMode
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.QueueItem
import org.jellyfin.sdk.model.extensions.inWholeTicks
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.jellyfin.sdk.model.api.RepeatMode as SdkRepeatMode

class PlaySessionService(
	private val api: ApiClient,
) : PlayerService() {
	private companion object {
		/**
		 * How often playback progress is reported while playing. Matches what the web client sends,
		 * and bounds how much of an interrupted item has to be watched again.
		 */
		private val PROGRESS_REPORT_INTERVAL = 10.seconds
	}

	/**
	 * The play session a start was reported for, remembered so the stop can name the same one.
	 *
	 * Reading it out of the queue when the stop arrives does not work. [PlayerState.stop] asks the
	 * backend to stop and then clears the queue in the same call, while the backend's STOPPED state
	 * only arrives a looper message later and is collected on this service's own scope — so by the
	 * time the stop is handled the entry is always null and the report was dropped at its first
	 * line. The server was never told playback ended and kept the session open until it expired it
	 * on its own, which is what an abandoned session in the dashboard is.
	 *
	 * It also covers the entry outliving the session: re-resolving a stream (a transcode that has
	 * to bake in a different audio track, or a device profile change) replaces the entry's stream
	 * with one carrying a new play session id. Without this, the old session was left open and the
	 * next PLAYING opened a second one beside it.
	 */
	private var reportedSession: ReportedSession? = null

	private data class ReportedSession(
		val itemId: UUID,
		val stream: PlayableMediaStream,
		val playlistItemId: String?,
	)

	/**
	 * The job reporting progress while playback runs, so it can be stopped before a stop is sent.
	 */
	private var progressReports: Job? = null

	override suspend fun onInitialize() {
		// The queue moving on ends the session for the entry being left. Nothing in the play state
		// says that reliably: playback runs straight through a manual skip, and the stop that the
		// end of an entry does raise is collected here while the queue advances elsewhere, so which
		// of the two lands first is a race. Taking the boundary from the queue makes it the same
		// boundary in both cases.
		manager.queue.entry
			.map { entry -> entry?.baseItem?.id }
			.distinctUntilChanged()
			.onEach { itemId ->
				val open = reportedSession
				if (open == null || open.itemId == itemId) return@onEach

				sendStreamStop()

				if (state.playState.value == PlayState.PLAYING) {
					sendStreamStart()
					startProgressReports()
				}
			}
			.launchIn(coroutineScope)

		state.playState.onEach { playState ->
			when (playState) {
				PlayState.PLAYING -> {
					sendStreamStart()
					startProgressReports()
				}

				PlayState.PAUSED -> {
					stopProgressReports()
					sendStreamUpdate()
				}

				PlayState.STOPPED -> sendStreamStop()
				PlayState.ERROR -> sendStreamStop()
			}
		}.launchIn(coroutineScope)
	}

	/**
	 * Report progress at a fixed interval for as long as playback runs.
	 *
	 * Without this the server is only told a position when playback pauses and when it stops, and
	 * neither happens when the app is killed, the television is switched off at the wall or the
	 * stream dies. What it kept was whatever the last pause said, or the position the item started
	 * at when there was never a pause — so an item watched straight through and then interrupted
	 * resumed from its beginning.
	 */
	private fun startProgressReports() {
		if (progressReports?.isActive == true) return

		progressReports = coroutineScope.launch {
			while (true) {
				delay(PROGRESS_REPORT_INTERVAL)

				// Pausing and stopping both cancel this job where they are handled, so this is
				// only the backstop that keeps it from outliving the playback it reports on.
				if (state.playState.value != PlayState.PLAYING) break

				reportCurrentEntry()
			}
		}
	}

	/**
	 * Stop reporting progress, waiting for a report already in flight, so nothing can reach the
	 * server after the stop and leave the session open behind it.
	 */
	private suspend fun stopProgressReports() {
		progressReports?.cancelAndJoin()
		progressReports = null
	}

	private val MediaConversionMethod.playMethod
		get() = when (this) {
			MediaConversionMethod.None -> PlayMethod.DIRECT_PLAY
			MediaConversionMethod.Remux -> PlayMethod.DIRECT_STREAM
			MediaConversionMethod.Transcode -> PlayMethod.TRANSCODE
		}

	private val RepeatMode.remoteRepeatMode
		get() = when (this) {
			RepeatMode.NONE -> SdkRepeatMode.REPEAT_NONE
			RepeatMode.REPEAT_ENTRY_ONCE -> SdkRepeatMode.REPEAT_ONE
			RepeatMode.REPEAT_ENTRY_INFINITE -> SdkRepeatMode.REPEAT_ALL
		}

	suspend fun sendUpdateIfActive() {
		coroutineScope.launch { sendStreamUpdate() }
	}

	/**
	 * Say where the current entry has reached, opening a session for it when it has none.
	 *
	 * An entry the queue moved to while the play state never changed has no session yet, and a
	 * progress report naming a session the server has not been told about is not one it can do
	 * anything with.
	 */
	private suspend fun reportCurrentEntry() {
		val current = manager.queue.entry.value?.mediaStream?.identifier
		if (reportedSession?.stream?.identifier != current) sendStreamStart() else sendStreamUpdate()
	}

	private suspend fun readPosition(): Duration =
		withContext(Dispatchers.Main) { state.positionInfo.active }

	private suspend fun getQueue(): List<QueueItem> {
		// The queues are lazy loaded so we only load a small amount of items to set as queue on the
		// backend.
		return manager.queue
			.peekNext(15)
			.mapNotNull { it.baseItem }
			.map { QueueItem(id = it.id, playlistItemId = it.playlistItemId) }
	}

	private suspend fun sendStreamStart() {
		val entry = manager.queue.entry.value ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: return

		// Already open, so this is playback resuming or a start the progress reports asked for
		// again, not a new session. Telling the server where it is says that; starting it a second
		// time only repeats what it already has.
		val previous = reportedSession
		if (previous?.stream?.identifier == stream.identifier) {
			sendStreamUpdate()
			return
		}

		// A different session than the one still open means the stream was resolved again. Close
		// the old one rather than leaving the server with two sessions for one playback.
		if (previous != null) sendStreamStop()

		reportedSession = ReportedSession(
			itemId = item.id,
			stream = stream,
			playlistItemId = item.playlistItemId,
		)

		runCatching {
			api.playStateApi.reportPlaybackStart(
				PlaybackStartInfo(
					itemId = item.id,
					playSessionId = stream.identifier,
					playlistItemId = item.playlistItemId,
					canSeek = true,
					isMuted = state.volume.muted,
					volumeLevel = (state.volume.volume * 100).roundToInt(),
					isPaused = state.playState.value != PlayState.PLAYING,
					aspectRatio = state.videoSize.value.aspectRatio.toString(),
					positionTicks = readPosition().inWholeTicks,
					playMethod = stream.conversionMethod.playMethod,
					// Only a choice the user made on this entry. The server keeps a reported track as
					// the item's default for every client, so a carried one would overwrite it with a
					// choice made on another item.
					audioStreamIndex = entry.reportedAudioStreamIndex,
					subtitleStreamIndex = entry.reportedSubtitleStreamIndex,
					repeatMode = state.repeatMode.value.remoteRepeatMode,
					nowPlayingQueue = getQueue(),
					playbackOrder = when (state.playbackOrder.value) {
						org.jellyfin.playback.core.model.PlaybackOrder.DEFAULT -> PlaybackOrder.DEFAULT
						org.jellyfin.playback.core.model.PlaybackOrder.RANDOM -> PlaybackOrder.SHUFFLE
						org.jellyfin.playback.core.model.PlaybackOrder.SHUFFLE -> PlaybackOrder.SHUFFLE
					}
				)
			)
		}.onFailure { error -> Timber.w(error, "Failed to send playback start event") }
	}

	private suspend fun sendStreamUpdate() {
		val entry = manager.queue.entry.value ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: return

		// Only for the session that is open. The player pauses at the end of every entry, after
		// its stop has gone out, and a progress report for a closed session makes the server open
		// it again: it then logged a second stop for the same item with no start between them.
		if (reportedSession?.stream?.identifier != stream.identifier) return

		runCatching {
			api.playStateApi.reportPlaybackProgress(
				PlaybackProgressInfo(
					itemId = item.id,
					playSessionId = stream.identifier,
					playlistItemId = item.playlistItemId,
					canSeek = true,
					isMuted = state.volume.muted,
					volumeLevel = (state.volume.volume * 100).roundToInt(),
					isPaused = state.playState.value != PlayState.PLAYING,
					aspectRatio = state.videoSize.value.aspectRatio.toString(),
					positionTicks = readPosition().inWholeTicks,
					playMethod = stream.conversionMethod.playMethod,
					audioStreamIndex = entry.reportedAudioStreamIndex,
					subtitleStreamIndex = entry.reportedSubtitleStreamIndex,
					repeatMode = state.repeatMode.value.remoteRepeatMode,
					nowPlayingQueue = getQueue(),
					playbackOrder = when (state.playbackOrder.value) {
						org.jellyfin.playback.core.model.PlaybackOrder.DEFAULT -> PlaybackOrder.DEFAULT
						org.jellyfin.playback.core.model.PlaybackOrder.RANDOM -> PlaybackOrder.SHUFFLE
						org.jellyfin.playback.core.model.PlaybackOrder.SHUFFLE -> PlaybackOrder.SHUFFLE
					}
				)
			)
		}.onFailure { error -> Timber.w("Failed to send playback update event", error) }
	}

	private suspend fun sendStreamStop() {
		// Before the session is read, so a progress report cannot land after the stop.
		stopProgressReports()

		// Deliberately not read from the queue: see [reportedSession]. Null means nothing was ever
		// started, or the stop for it has already been sent.
		val session = reportedSession ?: return
		reportedSession = null

		// Asked for by name, because the player answers for whatever it is playing now and the
		// queue may already have moved on to the next entry. Falls back to the player for a
		// session it never left, which is a stream that failed rather than ended.
		val position = manager.backend.getFinalPosition(session.stream) ?: readPosition()

		runCatching {
			api.playStateApi.reportPlaybackStopped(
				PlaybackStopInfo(
					itemId = session.itemId,
					playSessionId = session.stream.identifier,
					playlistItemId = session.playlistItemId,
					positionTicks = position.inWholeTicks,
					failed = false,
					nowPlayingQueue = getQueue(),
				)
			)
		}.onFailure { error -> Timber.w("Failed to send playback stop event", error) }
	}
}

private val QueueEntry.reportedAudioStreamIndex
	get() = selectedAudioStreamIndex.takeIf { audioStreamIndexPicked == true }

private val QueueEntry.reportedSubtitleStreamIndex
	get() = selectedSubtitleStreamIndex.takeIf { subtitleStreamIndexPicked == true }

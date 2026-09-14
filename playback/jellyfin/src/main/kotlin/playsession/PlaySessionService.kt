package org.jellyfin.playback.jellyfin.playsession

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.RepeatMode
import org.jellyfin.playback.core.plugin.PlayerService
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
		val playSessionId: String,
		val playlistItemId: String?,
	)

	/**
	 * The job reporting progress while playback runs, so it can be stopped before a stop is sent.
	 */
	private var progressReports: Job? = null

	override suspend fun onInitialize() {
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

				// Nothing to report against once the stop has been sent, and a report naming a
				// finished session opens it again on the server.
				if (reportedSession == null) break

				sendStreamUpdate()
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

		// A different session than the one still open means the stream was resolved again. Close
		// the old one rather than leaving the server with two sessions for one playback.
		val previous = reportedSession
		if (previous != null && previous.playSessionId != stream.identifier) sendStreamStop()

		reportedSession = ReportedSession(
			itemId = item.id,
			playSessionId = stream.identifier,
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
					positionTicks = withContext(Dispatchers.Main) { state.positionInfo.active.inWholeTicks },
					playMethod = stream.conversionMethod.playMethod,
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
					positionTicks = withContext(Dispatchers.Main) { state.positionInfo.active.inWholeTicks },
					playMethod = stream.conversionMethod.playMethod,
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

		runCatching {
			api.playStateApi.reportPlaybackStopped(
				PlaybackStopInfo(
					itemId = session.itemId,
					playSessionId = session.playSessionId,
					playlistItemId = session.playlistItemId,
					positionTicks = withContext(Dispatchers.Main) { state.positionInfo.active.inWholeTicks },
					failed = false,
					nowPlayingQueue = getQueue(),
				)
			)
		}.onFailure { error -> Timber.w("Failed to send playback stop event", error) }
	}
}

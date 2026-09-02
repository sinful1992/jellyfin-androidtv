package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue

/**
 * The service resolving media streams for this manager.
 */
val PlaybackManager.mediaStreamService: MediaStreamService
	get() = requireNotNull(getService<MediaStreamService>())

/**
 * The audio and subtitle tracks of the stream currently playing, in the order the media source
 * lists them. Empty when nothing is playing or the stream has not been resolved yet.
 */
val PlaybackManager.currentTracks: Collection<MediaStreamTrack>
	get() = queue.entry.value?.mediaStream?.tracks.orEmpty()

/**
 * Play the current entry with a different audio track, keeping the position and play state.
 * Does nothing when that track is already selected.
 *
 * The change is applied in the background: on a stream that already carries every track it takes
 * effect without interrupting playback, and otherwise the stream is resolved again, which on a
 * transcode restarts it server side.
 */
fun PlaybackManager.selectAudioStream(index: Int) {
	val entry = queue.entry.value ?: return
	if (entry.selectedAudioStreamIndex == index) return

	entry.selectedAudioStreamIndex = index
	mediaStreamService.applyAudioTrackSelection(index)
}

/**
 * Play the current entry with a different subtitle track, keeping the position and play state.
 * Pass [MediaStreamSubtitleTrack.INDEX_NONE] to turn subtitles off. Does nothing when that track
 * is already selected.
 *
 * @see selectAudioStream
 */
fun PlaybackManager.selectSubtitleStream(index: Int) {
	val entry = queue.entry.value ?: return
	if (entry.selectedSubtitleStreamIndex == index) return

	entry.selectedSubtitleStreamIndex = index
	mediaStreamService.applySubtitleTrackSelection(index)
}

package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.queue.QueueEntry

/**
 * Determine the media stream for a given queue item.
 */
interface MediaStreamResolver {
	/**
	 * @return [PlayableMediaStream] or null if no stream can be determined by this resolver
	 */
	suspend fun getStream(
		queueEntry: QueueEntry,
	): PlayableMediaStream?

	/**
	 * The tracks [queueEntry] has, when they are known without asking the server for a stream, so
	 * carried choices can go into the first request instead of a second one.
	 *
	 * @return the tracks with the indices [getStream] will use, or null when not known
	 */
	fun getKnownTracks(queueEntry: QueueEntry): Collection<MediaStreamTrack>? = null
}

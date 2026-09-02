package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.PlaybackManager

/**
 * Play the current entry again with its stream resolved from scratch, keeping the position and
 * play state. Use after changing something the resolvers read from outside the queue entry, such
 * as the device profile the server is asked to match.
 *
 * On a transcode this restarts the stream server side.
 */
fun PlaybackManager.reloadStream() {
	mediaStreamService.applyStreamOptionsChange()
}

package org.jellyfin.playback.core.backend

import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.model.PlayState

abstract class PlayerBackendEventListener {
	open fun onPlayStateChange(state: PlayState) = Unit
	open fun onVideoSizeChange(width: Int, height: Int) = Unit
	open fun onMediaStreamEnd(mediaStream: PlayableMediaStream) = Unit

	/**
	 * The player refused [mediaStream] itself - its container could not be read - as opposed to
	 * failing to fetch it. Raised alongside [PlayState.ERROR], for whoever can offer it another way.
	 */
	open fun onMediaStreamUnplayable(mediaStream: PlayableMediaStream) = Unit
}

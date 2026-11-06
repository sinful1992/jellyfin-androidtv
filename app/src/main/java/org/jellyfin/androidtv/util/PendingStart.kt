package org.jellyfin.androidtv.util

/**
 * A playback start held back because the player was stopped when the display mode switch finished
 * (an HDMI re-sync that outlasts the switch stops the activity). Released when the player is shown
 * again, so the film starts instead of leaving a black player.
 */
class PendingStart {
	private var start: (() -> Unit)? = null

	val isHeld: Boolean get() = start != null

	fun hold(start: () -> Unit) {
		this.start = start
	}

	/** Run the held start once; a player that is [leaving] drops it instead. */
	fun release(leaving: Boolean) {
		val held = start ?: return
		start = null
		if (!leaving) held()
	}
}

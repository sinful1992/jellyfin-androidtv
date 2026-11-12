package org.jellyfin.androidtv.util

/**
 * Leaving the player after the display link drops, held back while the player is not resumed.
 *
 * Switching the TV off (or the HDMI re-sync of a display mode switch) pauses and stops the activity, and a details
 * page opened then dropped its item because it was not started, so it came back black with no rows until Back was
 * pressed (tasks 147/148; the page now also keeps that item, see RunWhenStarted). Playback is already paused or
 * stopped by the player, so only the navigation waits until the player is resumed again.
 */
class LeaveWhenShown {
	private var due = false

	val isDue: Boolean get() = due

	/** The link dropped: leave now when the player is on screen, else once it is [shown] again. */
	fun linkLost(hidden: Boolean, leave: () -> Unit) {
		if (hidden) due = true
		else leave()
	}

	/** The player is shown again: run a held leave once. */
	fun shown(leave: () -> Unit) {
		if (!due) return
		due = false
		leave()
	}
}

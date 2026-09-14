package org.jellyfin.playback.core

import org.jellyfin.playback.core.queue.QueueEntry

/**
 * Something that happened during playback which the user should be told about, but which is not
 * part of the player's state and leaves nothing behind to observe afterwards.
 *
 * Emitted on [PlaybackManager.events]. Events are dropped when nobody is listening, so an event
 * is a message, never a source of truth.
 */
sealed interface PlaybackEvent {
	/**
	 * No stream could be resolved for [entry], so it was passed over. Playback continues with the
	 * next entry in the queue, or stops when there is none.
	 */
	data class EntryUnplayable(val entry: QueueEntry) : PlaybackEvent
}

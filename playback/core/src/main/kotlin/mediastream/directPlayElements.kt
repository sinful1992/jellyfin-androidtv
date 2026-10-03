package org.jellyfin.playback.core.mediastream

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import org.jellyfin.playback.core.queue.QueueEntry

private val directPlayFailedKey = ElementKey<Boolean>("DirectPlayFailed")

/**
 * Whether the player refused this [QueueEntry]'s file when it was played directly. Resolvers ask
 * the server for a stream it converts instead once this is set, so the entry is not offered the
 * same file again. Null means it has not failed.
 */
var QueueEntry.directPlayFailed by element(directPlayFailedKey)

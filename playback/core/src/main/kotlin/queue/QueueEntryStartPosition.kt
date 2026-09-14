package org.jellyfin.playback.core.queue

import org.jellyfin.playback.core.element.ElementKey
import org.jellyfin.playback.core.element.element
import kotlin.time.Duration

private val startPositionKey = ElementKey<Duration>("StartPosition")

/**
 * Where playback of this [QueueEntry] should begin, for an entry being resumed.
 *
 * Set before the entry reaches the backend. The backend applies it when it prepares the item, so
 * the alternative - starting at zero and seeking once playback is under way - is not needed: that
 * has to wait for a state the player may never publish, shows the opening of the item first, and
 * silently loses the position when the seek lands before the timeline is seekable.
 *
 * Consumed by the backend, which clears it, so it cannot be reapplied on a later replay of the
 * same entry.
 */
var QueueEntry.startPosition by element(startPositionKey)

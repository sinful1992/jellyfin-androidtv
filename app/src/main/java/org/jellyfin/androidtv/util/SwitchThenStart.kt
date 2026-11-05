package org.jellyfin.androidtv.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * Run the display mode [switch], then [start] playback, unless the player was left meanwhile.
 *
 * A failed switch still starts playback at the current mode. A cancelled one (the player was
 * destroyed) or a player that [isGone] (stopped or leaving) does not: starting then would play
 * sound with no player on screen.
 *
 * @return whether [start] ran.
 */
suspend fun switchThenStart(
	switch: suspend () -> Unit,
	isGone: () -> Boolean,
	start: () -> Unit,
): Boolean {
	try {
		switch()
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		Timber.w(e, "Display mode switch failed")
	}
	currentCoroutineContext().ensureActive()
	if (isGone()) return false
	start()
	return true
}

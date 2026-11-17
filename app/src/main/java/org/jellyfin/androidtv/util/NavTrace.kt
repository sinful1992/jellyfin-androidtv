package org.jellyfin.androidtv.util

import android.os.SystemClock
import android.util.Log

/**
 * Back-to-Home timing in logcat (task 176), tag `JFNav`, kept in release builds like [StartupTrace]:
 * - `back from=<home|details|other> to=<home|details|other> at=<ms>` when a Back is taken,
 * - `home_back_drawn since_back=<ms> at=<ms>` on the first draw of the Home rows after a Back to Home,
 * - `home_back_rows since_back=<ms> calls=<n>` once the row refreshes that Back started have answered.
 * Times are elapsedRealtime. No names, ids or tokens.
 */
object NavTrace {
	const val TAG = "JFNav"
	/** A home_back_* line further than this from its Back belongs to a Back that never got its Home. */
	const val MAX_SINCE_BACK_MS = 10_000L

	private val lock = Any()
	private val state = NavTraceState()

	private fun now() = SystemClock.elapsedRealtime()

	fun back(from: String?, to: String?, toHome: Boolean) = log(synchronized(lock) { state.back(from, to, toHome, now()) })

	/** Any forward navigation: a Back still waiting for its Home is given up. */
	fun navigated() = synchronized(lock) { state.navigated() }

	fun homeDrawn() = log(synchronized(lock) { state.homeDrawn(now()) })

	fun homeRows(calls: Int) = log(synchronized(lock) { state.homeRows(calls, now()) })

	/** Whether the Home now coming back is the target of a Back whose rows are not yet timed. */
	fun homeRowsPending(): Boolean = synchronized(lock) { state.rowsPending }

	private fun log(line: String?) {
		if (line != null) Log.i(TAG, line)
	}
}

/** The bookkeeping of [NavTrace], free of Android so it can be tested as plain code. */
class NavTraceState {
	private var backAt = -1L
	private var drawnPending = false
	var rowsPending = false
		private set

	fun back(from: String?, to: String?, toHome: Boolean, at: Long): String {
		backAt = at
		drawnPending = toHome
		rowsPending = toHome
		return "back from=${from ?: "none"} to=${to ?: "none"} at=$at"
	}

	fun navigated() {
		drawnPending = false
		rowsPending = false
	}

	fun homeDrawn(at: Long): String? {
		if (!drawnPending) return null
		drawnPending = false
		if (at - backAt > NavTrace.MAX_SINCE_BACK_MS) return null
		return "home_back_drawn since_back=${at - backAt} at=$at"
	}

	fun homeRows(calls: Int, at: Long): String? {
		if (!rowsPending) return null
		rowsPending = false
		if (at - backAt > NavTrace.MAX_SINCE_BACK_MS) return null
		return "home_back_rows since_back=${at - backAt} calls=$calls"
	}
}

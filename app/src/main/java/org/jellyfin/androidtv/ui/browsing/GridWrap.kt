package org.jellyfin.androidtv.ui.browsing

import android.view.KeyEvent

/** Where Left or Right goes from the edge of a line in a grid that fills lines left to right. */
object GridWrap {
	const val NONE = -1

	/**
	 * The card [keyCode] moves to from [position] when that crosses into the next or previous line,
	 * or [NONE] when the key stays inside the line, there is nothing past the edge, or it is not
	 * Left or Right.
	 */
	@JvmStatic
	fun target(keyCode: Int, position: Int, columns: Int, count: Int): Int {
		if (columns < 1 || position !in 0 until count) return NONE

		return when (keyCode) {
			KeyEvent.KEYCODE_DPAD_RIGHT -> if (position % columns == columns - 1 && position + 1 < count) position + 1 else NONE
			KeyEvent.KEYCODE_DPAD_LEFT -> if (position % columns == 0 && position > 0) position - 1 else NONE
			else -> NONE
		}
	}
}

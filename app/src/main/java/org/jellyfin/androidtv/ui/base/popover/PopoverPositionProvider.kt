package org.jellyfin.androidtv.ui.base.popover

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider

class PopoverPositionProvider(
	val alignment: Alignment = Alignment.BottomCenter,
	val offset: IntOffset = IntOffset.Zero,
) : PopupPositionProvider {
	private companion object {
		private const val EPSILON = 1e-3f

		private const val OVERSCAN_X = 48
		private const val OVERSCAN_Y = 27
		private val PROBE_SPACE = IntSize(10_000, 10_000)
	}

	override fun calculatePosition(
		anchorBounds: IntRect,
		windowSize: IntSize,
		layoutDirection: LayoutDirection,
		popupContentSize: IntSize
	): IntOffset {
		// Calculate alignment if placed inside the anchor
		val insidePosition = alignment.align(
			size = popupContentSize,
			space = anchorBounds.size,
			layoutDirection = layoutDirection,
		)

		// Measure bias
		val biasPosition = alignment.align(
			size = IntSize.Zero,
			space = PROBE_SPACE,
			layoutDirection = layoutDirection,
		)
		val biasX = (2f * biasPosition.x / PROBE_SPACE.width) - 1f
		val biasY = (2f * biasPosition.y / PROBE_SPACE.height) - 1f

		// Calculate position
		val x =
			(anchorBounds.left + insidePosition.x + biasX.dir * popupContentSize.width + offset.x)
		val y =
			(anchorBounds.top + insidePosition.y + biasY.dir * popupContentSize.height + offset.y)

		// Return position clamped to fit in window with overscan
		return IntOffset(
			x = x.clampToWindow(OVERSCAN_X, windowSize.width - popupContentSize.width),
			y = y.clampToWindow(OVERSCAN_Y, windowSize.height - popupContentSize.height),
		)
	}

	/**
	 * Clamp to the space between the overscan margins, where [limit] is the largest position that
	 * still leaves the popup fully inside the window.
	 *
	 * A popup taller or wider than the window leaves no valid position, which would make the range
	 * empty and throw. Pin it to the leading margin instead, so a list too long for the screen
	 * starts at the top rather than being pushed off it.
	 */
	private fun Int.clampToWindow(overscan: Int, limit: Int) =
		coerceIn(overscan, (limit - overscan).coerceAtLeast(overscan))

	private val Float.dir
		get() = when {
			this > EPSILON -> 1
			this < -EPSILON -> -1
			else -> 0
		}
}

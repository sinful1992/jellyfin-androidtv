package org.jellyfin.androidtv.ui.player.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * What is open over the bottom of the player, so the next up card can keep clear of it.
 *
 * The track lists open in popups of their own, drawn over everything in the player's window with
 * the card included, and the seek preview floats above the controls in the very corner the card
 * takes near the end of an entry. Each reports where its top edge is on the screen while it is up,
 * and the card rises above the highest of them, the way a page makes room for the keyboard.
 */
@Stable
class PlayerOverlayObstructions {
	private val tops = mutableStateMapOf<Any, Float>()

	/** The highest top edge reported, in screen pixels, or null when nothing is open. */
	val highestTop: Float? get() = tops.values.minOrNull()

	fun report(key: Any, top: Float) {
		tops[key] = top
	}

	fun clear(key: Any) {
		tops.remove(key)
	}
}

val LocalPlayerOverlayObstructions = staticCompositionLocalOf<PlayerOverlayObstructions?> { null }

/** Reports this element's top edge to the player's [PlayerOverlayObstructions] for as long as it is shown. */
@Composable
fun Modifier.obstructsNextUp(): Modifier {
	val obstructions = LocalPlayerOverlayObstructions.current ?: return this
	val key = remember { Any() }
	DisposableEffect(obstructions, key) {
		onDispose { obstructions.clear(key) }
	}
	// Screen coordinates, because a popup is a window of its own and its local positions mean
	// nothing in the player's.
	return onGloballyPositioned { obstructions.report(key, it.localToScreen(Offset.Zero).y) }
}

/**
 * How far above [anchorBottom], where the card's bottom edge sits with nothing open, that edge has
 * to go to clear [obstructionTop] by [gap]. Zero when nothing is open or it is clear already. All in
 * screen pixels, which grow downwards.
 */
internal fun nextUpClearance(anchorBottom: Float, obstructionTop: Float?, gap: Float): Float =
	if (obstructionTop == null) 0f else (anchorBottom - obstructionTop + gap).coerceAtLeast(0f)

package org.jellyfin.androidtv.ui.navigation.layout

import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentContainerView

/**
 * A fragment view that came up with no size inside a parent that has one.
 *
 * [isHost] is true when [view] is a fragment container itself, false when it is the root view of a
 * fragment placed in a container.
 */
class CollapsedFragmentView(
	val view: View,
	val parent: ViewGroup,
	val isHost: Boolean,
)

private val View.hasNoSize get() = width == 0 || height == 0

/**
 * Returns the top-most collapsed fragment view under [root], or null when every fragment level has
 * a size.
 *
 * Only fragment levels are checked: each visible fragment container and the visible views it holds,
 * which are fragment roots. Those are the views added after their parent was laid out, the level
 * where the black screen dumps show a lost layout pass (the home rows grid on 09-20, HomeFragment's
 * ComposeView on 09-21). A healthy screen also has 0x0 views that are not fragment levels, a gone
 * action mode stub and hidden leanback row headers, and they never match. Views that are not
 * VISIBLE are skipped together with their children.
 */
fun findCollapsedFragmentView(
	root: ViewGroup,
	isHost: (ViewGroup) -> Boolean = { it is FragmentContainerView },
): CollapsedFragmentView? {
	if (root.hasNoSize) return null

	for (index in 0 until root.childCount) {
		val child = root.getChildAt(index) ?: continue
		if (child.visibility != View.VISIBLE) continue

		val childIsHost = child is ViewGroup && isHost(child)
		if (child.hasNoSize) {
			if (childIsHost || isHost(root)) return CollapsedFragmentView(child, root, childIsHost)
			continue
		}

		if (child is ViewGroup) findCollapsedFragmentView(child, isHost)?.let { return it }
	}

	return null
}

/**
 * Requests a layout on [view] and on every ancestor up to the window.
 *
 * A single requestLayout stops climbing at the first ancestor that already reports a pending layout,
 * and a stale pending flag is how the original request got lost. An ancestor that is not flagged
 * also skips re-measuring its children when its own measure spec is unchanged. Flagging each level
 * on the way up avoids both.
 */
fun requestLayoutToWindow(view: View) {
	var current: View? = view
	while (current != null) {
		current.requestLayout()
		current = current.parent as? View
	}
}

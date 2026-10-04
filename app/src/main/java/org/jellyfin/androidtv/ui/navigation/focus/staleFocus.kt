package org.jellyfin.androidtv.ui.navigation.focus

import android.view.View
import android.view.ViewGroup

/**
 * Walks the focus chain down from [root] and returns the first group whose focused child is no
 * longer attached to it.
 *
 * A recycler can detach a child that holds focus without clearing its parent's focused child. A key
 * press in that state makes FocusFinder throw "parameter must be a descendant of this view", which
 * is the crash both TVs reported from Compose's interop focus search.
 */
fun findStaleFocusOwner(root: ViewGroup): ViewGroup? {
	var group = root
	while (true) {
		val child: View = group.focusedChild ?: return null
		if (child.parent !== group) return group
		group = child as? ViewGroup ?: return null
	}
}

/**
 * Drops a stale focused child found by [findStaleFocusOwner] and moves focus back into the group
 * that held it. Returns the group that was repaired, or null when the focus chain was intact.
 */
fun repairStaleFocus(root: ViewGroup): ViewGroup? {
	val group = findStaleFocusOwner(root) ?: return null
	group.clearChildFocus(group.focusedChild)
	group.requestFocus()
	return group
}

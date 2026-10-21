package org.jellyfin.androidtv.ui.navigation

/** What a new MainActivity does with the navigation state it finds. */
enum class StartNavigation {
	/** Leave the pending action for the nav host to replay. */
	REPLAY,

	/** Start over on the home screen. */
	RESET_HOME,

	/** Start over on the destination on top of the back stack, or home when there is none. */
	RESET_CURRENT,
}

/**
 * Decides how a new MainActivity starts its navigation.
 *
 * Its nav host always starts empty, on a recreation too: DestinationFragmentView has no view id, so
 * Compose never saves or restores its history. Whatever it shows has to come from the action it is
 * replayed. A navigate with no back stack behind it is what StartupActivity leaves, home or a deep
 * link, and is replayed as it is. Anything else (no action at all after the task outlived its
 * process, a Back, or a navigate on top of a back stack the empty host does not have) starts over on
 * the current destination, so the host and the repository agree again.
 */
fun startNavigation(
	pendingAction: NavigationAction?,
	canGoBack: Boolean,
	recreated: Boolean,
): StartNavigation = when {
	!recreated && canGoBack -> StartNavigation.RESET_HOME
	pendingAction is NavigationAction.NavigateFragment && !canGoBack -> StartNavigation.REPLAY
	else -> StartNavigation.RESET_CURRENT
}

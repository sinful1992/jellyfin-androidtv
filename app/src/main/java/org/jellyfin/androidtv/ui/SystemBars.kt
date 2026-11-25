package org.jellyfin.androidtv.ui

import android.app.Activity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Keeps the status bar and navigation bar hidden while the app is in front.
 *
 * Boxes on plain Android (e.g. the Ugoos SK4) show both bars over every app, so a film is squeezed
 * between them. Android TV launchers show no bars, so there this changes nothing. Dialogs and the
 * keyboard can bring the bars back, which is why it runs again whenever the window regains focus.
 */
object SystemBars {
	fun hide(controller: WindowInsetsControllerCompat) {
		controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		controller.hide(WindowInsetsCompat.Type.systemBars())
	}

	fun onFocusChanged(controller: WindowInsetsControllerCompat, hasFocus: Boolean) {
		if (hasFocus) hide(controller)
	}

	fun controllerOf(activity: Activity) = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
}

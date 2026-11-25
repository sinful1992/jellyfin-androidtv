package org.jellyfin.androidtv.ui

import android.app.Activity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Keeps the status bar and navigation bar hidden while the app is in front.
 *
 * Boxes on plain Android (e.g. the Ugoos SK4) show both bars over every app, so a film is squeezed
 * between them. Android TV launchers show no bars, so there this changes nothing. Dialogs and the
 * keyboard can bring the bars back, which is why it runs again whenever the window regains focus.
 * A Compose dialog has its own window, so it needs the same treatment ([HideSystemBarsInDialog]).
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

	/** The window of the Compose dialog that hosts [view], or null when it is not in a dialog (preview, test host). */
	fun dialogWindowOf(view: View): Window? = (view.parent as? DialogWindowProvider)?.window

	/**
	 * Hides the bars now and again each time [view]'s window regains focus (keyboard or a popup over it closed).
	 * Returns the function that stops listening.
	 */
	fun keepHidden(view: View, controller: WindowInsetsControllerCompat): () -> Unit {
		hide(controller)
		val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> onFocusChanged(controller, hasFocus) }
		val observer = view.viewTreeObserver
		observer.addOnWindowFocusChangeListener(listener)
		return { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
	}
}

/** Call inside a Compose `Dialog { }`: keeps the status and navigation bars hidden in that dialog's window. */
@Composable
fun HideSystemBarsInDialog() {
	val view = LocalView.current
	DisposableEffect(view) {
		val window = SystemBars.dialogWindowOf(view)
		val stop = window?.let { SystemBars.keepHidden(view, WindowInsetsControllerCompat(it, it.decorView)) }
		onDispose { stop?.invoke() }
	}
}

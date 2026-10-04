package org.jellyfin.androidtv.ui.navigation.layout

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import org.acra.ACRA

/**
 * Finds a fragment view that came up with no size and asks for the layout pass it missed.
 *
 * The black screen after TV standby is a fragment view left at 0x0 inside a parent that has a size:
 * the pass that should have measured it never ran, and nothing on the screen asks for another one.
 * It has not been reproduced on demand, so instead of a guessed cause this checks for the end state
 * after each page change and resume, repairs it, and reports once per process whether that worked.
 */
object BlackScreenHeal {
	private const val TAG = "BlackScreenHeal"

	// Past the 400 ms fragment fade, so a page whose first layout simply has not run yet is not a hit.
	private const val DELAY_MS = 500L

	// A cold start can still be attaching its first page at the first check and heal it on the normal pass,
	// which would spend the one report on a false cure. The standby case comes in a long-lived process.
	private const val REPORT_MIN_AGE_S = 60L

	// Fallback for API 23, which has no process start time: the first use, at the first page change.
	private val firstUse = SystemClock.elapsedRealtime()

	private var reported = false

	private fun processAgeSeconds(): Long {
		val start = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Process.getStartElapsedRealtime() else firstUse
		return (SystemClock.elapsedRealtime() - start) / 1000
	}

	/** Checks the window [anchor] belongs to after a delay. Main thread only. */
	fun schedule(anchor: View, trigger: String) {
		anchor.postDelayed({ check(anchor, trigger) }, DELAY_MS)
	}

	private fun check(anchor: View, trigger: String) {
		if (!anchor.isAttachedToWindow) return
		val root = anchor.rootView as? ViewGroup ?: return
		val hit = findCollapsedFragmentView(root) ?: return

		val view = hit.view
		val label = "level=${fragmentName(view, hit.isHost)}/${if (hit.isHost) "host" else "root"} " +
			"id=${idName(view)} parent=${hit.parent.width}x${hit.parent.height} " +
			"layoutRequested=${view.isLayoutRequested} trigger=$trigger age=${processAgeSeconds()}"
		// Release builds plant no Timber tree, so log directly to keep this visible in logcat.
		Log.w(TAG, "$TAG $label, requesting layout")
		requestLayoutToWindow(view)

		// Claimed now, not when sent, so a second trigger inside the delay cannot send a second report.
		val report = !reported && processAgeSeconds() >= REPORT_MIN_AGE_S
		if (report) reported = true

		root.postDelayed({
			// Leaving the page before the re-check (Back on a black screen) tells nothing about the heal.
			val cured = when {
				!view.isAttachedToWindow -> "detached"
				else -> (view.width > 0 && view.height > 0).toString()
			}
			val message = "$TAG $label cured=$cured"
			Log.w(TAG, message)
			if (report) ACRA.errorReporter.handleSilentException(IllegalStateException(message))
		}, DELAY_MS)
	}

	private fun fragmentName(view: View, isHost: Boolean): String {
		// A container belongs to the fragment around it; a fragment root belongs to its own fragment.
		val fragment = runCatching {
			if (isHost) (view.parent as? View)?.let { FragmentManager.findFragment<Fragment>(it) }
			else FragmentManager.findFragment<Fragment>(view)
		}.getOrNull()
		return fragment?.javaClass?.simpleName ?: "activity"
	}

	private fun idName(view: View): String = when (view.id) {
		View.NO_ID -> "no-id"
		else -> runCatching { view.resources.getResourceEntryName(view.id) }
			.getOrDefault("0x${Integer.toHexString(view.id)}")
	}
}

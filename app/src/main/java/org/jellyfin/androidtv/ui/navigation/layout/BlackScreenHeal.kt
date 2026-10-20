package org.jellyfin.androidtv.ui.navigation.layout

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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

		// A window that is not on screen lays nothing out, so every view in it reads 0x0 and no
		// request can change that: all of the reports so far were the app going to the background or
		// the TV going off just after a page change. Coming back runs the resume check, which is the
		// one that can see a real black screen.
		if (isWindowHidden(root.windowVisibility, root.isShown)) {
			Log.i(TAG, "$TAG skipped hidden $label window=${root.windowVisibility}")
			return
		}

		// Release builds plant no Timber tree, so log directly to keep this visible in logcat.
		Log.w(TAG, "$TAG $label, requesting layout")
		val before = describeChain(view)

		// Counts the window's layout and draw passes until the re-check. None at all means the
		// window is not traversing; passes that leave the view at 0x0 mean it is measured to zero.
		var layoutPasses = 0
		var drawPasses = 0
		val observer = root.viewTreeObserver
		val onLayout = ViewTreeObserver.OnGlobalLayoutListener { layoutPasses++ }
		val onDraw = ViewTreeObserver.OnPreDrawListener { drawPasses++; true }
		observer.addOnGlobalLayoutListener(onLayout)
		observer.addOnPreDrawListener(onDraw)

		requestLayoutToWindow(view)

		// Claimed now, not when sent, so a second trigger inside the delay cannot send a second report.
		val report = !reported && processAgeSeconds() >= REPORT_MIN_AGE_S
		if (report) reported = true

		root.postDelayed({
			root.viewTreeObserver.takeIf { it.isAlive }?.run {
				removeOnGlobalLayoutListener(onLayout)
				removeOnPreDrawListener(onDraw)
			}

			// Leaving the page before the re-check (Back on a black screen) tells nothing about the heal.
			val cured = when {
				!view.isAttachedToWindow -> "detached"
				else -> (view.width > 0 && view.height > 0).toString()
			}
			val message = buildString {
				append("$TAG $label cured=$cured layoutPasses=$layoutPasses drawPasses=$drawPasses")
				append("\nwindow: visibility=${root.windowVisibility} focus=${root.hasWindowFocus()} shown=${root.isShown}")
				append("\nbefore heal (view -> window):\n").append(before)
				append("\nafter heal:\n").append(describeChain(view))
			}
			Log.w(TAG, message)

			// The window can also go between the two checks, when the TV goes off just after the
			// first one. The re-check then says nothing about the heal, so it keeps the report.
			if (isWindowHidden(root.windowVisibility, root.isShown)) {
				Log.i(TAG, "$TAG skipped hidden $label window=${root.windowVisibility} at re-check")
				if (report) reported = false
				return@postDelayed
			}

			if (report) ACRA.errorReporter.handleSilentException(IllegalStateException(message))
		}, DELAY_MS)
	}

	internal fun isWindowHidden(windowVisibility: Int, isShown: Boolean): Boolean =
		windowVisibility != View.VISIBLE || !isShown

	/**
	 * One line per view from [view] up to the window: size as laid out and as measured, whether a
	 * layout is pending, and visibility. Where the chain stops being sized, or stops being flagged,
	 * is where the lost pass is.
	 */
	private fun describeChain(view: View): String = buildString {
		var current: View? = view
		while (current != null) {
			append("  ${current.javaClass.name}#${idName(current)} ")
			append("${current.width}x${current.height} measured=${current.measuredWidth}x${current.measuredHeight} ")
			append("layoutRequested=${current.isLayoutRequested} visibility=${current.visibility}")
			if (current is ViewGroup) append(" children=${current.childCount}")
			append('\n')
			current = current.parent as? View
		}
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

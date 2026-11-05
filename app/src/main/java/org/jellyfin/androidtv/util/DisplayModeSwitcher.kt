package org.jellyfin.androidtv.util

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.preference.constant.RefreshRateSwitchingBehavior
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Switches the display to the mode that matches a video's frame rate, through the window's
 * preferredDisplayModeId (the only way to force a non-seamless HDMI mode change on API 30-31).
 *
 * The HDMI link re-syncs during the switch, so callers wait for [apply] to return before
 * preparing playback or starting the display-link monitors.
 */
class DisplayModeSwitcher(
	private val activity: Activity,
	private val behavior: RefreshRateSwitchingBehavior,
	private val modeTimeout: Duration = 8.seconds,
	private val settleDelay: Duration = 5.seconds,
) {
	private companion object {
		/**
		 * The switcher whose mode the window holds. A player opened before the previous one is
		 * destroyed takes this over, so the old player's [reset] can no longer undo the new mode.
		 */
		@Volatile
		private var owner: DisplayModeSwitcher? = null
	}

	@Suppress("DEPRECATION")
	private val display: Display?
		get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
		else activity.windowManager.defaultDisplay

	/** The mode [item] should play in, or null when no switch is wanted or possible. */
	fun targetFor(item: BaseItemDto?): Int? {
		if (behavior == RefreshRateSwitchingBehavior.DISABLED) return null
		val display = display ?: run {
			Timber.w("No display to switch the mode of")
			return null
		}
		val video = item?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
		val target = DisplayModePicker.pick(
			modes = display.supportedModes.map { it.toPickerMode() },
			current = display.mode.toPickerMode(),
			videoWidth = video?.width,
			videoHeight = video?.height,
			frameRate = video?.realFrameRate ?: video?.averageFrameRate,
			behavior = behavior,
		)
		Timber.i("Display mode for %sx%s@%s: %s (current %d)", video?.width, video?.height, video?.realFrameRate, target, display.mode.modeId)
		// Already in the right mode: still claim the window, so an older player's reset can't undo it.
		if (target == null) owner = this
		return target
	}

	/**
	 * Request [modeId]. Suspends until the display reports it and the HDMI link has had
	 * [settleDelay] to come back.
	 */
	suspend fun apply(modeId: Int) {
		owner = this
		setPreferredMode(modeId)

		val reached = withTimeoutOrNull(modeTimeout) { awaitMode(modeId) } != null
		Timber.i("Display mode %d %s", modeId, if (reached) "reached" else "not reached in $modeTimeout")
		delay(settleDelay)
	}

	/** [targetFor] then [apply]. Returns true when a switch was made. */
	suspend fun switchFor(item: BaseItemDto?): Boolean {
		val target = targetFor(item) ?: return false
		apply(target)
		return true
	}

	/** Hand the display back to the system default mode, unless a newer player has taken it over. */
	fun reset() {
		if (owner !== this) return
		owner = null
		if (activity.window?.attributes?.preferredDisplayModeId == 0) return
		setPreferredMode(0)
	}

	private fun setPreferredMode(modeId: Int) {
		val window = activity.window ?: return
		val params = window.attributes
		params.preferredDisplayModeId = modeId
		window.attributes = params
	}

	private suspend fun awaitMode(modeId: Int) {
		val displayManager = activity.getSystemService(DisplayManager::class.java) ?: return
		suspendCancellableCoroutine { continuation ->
			val listener = object : DisplayManager.DisplayListener {
				override fun onDisplayAdded(displayId: Int) = Unit
				override fun onDisplayRemoved(displayId: Int) = Unit
				override fun onDisplayChanged(displayId: Int) {
					if (display?.mode?.modeId == modeId && continuation.isActive) {
						displayManager.unregisterDisplayListener(this)
						continuation.resume(Unit)
					}
				}
			}
			displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
			continuation.invokeOnCancellation { displayManager.unregisterDisplayListener(listener) }
			if (display?.mode?.modeId == modeId) {
				displayManager.unregisterDisplayListener(listener)
				continuation.resume(Unit)
			}
		}
	}

	private fun Display.Mode.toPickerMode() = DisplayModePicker.Mode(modeId, physicalWidth, physicalHeight, refreshRate)
}

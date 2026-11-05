package org.jellyfin.androidtv.util

import android.app.Activity
import android.hardware.display.DisplayManager
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
 * The HDMI link re-syncs during the switch, so callers wait for [switchFor] to return before
 * starting playback or the display-link monitors.
 */
class DisplayModeSwitcher(
	private val activity: Activity,
	private val behavior: RefreshRateSwitchingBehavior,
	private val modeTimeout: Duration = 8.seconds,
	private val settleDelay: Duration = 3.seconds,
) {
	private val display: Display? get() = activity.window?.decorView?.display

	/**
	 * Request the best mode for [item]. Suspends until the display reports it and the HDMI link has
	 * had [settleDelay] to come back. Returns true when a switch was requested.
	 */
	suspend fun switchFor(item: BaseItemDto?): Boolean {
		val display = display ?: return false
		val video = item?.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
		val current = display.mode.toPickerMode()
		val target = DisplayModePicker.pick(
			modes = display.supportedModes.map { it.toPickerMode() },
			current = current,
			videoWidth = video?.width,
			videoHeight = video?.height,
			frameRate = video?.realFrameRate ?: video?.averageFrameRate,
			behavior = behavior,
		) ?: return false

		Timber.i("Switching display mode %d -> %d for %s fps", current.id, target, video?.realFrameRate)
		setPreferredMode(target)

		val reached = withTimeoutOrNull(modeTimeout) { awaitMode(target) } != null
		Timber.i("Display mode %d %s", target, if (reached) "reached" else "not reached in $modeTimeout")
		delay(settleDelay)
		return true
	}

	/** Hand the display back to the system default mode. */
	fun reset() {
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

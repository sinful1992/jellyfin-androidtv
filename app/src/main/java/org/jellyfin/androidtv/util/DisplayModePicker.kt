package org.jellyfin.androidtv.util

import org.jellyfin.androidtv.preference.constant.RefreshRateSwitchingBehavior
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Picks the display mode a video should play in, so 23.976 fps films are not shown at 59.94 Hz.
 *
 * Pure: the caller passes the display's modes and the video's size and frame rate.
 */
object DisplayModePicker {
	data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

	/**
	 * @return the id of the mode to switch to, or null to stay in [current].
	 */
	fun pick(
		modes: List<Mode>,
		current: Mode,
		videoWidth: Int?,
		videoHeight: Int?,
		frameRate: Float?,
		behavior: RefreshRateSwitchingBehavior,
	): Int? {
		if (behavior == RefreshRateSwitchingBehavior.DISABLED) return null
		if (frameRate == null || frameRate <= 0f || frameRate.isNaN()) return null
		val width = videoWidth?.takeIf { it > 0 } ?: return null
		val height = videoHeight?.takeIf { it > 0 } ?: return null

		val sourceRate = (frameRate * 100).roundToInt()

		var best: Mode? = null
		var bestWeight = Int.MIN_VALUE
		for (mode in modes) {
			if (mode.width < 1280 || mode.height < 720) continue

			val nativeResolution = mode.width == current.width && mode.height == current.height
			if (behavior == RefreshRateSwitchingBehavior.SCALE_ON_DEVICE && !nativeResolution) continue
			if (behavior == RefreshRateSwitchingBehavior.SCALE_ON_TV && (mode.width < width || mode.height < height)) continue

			val rate = (mode.refreshRate * 100).roundToInt()
			if (!rateMatches(rate, sourceRate)) continue

			val resolutionDifference = when (behavior) {
				RefreshRateSwitchingBehavior.SCALE_ON_TV -> abs(mode.width - width)
				else -> 0
			}
			val weight = -(rate - sourceRate) * 10 - resolutionDifference
			if (weight > bestWeight) {
				bestWeight = weight
				best = mode
			}
		}

		val chosen = best ?: return null
		return if (chosen.id == current.id) null else chosen.id
	}

	// Rates are in hundredths of a hertz. One hundredth of slack absorbs rounding: 23.976 x 2.5 is
	// 5994.0 at the source's precision but 5995 after rounding 2398 up first.
	private fun rateMatches(rate: Int, sourceRate: Int): Boolean =
		listOf(sourceRate, sourceRate * 2, (sourceRate * 2.5f).roundToInt()).any { abs(rate - it) <= 1 }
}

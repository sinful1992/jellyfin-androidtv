package org.jellyfin.playback.media3.exoplayer

/**
 * Tells when an audio clock falls behind real time while it plays.
 *
 * Measured on a Bravia passing DTS-HD to a soundbar: the clock lost 80-300 ms every few seconds on
 * heavy passages, 4.6 s in the worst minute, while every clean run lost at most 0.02 s in any
 * minute. A loss of [thresholdUs] within [windowUs] sits far from both.
 *
 * Fed from one thread. [reset] whenever the clock may jump or pause for a reason of its own: a
 * seek, a pause, a rebuffer, a new format.
 */
class ClockStallDetector(
	private val windowUs: Long = 60_000_000,
	private val thresholdUs: Long = 1_000_000,
	private val warmupUs: Long = 15_000_000,
	private val sampleIntervalUs: Long = 200_000,
) {
	private class Sample(val wallUs: Long, val positionUs: Long)

	private val samples = ArrayDeque<Sample>()
	private var startWallUs: Long? = null
	private var speed = 1f

	fun reset() {
		samples.clear()
		startWallUs = null
	}

	/**
	 * Adds a reading of the clock at [positionUs] taken at [wallUs] of real time, and returns
	 * whether the clock has lost [thresholdUs] or more over the last [windowUs].
	 */
	fun sample(wallUs: Long, positionUs: Long, playbackSpeed: Float = 1f): Boolean {
		if (playbackSpeed != speed) {
			speed = playbackSpeed
			reset()
		}

		val start = startWallUs ?: wallUs.also { startWallUs = it }
		// The start of playback settles the clock in on its own terms.
		if (wallUs - start < warmupUs) return false

		val last = samples.lastOrNull()
		if (last != null && wallUs - last.wallUs < sampleIntervalUs) return false
		samples.addLast(Sample(wallUs, positionUs))

		// Keep one sample at or before the start of the window to measure from.
		while (samples.size > 1 && wallUs - samples[1].wallUs >= windowUs) samples.removeFirst()

		val first = samples.first()
		val lostUs = ((wallUs - first.wallUs) * speed).toLong() - (positionUs - first.positionUs)
		return lostUs >= thresholdUs
	}
}

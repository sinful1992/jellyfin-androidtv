package org.jellyfin.playback.media3.exoplayer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val SECOND = 1_000_000L

/**
 * Plays [seconds] of real time in 100 ms steps, the clock losing [lossPerStepUs] each step, and
 * returns the first second at which the detector fired, or null.
 */
private fun ClockStallDetector.run(seconds: Int, lossPerStepUs: (Int) -> Long, startWallUs: Long = 0, startPositionUs: Long = 0): Int? {
	var position = startPositionUs
	for (step in 0..seconds * 10) {
		val wall = startWallUs + step * SECOND / 10
		if (step > 0) position += SECOND / 10 - lossPerStepUs(step)
		if (sample(wall, position)) return step / 10
	}
	return null
}

class ClockStallDetectorTests : FunSpec({
	test("a clock that keeps time never fires") {
		ClockStallDetector().run(600, { 0 }) shouldBe null
	}

	test("a clean run's jitter never fires") {
		// 0.02 s in a minute, the most any clean run lost.
		ClockStallDetector().run(600, { if (it % 300 == 0) 20_000 else 0 }) shouldBe null
	}

	test("Tangled on DTS-HD fires within its first minute") {
		// 200 ms lost every 3 s, as the Bravia did on the heavy passages.
		val fired = ClockStallDetector().run(120, { if (it % 30 == 0) 200_000 else 0 })
		(fired != null && fired in 15..60) shouldBe true
	}

	test("nothing lost in the warm-up counts") {
		// 5 s lost in the first 10 s, then clean.
		ClockStallDetector().run(120, { if (it in 1..10) 500_000 else 0 }) shouldBe null
	}

	test("loss spread thinner than the threshold per window never fires") {
		// 0.5 s per minute.
		ClockStallDetector().run(600, { if (it % 600 == 0) 500_000 else 0 }) shouldBe null
	}

	test("reset starts over, warm-up included") {
		val detector = ClockStallDetector()
		detector.run(50, { if (it % 30 == 0) 200_000 else 0 })
		detector.reset()
		// A stall right after a reset is the warm-up's.
		detector.run(10, { 300_000 }, startWallUs = 100 * SECOND) shouldBe null
	}

	test("the clock running at another speed is measured at that speed") {
		val detector = ClockStallDetector()
		var position = 0L
		var fired = false
		for (step in 0..1200) {
			if (step > 0) position += (SECOND / 10 * 1.25).toLong()
			fired = fired || detector.sample(step * SECOND / 10, position, playbackSpeed = 1.25f)
		}
		fired shouldBe false
	}
})

package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.RefreshRateSwitchingBehavior
import org.jellyfin.androidtv.util.DisplayModePicker.Mode

// The 23 modes the Shield tube reported on the living-room X75WL via the HT-A7000 (task 125, dumpsys display).
private val shieldModes = listOf(
	Mode(1, 1280, 720, 29.97003f), Mode(2, 1280, 720, 23.976025f), Mode(3, 1920, 1080, 29.97003f),
	Mode(4, 1920, 1080, 23.976025f), Mode(5, 1280, 720, 59.94006f), Mode(6, 1920, 1080, 59.94006f),
	Mode(7, 3840, 2160, 29.97003f), Mode(8, 3840, 2160, 23.976025f), Mode(9, 720, 480, 60.000004f),
	Mode(10, 1280, 720, 30.000002f), Mode(11, 1280, 720, 24.000002f), Mode(12, 1920, 1080, 30.000002f),
	Mode(13, 1920, 1080, 24.000002f), Mode(14, 1280, 720, 50.0f), Mode(15, 1280, 720, 60.000004f),
	Mode(16, 1920, 1080, 50.0f), Mode(17, 3840, 2160, 30.000002f), Mode(18, 3840, 2160, 25.0f),
	Mode(19, 3840, 2160, 24.000002f), Mode(20, 3840, 2160, 50.0f), Mode(21, 1920, 1080, 60.000004f),
	Mode(22, 3840, 2160, 60.000004f), Mode(23, 3840, 2160, 59.94006f),
)
private val current = shieldModes.first { it.id == 23 }
private val device = RefreshRateSwitchingBehavior.SCALE_ON_DEVICE

private fun pick(w: Int?, h: Int?, fps: Float?, behavior: RefreshRateSwitchingBehavior = device, from: Mode = current) =
	DisplayModePicker.pick(shieldModes, from, w, h, fps, behavior)

class DisplayModePickerTests : FunSpec({
	test("4K 23.976 film goes to 2160p23.976") { pick(3840, 2160, 23.976f) shouldBe 8 }
	test("24.000 fps goes to 2160p24, not 23.976") { pick(3840, 2160, 24f) shouldBe 19 }
	test("25 fps UK episode goes to 2160p25, not 50") { pick(1920, 1080, 25f) shouldBe 18 }
	test("29.97 fps goes to 2160p29.97") { pick(1440, 1080, 29.97f) shouldBe 7 }
	test("59.94 content stays put") { pick(3840, 2160, 59.94f) shouldBe null }
	test("50 fps goes to 2160p50") { pick(1920, 1080, 50f) shouldBe 20 }
	test("1080p content keeps the 2160 output when scaling on device") { pick(1920, 1080, 23.976f) shouldBe 8 }
	test("scaling on the TV picks 1080p23.976 for 1080p content") {
		pick(1920, 1080, 23.976f, RefreshRateSwitchingBehavior.SCALE_ON_TV) shouldBe 4
	}
	test("scaling on the TV never drops below the video size") {
		pick(3840, 2160, 23.976f, RefreshRateSwitchingBehavior.SCALE_ON_TV) shouldBe 8
	}
	test("disabled never switches") { pick(3840, 2160, 23.976f, RefreshRateSwitchingBehavior.DISABLED) shouldBe null }
	test("unknown frame rate or size stays put") {
		pick(3840, 2160, null) shouldBe null
		pick(3840, 2160, 0f) shouldBe null
		pick(3840, 2160, Float.NaN) shouldBe null
		pick(null, 2160, 23.976f) shouldBe null
		pick(0, 0, 23.976f) shouldBe null
	}
	test("a rate no mode offers stays put") { pick(3840, 2160, 17f) shouldBe null }
	test("12.5 fps doubles to 2160p25") { pick(3840, 2160, 12.5f) shouldBe 18 }
	test("already in the best mode returns null") { pick(3840, 2160, 23.976f, from = shieldModes.first { it.id == 8 }) shouldBe null }
	test("23.976 with no 24 Hz mode falls back to 59.94 (2.5x)") {
		val no24 = shieldModes.filter { it.refreshRate < 23f || it.refreshRate > 25f }
		DisplayModePicker.pick(no24, shieldModes.first { it.id == 22 }, 3840, 2160, 23.976f, device) shouldBe 23
	}
	test("mode ids re-numbered after an HDMI replug still resolve by fps") {
		val renumbered = shieldModes.map { it.copy(id = it.id + 22) }
		DisplayModePicker.pick(renumbered, renumbered.last(), 3840, 2160, 23.976f, device) shouldBe 30
	}
	test("empty mode list stays put") {
		DisplayModePicker.pick(emptyList(), current, 3840, 2160, 23.976f, device) shouldBe null
	}
})

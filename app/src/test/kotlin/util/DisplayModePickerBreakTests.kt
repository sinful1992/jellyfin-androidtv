package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.RefreshRateSwitchingBehavior
import org.jellyfin.androidtv.util.DisplayModePicker.Mode

// Same 23 modes as DisplayModePickerTests. Since task 127 only 23.976/24 fps switch; the rest must stay put.
// 3 disabled tests are hand-traced to FAIL against 256fd3be5, named "EXPECTED FAIL".
private val modes = listOf(
	Mode(1, 1280, 720, 29.97003f), Mode(2, 1280, 720, 23.976025f), Mode(3, 1920, 1080, 29.97003f),
	Mode(4, 1920, 1080, 23.976025f), Mode(5, 1280, 720, 59.94006f), Mode(6, 1920, 1080, 59.94006f),
	Mode(7, 3840, 2160, 29.97003f), Mode(8, 3840, 2160, 23.976025f), Mode(9, 720, 480, 60.000004f),
	Mode(10, 1280, 720, 30.000002f), Mode(11, 1280, 720, 24.000002f), Mode(12, 1920, 1080, 30.000002f),
	Mode(13, 1920, 1080, 24.000002f), Mode(14, 1280, 720, 50.0f), Mode(15, 1280, 720, 60.000004f),
	Mode(16, 1920, 1080, 50.0f), Mode(17, 3840, 2160, 30.000002f), Mode(18, 3840, 2160, 25.0f),
	Mode(19, 3840, 2160, 24.000002f), Mode(20, 3840, 2160, 50.0f), Mode(21, 1920, 1080, 60.000004f),
	Mode(22, 3840, 2160, 60.000004f), Mode(23, 3840, 2160, 59.94006f),
)
private fun mode(id: Int) = modes.first { it.id == id }
private val device = RefreshRateSwitchingBehavior.SCALE_ON_DEVICE
private val tv = RefreshRateSwitchingBehavior.SCALE_ON_TV

private fun dev(w: Int?, h: Int?, fps: Float?, from: Int = 23) =
	DisplayModePicker.pick(modes, mode(from), w, h, fps, device)

private fun onTv(w: Int?, h: Int?, fps: Float?, from: Int = 23) =
	DisplayModePicker.pick(modes, mode(from), w, h, fps, tv)

class DisplayModePickerBreakTests : FunSpec({
	context("fractional and exact rates, 4K output") {
		test("23.976 family lands on 23.976, never 24") {
			dev(3840, 2160, 23.976f) shouldBe 8
			dev(3840, 2160, 23.976025f) shouldBe 8
			dev(3840, 2160, 23.98f) shouldBe 8
			dev(3840, 2160, 23.97f) shouldBe 8
		}
		test("24 lands on 24, never 23.976") {
			dev(3840, 2160, 24f) shouldBe 19
			dev(3840, 2160, 24.000002f) shouldBe 19
		}
		test("25 and 50 fps go to 50 Hz, never 25 (USER, task 127)") {
			dev(3840, 2160, 25f) shouldBe 20
			dev(3840, 2160, 25.0f) shouldBe 20
			dev(3840, 2160, 50f) shouldBe 20
			dev(3840, 2160, 25f, from = 20) shouldBe null
			dev(3840, 2160, 25f, from = 18) shouldBe 20
		}
		test("25 fps with no 50 Hz mode stays") {
			val no50 = modes.filter { it.refreshRate < 49f || it.refreshRate > 51f }
			DisplayModePicker.pick(no50, mode(23), 3840, 2160, 25f, device) shouldBe null
		}
		test("other non-film rates never switch (USER, task 127)") {
			dev(3840, 2160, 29.97f) shouldBe null
			dev(3840, 2160, 30f) shouldBe null
			dev(3840, 2160, 24.9f) shouldBe null
			dev(3840, 2160, 25.1f) shouldBe null
			dev(3840, 2160, 60f) shouldBe null
			dev(3840, 2160, 59.94f, from = 22) shouldBe null
			dev(3840, 2160, 23.95f) shouldBe null
			dev(3840, 2160, 24.05f) shouldBe null
		}
		test("a film still switches from a non-59.94 output") {
			dev(3840, 2160, 23.976f, from = 18) shouldBe 8
			dev(3840, 2160, 24f, from = 20) shouldBe 19
		}
		test("already in the right mode stays") {
			dev(3840, 2160, 59.94f, from = 23) shouldBe null
			dev(3840, 2160, 60f, from = 22) shouldBe null
			dev(3840, 2160, 25f, from = 20) shouldBe null
		}
		test("12 and 15 are not film rates and stay") {
			dev(3840, 2160, 12f) shouldBe null
			dev(3840, 2160, 15f) shouldBe null
		}
		test("no mode for the rate or a multiple stays") {
			dev(3840, 2160, 48f) shouldBe null
			dev(3840, 2160, 47.952f) shouldBe null
		}
		test("24 with no 24 Hz mode falls back to 60 (2.5x)") {
			val without = modes.filter { it.refreshRate < 23.5f || it.refreshRate > 24.5f }
			DisplayModePicker.pick(without, mode(23), 3840, 2160, 24f, device) shouldBe 22
		}
	}

	context("missing and junk data never switches and never throws") {
		test("frame rate") {
			dev(3840, 2160, null) shouldBe null
			dev(3840, 2160, 0f) shouldBe null
			dev(3840, 2160, -23.976f) shouldBe null
			dev(3840, 2160, Float.NaN) shouldBe null
			dev(3840, 2160, Float.POSITIVE_INFINITY) shouldBe null
			dev(3840, 2160, 0.001f) shouldBe null
		}
		test("size") {
			dev(null, 2160, 23.976f) shouldBe null
			dev(3840, null, 23.976f) shouldBe null
			dev(0, 2160, 23.976f) shouldBe null
			dev(3840, 0, 23.976f) shouldBe null
			dev(-1, -1, 23.976f) shouldBe null
		}
		test("disabled wins over everything") {
			DisplayModePicker.pick(modes, mode(23), 3840, 2160, 23.976f, RefreshRateSwitchingBehavior.DISABLED) shouldBe null
		}
		test("no usable modes") {
			DisplayModePicker.pick(emptyList(), mode(23), 3840, 2160, 23.976f, device) shouldBe null
			dev(3840, 2160, 23.976f, from = 9) shouldBe null
		}
	}

	context("modes re-numbered") {
		val renumbered = modes.reversed().map { it.copy(id = 100 - it.id) }
		fun re(id: Int) = renumbered.first { it.id == 100 - id }

		test("the rate decides, not the id or the list order") {
			DisplayModePicker.pick(renumbered, re(23), 3840, 2160, 23.976f, device) shouldBe 92
			DisplayModePicker.pick(renumbered, re(23), 3840, 2160, 25f, device) shouldBe 80
			DisplayModePicker.pick(renumbered, re(23), 1920, 1080, 23.976f, tv) shouldBe 96
		}
		test("already there under the new ids stays") {
			DisplayModePicker.pick(renumbered, re(8), 3840, 2160, 23.976f, device) shouldBe null
		}
		test("a current mode whose id is stale still resolves") {
			val stale = Mode(999, 3840, 2160, 59.94006f)
			DisplayModePicker.pick(modes, stale, 3840, 2160, 23.976f, device) shouldBe 8
		}
	}

	context("1440x1080") {
		test("device scaling keeps the 4K output") {
			dev(1440, 1080, 23.976f) shouldBe 8
			dev(1440, 1080, 25f) shouldBe 20
			dev(1440, 1080, 29.97f) shouldBe null
		}
		test("TV scaling takes 1080p where the rate exists") {
			onTv(1440, 1080, 23.976f) shouldBe 4
			onTv(1440, 1080, 24f) shouldBe 13
		}
		test("TV scaling sends 1440x1080 25 fps to 1080p50") { onTv(1440, 1080, 25f) shouldBe 16 }
	}

	context("SCALE_ON_TV against SCALE_ON_DEVICE") {
		test("same film, different output") {
			onTv(1920, 1080, 23.976f) shouldBe 4
			dev(1920, 1080, 23.976f) shouldBe 8
			onTv(1280, 720, 23.976f) shouldBe 2
			dev(1280, 720, 23.976f) shouldBe 8
		}
		test("TV never goes below the video size") {
			onTv(3840, 2160, 23.976f) shouldBe 8
			onTv(3840, 1600, 23.976f) shouldBe 8
		}
		test("scope ratio uses the width") {
			onTv(1920, 800, 23.976f) shouldBe 4
			onTv(1280, 536, 23.976f) shouldBe 2
		}
		test("SD film on TV: no 720x480 output, smallest mode with the rate") {
			onTv(720, 480, 23.976f) shouldBe 2
			onTv(720, 480, 29.97f) shouldBe null
		}
		test("PAL SD and 720p at 25 go to 720p50") {
			onTv(720, 576, 25f) shouldBe 14
			onTv(1280, 720, 25f) shouldBe 14
		}
		test("device scaling from a 1080p output stays at 1080p") {
			dev(3840, 2160, 23.976f, from = 21) shouldBe 4
			dev(1920, 1080, 25f, from = 21) shouldBe 16
		}
		test("TV scaling at the current mode stays") { onTv(1920, 1080, 23.976f, from = 4) shouldBe null }
	}

	// Outside the task 125 contract (DCI width on TV scaling, a contrived tie, another-size fallback):
	// these fail on the current picker by design and are kept as a record, disabled.
	xcontext("expected to fail on 256fd3be5") {
		// mode.width < width drops every mode, so DCI-width films never switch under SCALE_ON_TV.
		test("EXPECTED FAIL: DCI 4K on TV scaling picks the largest output at the film's rate") {
			onTv(4096, 2160, 23.976f) shouldBe 8
			onTv(4096, 1716, 23.976f) shouldBe 8
			onTv(3996, 2160, 24f) shouldBe 19
		}
		// weight > bestWeight keeps the first of a tie, even when the second is the current mode.
		test("EXPECTED FAIL: a tie with the current mode does not switch") {
			val twin = Mode(30, 3840, 2160, 59.94006f)
			DisplayModePicker.pick(listOf(mode(23), twin), twin, 3840, 2160, 59.94f, device) shouldBe null
		}
		// The enum's KDoc says "otherwise use difference in resolution"; the picker filters instead.
		test("EXPECTED FAIL: device scaling leaves the native size when only another size has the rate") {
			val only = listOf(Mode(1, 1920, 1080, 60f), Mode(2, 3840, 2160, 23.976025f))
			DisplayModePicker.pick(only, only[0], 1920, 1080, 23.976f, device) shouldBe 2
		}
	}
})

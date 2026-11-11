package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LeaveWhenShownTests : FunSpec({
	test("a link lost while the player is shown leaves at once") {
		var left = 0
		val leave = LeaveWhenShown()
		leave.linkLost(hidden = false) { left++ }
		left shouldBe 1
		leave.isDue shouldBe false
	}

	test("a link lost while the player is paused or stopped does not navigate in the hidden window") {
		var left = 0
		val leave = LeaveWhenShown()
		leave.linkLost(hidden = true) { left++ }
		left shouldBe 0
		leave.isDue shouldBe true
	}

	test("the held leave runs once the player is shown again") {
		var left = 0
		val leave = LeaveWhenShown()
		leave.linkLost(hidden = true) { left++ }
		leave.shown { left++ }
		left shouldBe 1
		leave.isDue shouldBe false
	}

	test("being shown twice leaves only once") {
		var left = 0
		val leave = LeaveWhenShown()
		leave.linkLost(hidden = true) { left++ }
		leave.shown { left++ }
		leave.shown { left++ }
		left shouldBe 1
	}

	test("being shown with no link lost does nothing") {
		var left = 0
		val leave = LeaveWhenShown()
		leave.shown { left++ }
		left shouldBe 0
	}

	test("a second loss while stopped still leaves once") {
		var left = 0
		val leave = LeaveWhenShown()
		leave.linkLost(hidden = true) { left++ }
		leave.linkLost(hidden = true) { left++ }
		leave.shown { left++ }
		left shouldBe 1
	}
})

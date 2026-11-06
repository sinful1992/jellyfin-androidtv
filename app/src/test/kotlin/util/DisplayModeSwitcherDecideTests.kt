package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.util.DisplayModeSwitcher.Action

class DisplayModeSwitcherDecideTests : FunSpec({
	test("a 29.97 show opened over a film's 24 Hz mode goes back to the default mode") {
		// linus #8873: the old player's reset is a no-op once the new one owns the window.
		DisplayModeSwitcher.decide(picked = null, currentId = 19, windowPreferred = 19) shouldBe Action.Default
	}

	test("no mode wanted and nothing changed by an earlier player keeps the display") {
		DisplayModeSwitcher.decide(picked = null, currentId = 23, windowPreferred = 0) shouldBe Action.Keep
	}

	test("already in the picked mode keeps it") {
		DisplayModeSwitcher.decide(picked = 8, currentId = 8, windowPreferred = 8) shouldBe Action.Keep
		DisplayModeSwitcher.decide(picked = 23, currentId = 23, windowPreferred = 0) shouldBe Action.Keep
	}

	test("a different picked mode switches, whatever the window held") {
		DisplayModeSwitcher.decide(picked = 8, currentId = 23, windowPreferred = 0) shouldBe Action.Switch(8)
		DisplayModeSwitcher.decide(picked = 20, currentId = 8, windowPreferred = 8) shouldBe Action.Switch(20)
	}
})

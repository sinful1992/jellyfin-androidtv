package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HomeRowAlignmentTests : FunSpec({
	test("a selected card row sits one title down, so its title is not cut off") {
		homeRowAlignment(heroSelected = false, rowTitleHeight = 42) shouldBe 42
	}

	test("the hero sits at the top, so the first row's title shows under it") {
		homeRowAlignment(heroSelected = true, rowTitleHeight = 42) shouldBe 0
	}

	test("an unmeasured title never pushes rows above the list") {
		homeRowAlignment(heroSelected = false, rowTitleHeight = -5) shouldBe 0
		homeRowAlignment(heroSelected = false, rowTitleHeight = 0) shouldBe 0
	}
})

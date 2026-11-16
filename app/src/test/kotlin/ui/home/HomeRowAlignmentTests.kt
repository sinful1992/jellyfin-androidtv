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

	context("putting the focus on the hero at start") {
		test("takes it when the hero row is attached and the viewer is on row 0 or 1") {
			heroStartFocusStep(selectedPosition = 1, heroReady = true, heroHasFocus = false, frames = 0) shouldBe HeroStartFocus.Take
			heroStartFocusStep(selectedPosition = 0, heroReady = true, heroHasFocus = false, frames = 5) shouldBe HeroStartFocus.Take
		}

		test("waits while the hero row is not attached yet") {
			heroStartFocusStep(selectedPosition = 1, heroReady = false, heroHasFocus = false, frames = 3) shouldBe HeroStartFocus.Wait
		}

		test("never pulls the focus back up once the viewer has pressed Down past row 1") {
			heroStartFocusStep(selectedPosition = 2, heroReady = true, heroHasFocus = false, frames = 4) shouldBe HeroStartFocus.Stop
			heroStartFocusStep(selectedPosition = 2, heroReady = false, heroHasFocus = false, frames = 0) shouldBe HeroStartFocus.Stop
		}

		test("stops once the hero holds the focus") {
			heroStartFocusStep(selectedPosition = 0, heroReady = true, heroHasFocus = true, frames = 1) shouldBe HeroStartFocus.Stop
		}

		test("gives up after the frame limit") {
			heroStartFocusStep(selectedPosition = 1, heroReady = false, heroHasFocus = false, frames = 30) shouldBe HeroStartFocus.Stop
			heroStartFocusStep(selectedPosition = 1, heroReady = true, heroHasFocus = false, frames = 29) shouldBe HeroStartFocus.Take
		}
	}
})

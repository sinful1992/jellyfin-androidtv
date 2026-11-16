package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe

class HomeRowAlignmentTests : FunSpec({
	test("a selected card row sits one title down, so its title is not cut off") {
		homeRowAlignment(heroSelected = false, rowTitleHeight = 42, rowsOverlap = 112) shouldBe 42
	}

	test("the hero sits below the info block's button strip, so the hero screen does not move") {
		homeRowAlignment(heroSelected = true, rowTitleHeight = 42, rowsOverlap = 112) shouldBe 112
	}

	test("without a hero the first card row gets the card row offset, not the overlap") {
		homeRowAlignment(heroSelected = false, rowTitleHeight = 42, rowsOverlap = 112) shouldBe 42
	}

	test("an unmeasured title or overlap never pushes rows above the list") {
		homeRowAlignment(heroSelected = false, rowTitleHeight = -5, rowsOverlap = 112) shouldBe 0
		homeRowAlignment(heroSelected = false, rowTitleHeight = 0, rowsOverlap = 112) shouldBe 0
		homeRowAlignment(heroSelected = true, rowTitleHeight = 42, rowsOverlap = -3) shouldBe 0
	}

	context("the focused card's name fits on a 960x540dp TV (Shield and Bravia)") {
		// hero.64 on the Shield: the focused Next up card's name began at 1068 of 1080 px (534 of
		// 540 dp) and its two text lines take about 40 dp, so it ran about 34 dp off the bottom. The
		// rows must start at least that much higher, with a few dp to spare.
		val overshoot = 34.dp
		val margin = 8.dp

		test("the rows reach far enough up for the name to show") {
			(HomeRowsOverlap >= overshoot + margin) shouldBe true
		}

		test("a row title in the button strip never covers the info text above it") {
			(HomeRowsOverlap < HomeHeroButtonStrip) shouldBe true
		}
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

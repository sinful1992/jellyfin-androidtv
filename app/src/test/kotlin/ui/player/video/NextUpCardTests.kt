package org.jellyfin.androidtv.ui.player.video

import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NextUpCardTests : FunSpec({
	test("the card keeps to well under half of a 960 dp TV") {
		(NextUpCardMaxWidth < 960.dp / 2) shouldBe true
	}

	test("the thumbnail is smaller than the 96 dp one that made the card tall") {
		(NextUpThumbnailHeight < 96.dp) shouldBe true
	}

	test("the name may wrap instead of being cut at one line") {
		(NextUpNameMaxLines > 1) shouldBe true
	}

	test("nothing open leaves the card where it is") {
		nextUpClearance(anchorBottom = 1000f, obstructionTop = null, gap = 32f) shouldBe 0f
	}

	test("a list opening over the card lifts it clear of the list's top edge") {
		// A list whose top is 400 px above the card's resting bottom edge, plus the gap.
		nextUpClearance(anchorBottom = 1000f, obstructionTop = 600f, gap = 32f) shouldBe 432f
	}

	test("something open below the card does not move it") {
		nextUpClearance(anchorBottom = 1000f, obstructionTop = 1100f, gap = 32f) shouldBe 0f
	}
})

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
})

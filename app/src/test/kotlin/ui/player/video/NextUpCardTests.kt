package org.jellyfin.androidtv.ui.player.video

import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NextUpCardTests : FunSpec({
	test("a 960 dp wide TV keeps the floor width") {
		nextUpCardMaxWidth(960.dp) shouldBe 560.dp
	}

	test("a narrow window keeps the floor width") {
		nextUpCardMaxWidth(640.dp) shouldBe 560.dp
	}

	test("a wider window grows the card with it") {
		nextUpCardMaxWidth(1280.dp) shouldBe 640.dp
	}

	test("the name may wrap instead of being cut at one line") {
		(NextUpNameMaxLines > 1) shouldBe true
	}
})

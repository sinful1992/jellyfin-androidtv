package org.jellyfin.androidtv.ui.navigation.layout

import android.view.View
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class BlackScreenHealTests : FunSpec({
	test("a window gone with the app in the background is skipped") {
		isWindowHidden(View.GONE, isShown = false) shouldBe true
	}

	test("a window made invisible as the TV goes off is skipped") {
		isWindowHidden(View.INVISIBLE, isShown = false) shouldBe true
	}

	test("a visible window under a hidden decor view is skipped") {
		isWindowHidden(View.VISIBLE, isShown = false) shouldBe true
	}

	test("a shown window is still healed and reported") {
		isWindowHidden(View.VISIBLE, isShown = true) shouldBe false
	}
})

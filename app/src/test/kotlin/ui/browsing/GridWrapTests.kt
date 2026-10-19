package org.jellyfin.androidtv.ui.browsing

import android.view.KeyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
private const val LEFT = KeyEvent.KEYCODE_DPAD_LEFT

class GridWrapTests : FunSpec({
	// 11 columns, 25 cards: lines 0-10, 11-21, 22-24.
	test("Right on the last card of a line goes to the first card of the next") {
		GridWrap.target(RIGHT, 10, 11, 25) shouldBe 11
		GridWrap.target(RIGHT, 21, 11, 25) shouldBe 22
	}

	test("Left on the first card of a line goes to the last card of the one before") {
		GridWrap.target(LEFT, 11, 11, 25) shouldBe 10
		GridWrap.target(LEFT, 22, 11, 25) shouldBe 21
	}

	test("inside a line the grid moves as before") {
		GridWrap.target(RIGHT, 5, 11, 25) shouldBe GridWrap.NONE
		GridWrap.target(LEFT, 5, 11, 25) shouldBe GridWrap.NONE
		GridWrap.target(RIGHT, 22, 11, 25) shouldBe GridWrap.NONE
	}

	test("nothing past the very first or very last card") {
		GridWrap.target(LEFT, 0, 11, 25) shouldBe GridWrap.NONE
		GridWrap.target(RIGHT, 24, 11, 25) shouldBe GridWrap.NONE
		GridWrap.target(RIGHT, 10, 11, 11) shouldBe GridWrap.NONE
	}

	test("other keys, no selection and an empty grid do nothing") {
		GridWrap.target(KeyEvent.KEYCODE_DPAD_DOWN, 10, 11, 25) shouldBe GridWrap.NONE
		GridWrap.target(RIGHT, -1, 11, 25) shouldBe GridWrap.NONE
		GridWrap.target(RIGHT, 0, 11, 0) shouldBe GridWrap.NONE
		GridWrap.target(RIGHT, 0, 0, 25) shouldBe GridWrap.NONE
	}
})

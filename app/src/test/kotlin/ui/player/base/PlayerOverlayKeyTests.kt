package org.jellyfin.androidtv.ui.player.base

import androidx.compose.ui.input.key.Key
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PlayerOverlayKeyTests : FunSpec({
	fun action(
		key: Key,
		down: Boolean = true,
		visible: Boolean,
		isSystem: Boolean = key == Key.Back,
		hasOnSelect: Boolean = true,
	) = playerOverlayKeyAction(
		isKeyDown = down,
		key = key,
		isSystem = isSystem,
		visible = visible,
		hasOnSelect = hasOnSelect,
	)

	// Task 196: Back down closed the controls, then Back up, read against the frame before,
	// restarted their timer and put them back, so Back could never leave the player.
	test("Back up with the controls still drawn as up does not bring them back") {
		action(Key.Back, down = false, visible = true) shouldBe PlayerOverlayKeyAction.Ignore
	}

	test("no key up acts, whatever the state") {
		for (key in listOf(Key.Back, Key.DirectionCenter, Key.Enter, Key.DirectionUp, Key.MediaPlayPause)) {
			for (visible in listOf(true, false)) {
				action(key, down = false, visible = visible) shouldBe PlayerOverlayKeyAction.Ignore
			}
		}
	}

	test("Back down closes the controls when they are up") {
		action(Key.Back, visible = true) shouldBe PlayerOverlayKeyAction.Hide
	}

	test("Back down with the controls down is left to leave the player") {
		action(Key.Back, visible = false) shouldBe PlayerOverlayKeyAction.Ignore
	}

	test("select with the controls down pauses or resumes and shows them") {
		action(Key.DirectionCenter, visible = false) shouldBe PlayerOverlayKeyAction.SelectAndShow
		action(Key.Enter, visible = false) shouldBe PlayerOverlayKeyAction.SelectAndShow
	}

	test("select with no select action only opens the controls") {
		action(Key.DirectionCenter, visible = false, hasOnSelect = false) shouldBe PlayerOverlayKeyAction.Show
	}

	test("select with the controls up belongs to the focused control") {
		action(Key.DirectionCenter, visible = true) shouldBe PlayerOverlayKeyAction.RestartTimer
	}

	test("a direction with the controls down opens them and is used up") {
		action(Key.DirectionLeft, visible = false) shouldBe PlayerOverlayKeyAction.Show
	}

	test("a direction with the controls up moves the focus and restarts the timer") {
		action(Key.DirectionLeft, visible = true) shouldBe PlayerOverlayKeyAction.RestartTimer
	}

	test("a system key with the controls down is not used up") {
		action(Key.VolumeUp, visible = false, isSystem = true) shouldBe PlayerOverlayKeyAction.Ignore
	}
})

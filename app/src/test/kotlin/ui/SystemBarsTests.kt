package org.jellyfin.androidtv.ui

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.kotest.core.spec.style.FunSpec
import io.mockk.mockk
import io.mockk.verify

class SystemBarsTests : FunSpec({
	test("hide hides both status and navigation bars, swipe brings them back for a moment") {
		val controller = mockk<WindowInsetsControllerCompat>(relaxed = true)
		SystemBars.hide(controller)
		verify { controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
		verify { controller.hide(WindowInsetsCompat.Type.systemBars()) }
	}

	test("regaining focus (dialog or keyboard closed) hides the bars again") {
		val controller = mockk<WindowInsetsControllerCompat>(relaxed = true)
		SystemBars.onFocusChanged(controller, hasFocus = true)
		verify(exactly = 1) { controller.hide(WindowInsetsCompat.Type.systemBars()) }
	}

	test("losing focus does nothing, so a dialog opening is not disturbed") {
		val controller = mockk<WindowInsetsControllerCompat>(relaxed = true)
		SystemBars.onFocusChanged(controller, hasFocus = false)
		verify(exactly = 0) { controller.hide(any()) }
		verify(exactly = 0) { controller.systemBarsBehavior = any() }
	}
})

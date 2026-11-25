package org.jellyfin.androidtv.ui

import android.view.View
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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

	test("a Compose dialog's own window is found from its content view") {
		val window = mockk<Window>()
		val parent = mockk<ViewParent>(moreInterfaces = arrayOf(DialogWindowProvider::class))
		every { (parent as DialogWindowProvider).window } returns window
		val view = mockk<View> { every { this@mockk.parent } returns parent }
		SystemBars.dialogWindowOf(view) shouldBe window
	}

	test("a view outside a dialog (preview, test host, no parent) gives no window instead of crashing") {
		SystemBars.dialogWindowOf(mockk<View> { every { parent } returns mockk<ViewParent>() }) shouldBe null
		SystemBars.dialogWindowOf(mockk<View> { every { parent } returns null }) shouldBe null
	}

	test("keepHidden hides at once, again when the dialog regains focus, and stops listening when disposed") {
		val controller = mockk<WindowInsetsControllerCompat>(relaxed = true)
		val listener = slot<ViewTreeObserver.OnWindowFocusChangeListener>()
		val observer = mockk<ViewTreeObserver>(relaxed = true) {
			every { addOnWindowFocusChangeListener(capture(listener)) } returns Unit
			every { isAlive } returns true
		}
		val view = mockk<View> { every { viewTreeObserver } returns observer }

		val stop = SystemBars.keepHidden(view, controller)
		verify(exactly = 1) { controller.hide(WindowInsetsCompat.Type.systemBars()) }

		listener.captured.onWindowFocusChanged(false) // keyboard or popup opens over the dialog
		verify(exactly = 1) { controller.hide(WindowInsetsCompat.Type.systemBars()) }

		listener.captured.onWindowFocusChanged(true) // it closed: the bars must go again
		verify(exactly = 2) { controller.hide(WindowInsetsCompat.Type.systemBars()) }

		stop()
		verify(exactly = 1) { observer.removeOnWindowFocusChangeListener(listener.captured) }
	}

	test("disposing after the view tree died does not touch the dead observer") {
		val observer = mockk<ViewTreeObserver>(relaxed = true) { every { isAlive } returns false }
		val view = mockk<View> { every { viewTreeObserver } returns observer }
		SystemBars.keepHidden(view, mockk(relaxed = true))()
		verify(exactly = 0) { observer.removeOnWindowFocusChangeListener(any()) }
	}
})

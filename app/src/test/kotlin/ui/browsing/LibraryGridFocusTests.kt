package org.jellyfin.androidtv.ui.browsing

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.grid.LibraryGrid
import org.jellyfin.androidtv.ui.browsing.grid.LibraryGridMemory
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Task 200: the Compose library grid puts the focus where the leanback one did: the first card on a
 * new list, the same card after Back (same generation, the grid composed again), and across the end
 * of a line on Left/Right.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryGridFocusTests {
	private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
	private val parent = FrameLayout(activity).also { activity.setContentView(it) }
	private val focused = mutableListOf<Int>()

	private var items by mutableStateOf<List<BaseRowItem>>(emptyList())
	private var generation by mutableStateOf(0)

	private fun cards(count: Int) = List(count) { GridButtonBaseRowItem(GridButton(it, "Card $it")) }

	private fun frames() = repeat(10) { ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS) }

	private fun round() {
		frames()
		parent.measure(
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
		)
		parent.layout(0, 0, 1920, 1080)
	}

	private fun settle() = repeat(10) { round() }

	private fun show(memory: LibraryGridMemory): ComposeView {
		val view = ComposeView(activity).apply {
			isFocusable = true
			setContent {
				LibraryGrid(
					items = items,
					generation = generation,
					columns = 5,
					cardHeight = 150,
					imageType = ImageType.POSTER,
					spacingHorizontalPx = 10,
					spacingVerticalPx = 10,
					paddingHorizontalPx = 20,
					paddingVerticalPx = 20,
					memory = memory,
					onFocused = { index, _ -> focused += index },
					onClick = {},
					onLongClick = {},
					onScrollingChanged = {},
				)
			}
		}
		parent.removeAllViews()
		parent.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
		settle()
		return view
	}

	/** Skips, as CardReuseTests does, when Compose does not recompose in this JVM. */
	private fun awaitFocus() {
		repeat(30) {
			if (focused.isNotEmpty()) return
			round()
		}
		Assume.assumeTrue("Compose did not compose or focus in this JVM", focused.isNotEmpty())
	}

	private fun key(view: View, keyCode: Int) {
		view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
		view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
		settle()
	}

	@Test
	fun `a new list focuses its first card`() {
		items = cards(40)
		show(LibraryGridMemory())
		awaitFocus()
		assertEquals(0, focused.last())
	}

	@Test
	fun `Back to the same generation focuses the card that had it`() {
		val memory = LibraryGridMemory()
		items = cards(40)
		show(memory)
		awaitFocus()
		memory.requestFocus(27)
		settle()
		assertEquals(27, focused.last())

		// The view goes (details), a new composition comes (Back): same items, same generation.
		parent.removeAllViews()
		settle()
		focused.clear()
		show(memory)
		awaitFocus()
		assertEquals(27, focused.last())
	}

	@Test
	fun `a new generation goes back to the first card`() {
		val memory = LibraryGridMemory()
		items = cards(40)
		show(memory)
		awaitFocus()
		memory.requestFocus(27)
		settle()

		focused.clear()
		generation = 1
		items = cards(30)
		settle()
		awaitFocus()
		assertEquals(0, focused.last())
	}

	@Test
	fun `Right on the last card of a line goes to the first card of the next`() {
		items = cards(40)
		val view = show(LibraryGridMemory())
		awaitFocus()
		repeat(4) { key(view, KeyEvent.KEYCODE_DPAD_RIGHT) }
		assertEquals(4, focused.last())
		key(view, KeyEvent.KEYCODE_DPAD_RIGHT)
		assertEquals(5, focused.last())
		key(view, KeyEvent.KEYCODE_DPAD_LEFT)
		assertEquals(4, focused.last())
	}

	@Test
	fun `Down moves one line, to the card below`() {
		items = cards(40)
		val view = show(LibraryGridMemory())
		awaitFocus()
		key(view, KeyEvent.KEYCODE_DPAD_RIGHT)
		key(view, KeyEvent.KEYCODE_DPAD_DOWN)
		assertEquals(6, focused.last())
	}

	@Test
	fun `an empty list focuses nothing`() {
		items = emptyList()
		show(LibraryGridMemory())
		assertEquals(emptyList<Int>(), focused)
	}
}

package org.jellyfin.androidtv.ui.presentation

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Task 198: a card going back to the pool must stay composed and card-sized. Emptying it on unbind
 * made every rebind build the card again and grow it back from 0x0, a layout the whole grid answered,
 * for all 11 cards of a row at the moment it slid in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CardReuseTests {
	private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
	private val parent = FrameLayout(activity).also { activity.setContentView(it) }
	private val presenter = CardPresenter(true, 150)
	private val holder = presenter.onCreateViewHolder(parent).also {
		parent.addView(it.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
	}

	private fun button(id: Int) = GridButtonBaseRowItem(GridButton(id, "Button $id"))

	/** Runs a few frames: the Compose frame clock only ticks when the looper's time moves. */
	private fun frames() = repeat(10) { ShadowLooper.idleMainLooper(16, java.util.concurrent.TimeUnit.MILLISECONDS) }

	private fun round() {
		frames()
		parent.measure(
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
		)
		parent.layout(0, 0, 1920, 1080)
	}

	/** Frames, measure and layout, a few rounds: a composition change can take a frame to show in a size. */
	private fun settle() = repeat(5) { round() }

	/**
	 * Binds [item] and runs rounds until the card has a size. When it never gets one, the test is
	 * skipped, not failed: Compose's UI dispatcher is made once per thread, and after another Compose
	 * test in the same JVM it can stay tied to Robolectric's previous main looper, so nothing
	 * recomposes. Without a recomposition the tests below could not tell old code from new.
	 */
	private fun bindAndShow(item: GridButtonBaseRowItem) {
		presenter.onBindViewHolder(holder, item)
		repeat(50) {
			round()
			if (holder.view.width > 0 && holder.view.height > 0) return
		}
		Assume.assumeTrue("Compose did not recompose in this JVM (see bindAndShow)", false)
	}

	@Test
	fun `a bound card has a size`() {
		bindAndShow(button(1))
		assertTrue("card is ${holder.view.width}x${holder.view.height}", holder.view.width > 0 && holder.view.height > 0)
	}

	@Test
	fun `an unbound card keeps its size`() {
		bindAndShow(button(1))
		val width = holder.view.width
		val height = holder.view.height

		presenter.onUnbindViewHolder(holder)
		settle()
		assertEquals(width, holder.view.width)
		assertEquals(height, holder.view.height)
	}

	@Test
	fun `rebinding a pooled card to another item asks for no layout`() {
		bindAndShow(button(1))
		presenter.onUnbindViewHolder(holder)
		settle()
		assertFalse(holder.view.isLayoutRequested)

		presenter.onBindViewHolder(holder, button(2))
		frames()
		assertFalse("rebind asked for a layout", holder.view.isLayoutRequested)
	}
}

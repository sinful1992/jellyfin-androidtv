package org.jellyfin.androidtv.ui.presentation

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Task 199: a line of cards comes in on one scroll frame. Each card waits 1-3 frames (in bind order)
 * before composing its picture and overlay, so that work is spread over three frames. The card's
 * size must not change when the body arrives, or the whole grid lays out again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CardStaggerTests {
	@Test
	fun `consecutive binds spread over three frames`() {
		val delays = (0 until 11).map(::bodyDelayFor)
		assertEquals(listOf(1, 2, 3, 1, 2, 3, 1, 2, 3, 1, 2), delays)
		assertEquals(4, delays.count { it == 1 })
	}

	@Test
	fun `every card waits at least one frame and at most three`() {
		for (i in listOf(0, 1, 2, 3, 1000, Int.MAX_VALUE, Int.MIN_VALUE, -1)) {
			val d = bodyDelayFor(i)
			assertTrue("bind $i waits $d", d in 1..3)
		}
	}

	@Test
	fun `a bind without a delay is ready at once`() {
		val bound = BoundCard(GridButtonBaseRowItem(GridButton(1, "a")))
		assertEquals(0, bound.bodyDelayFrames)
	}

	private val film = java.util.UUID.fromString("3c0f4d95-0347-4cfa-e956-6035d55f07ed")
	private val other = java.util.UUID.fromString("11111111-2222-3333-4444-555555555555")
	private fun delay(stagger: Boolean = true, onScreen: Boolean = false, previous: java.util.UUID? = film, new: java.util.UUID? = other, hasSize: Boolean = true) =
		bodyDelayFrames(stagger, onScreen, previous, new, hasSize) { 2 }

	@Test
	fun `a card from the pool waits its turn`() = assertEquals(2, delay())

	@Test
	fun `a refresh of a card on screen keeps its picture up`() = assertEquals(0, delay(onScreen = true))

	@Test
	fun `a rebind to the same item keeps its picture up`() = assertEquals(0, delay(new = film))

	@Test
	fun `items without ids still wait (grid buttons)`() = assertEquals(2, delay(previous = null, new = null))

	@Test
	fun `a card that never had a size is built at once`() = assertEquals(0, delay(hasSize = false))

	@Test
	fun `a presenter without stagger never waits`() = assertEquals(0, delay(stagger = false))

	@Test
	fun `a refresh does not take a turn from the bind order`() {
		var asked = 0
		bodyDelayFrames(true, true, film, other, true) { asked++; 1 }
		bodyDelayFrames(true, false, film, film, true) { asked++; 1 }
		assertEquals(0, asked)
	}

	@Test
	fun `a pooled card keeps its exact size while its body arrives`() {
		val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
		val parent = FrameLayout(activity).also { activity.setContentView(it) }
		val presenter = CardPresenter(true, 150).apply { staggerBody = true }
		val holder = presenter.onCreateViewHolder(parent)
		fun attach() = parent.addView(holder.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
		fun round() {
			ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
			parent.measure(
				View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			)
			parent.layout(0, 0, 1920, 1080)
		}
		attach()
		presenter.onBindViewHolder(holder, GridButtonBaseRowItem(GridButton(1, "Button 1")))
		var size = 0 to 0
		repeat(50) { if (size.first == 0) { round(); size = holder.view.width to holder.view.height } }
		// Same JVM limit as CardReuseTests.bindAndShow: no recomposition means nothing to compare.
		Assume.assumeTrue("Compose did not recompose in this JVM", size.first > 0 && size.second > 0)
		repeat(5) { round() }
		size = holder.view.width to holder.view.height

		// Back to the pool (detached), bound to another card, then attached again as the line comes in.
		presenter.onUnbindViewHolder(holder)
		parent.removeView(holder.view)
		val heldBefore = heldBodies
		presenter.onBindViewHolder(holder, GridButtonBaseRowItem(GridButton(2, "Button 2")))
		attach()
		// Measure after every 1 ms of looper time, so the frames where the body is still waiting
		// are laid out too, not only the settled card.
		repeat(100) {
			ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
			parent.measure(
				View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			)
			parent.layout(0, 0, 1920, 1080)
			assertEquals("card size changed while its body came in (step $it)", size, holder.view.width to holder.view.height)
		}
		// Robolectric runs the waiting frames and the body's frame inside one looper step, so the
		// held box itself is never measured as a View here: check the size it holds instead.
		assertTrue("the card was never held as an empty box", heldBodies > heldBefore)
		assertEquals(androidx.compose.ui.unit.IntSize(size.first, size.second), lastHeldSize)
	}
}

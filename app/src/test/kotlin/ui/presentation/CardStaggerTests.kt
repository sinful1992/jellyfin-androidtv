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

	@Test
	fun `the card keeps its size while its body arrives`() {
		val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
		val parent = FrameLayout(activity).also { activity.setContentView(it) }
		val presenter = CardPresenter(true, 150)
		val holder = presenter.onCreateViewHolder(parent).also {
			parent.addView(it.view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
		}
		fun round() {
			ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
			parent.measure(
				View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
				View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			)
			parent.layout(0, 0, 1920, 1080)
		}

		presenter.onBindViewHolder(holder, GridButtonBaseRowItem(GridButton(1, "Button 1")))
		// The first size the card gets, then the sizes over the next frames (the body's turn).
		var first: Pair<Int, Int>? = null
		val later = mutableListOf<Pair<Int, Int>>()
		repeat(60) {
			round()
			val size = holder.view.width to holder.view.height
			if (size.first > 0 && size.second > 0) {
				if (first == null) first = size else later += size
			}
		}
		// Same JVM limit as CardReuseTests.bindAndShow: no recomposition means nothing to compare.
		Assume.assumeTrue("Compose did not recompose in this JVM", first != null && later.size >= 5)
		later.forEach { assertEquals("card size changed when its body came in", first, it) }
	}
}

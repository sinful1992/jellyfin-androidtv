package org.jellyfin.androidtv.ui

import android.app.Application
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.widget.TextView
import org.robolectric.RuntimeEnvironment
import org.jellyfin.androidtv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The details page's pill buttons: the focused pill is white, so its label must turn dark with it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DetailsPillTests {
	private val context = ContextThemeWrapper(
		RuntimeEnvironment.getApplication(),
		R.style.Theme_Jellyfin,
	)

	private fun pill() = TextUnderButton.create(context, R.drawable.ic_play, label = "Play") {}.apply { setPill() }
	private fun label(button: TextUnderButton) = button.findViewById<TextView>(R.id.label)

	@Test
	fun `the label follows the button's focus state`() {
		assertTrue(label(pill()).isDuplicateParentStateEnabled)
	}

	@Test
	fun `label is dark when focused and white otherwise`() {
		val colors = label(pill()).textColors
		assertEquals(Color.parseColor("#111111"), colors.getColorForState(intArrayOf(android.R.attr.state_focused), 0))
		assertEquals(Color.WHITE, colors.getColorForState(intArrayOf(), 0))
	}

	@Test
	fun `focusing the pill turns the shown label dark`() {
		val button = pill()
		val parent = android.widget.FrameLayout(context).apply { addView(button) }
		button.isFocusableInTouchMode = true
		parent.measure(0, 0)
		button.requestFocus()
		assertTrue(button.isFocused)
		assertEquals(Color.parseColor("#111111"), label(button).currentTextColor)
	}

	@Test
	fun `label sits on one line beside the icon`() {
		val label = label(pill())
		assertEquals(1, label.maxLines)
		assertEquals(1f, label.alpha)
	}
}

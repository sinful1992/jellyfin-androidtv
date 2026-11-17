package org.jellyfin.androidtv.ui

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import android.view.View.MeasureSpec
import android.widget.LinearLayout
import org.jellyfin.androidtv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The details row's height (task 176). Leanback measures a row with an unspecified height, so the
 * row is as tall as its tallest child. The picture behind the page was a fixed 470dp, which made
 * every details row with art at least 470dp tall: the cast row sat that far down whatever the
 * text, with a gap under the buttons. The picture now ends with the button row instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
class DetailRowViewHeightTests {
	private val context = ContextThemeWrapper(
		RuntimeEnvironment.getApplication(),
		androidx.leanback.R.style.Theme_Leanback,
	)
	private val density = context.resources.displayMetrics.density
	private val backdropHeight = context.resources.getDimensionPixelSize(R.dimen.details_backdrop_height)

	private fun row(withArt: Boolean, summary: String, buttons: Int = 3, buttonDp: Int = 60): DetailRowView {
		val view = DetailRowView(context)
		val b = view.binding
		b.fdBackdrop.visibility = if (withArt) View.VISIBLE else View.GONE
		b.fdTitle.text = "A film"
		b.fdGenreRow.text = "Drama"
		b.fdSummaryText.text = summary
		repeat(buttons) {
			b.fdButtonRow.addView(View(context), LinearLayout.LayoutParams((60 * density).toInt(), (buttonDp * density).toInt()))
		}
		val width = (960 * density).toInt()
		view.measure(
			MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
			MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
		)
		view.layout(0, 0, width, view.measuredHeight)
		return view
	}

	@Test
	fun `a short page with art ends at its buttons, not at the picture's old 470dp`() {
		val view = row(withArt = true, summary = "Short.")
		val buttonsBottom = view.binding.fdButtonRow.bottom
		assertTrue("row ${view.measuredHeight} px should be under $backdropHeight px", view.measuredHeight < backdropHeight)
		assertEquals(buttonsBottom, view.measuredHeight)
	}

	@Test
	fun `the picture reaches exactly the bottom of the button row`() {
		val view = row(withArt = true, summary = "Short.")
		assertEquals(0, view.binding.fdBackdrop.top)
		assertEquals(view.binding.fdButtonRow.bottom, view.binding.fdBackdrop.bottom)
	}

	@Test
	fun `with art and without art the row is the same height`() {
		val withArt = row(withArt = true, summary = "Short.")
		val noArt = row(withArt = false, summary = "Short.")
		assertEquals(noArt.measuredHeight, withArt.measuredHeight)
	}

	@Test
	fun `taller content still grows the row and the picture with it`() {
		val short = row(withArt = true, summary = "Short.")
		val tall = row(withArt = true, summary = "Short.", buttonDp = 200)
		assertTrue(tall.measuredHeight > short.measuredHeight)
		assertEquals(tall.binding.fdButtonRow.bottom, tall.binding.fdBackdrop.bottom)
		assertEquals(tall.binding.fdButtonRow.bottom, tall.measuredHeight)
	}

	@Test
	fun `a page with no buttons yet still has a non-negative picture`() {
		val view = row(withArt = true, summary = "Short.", buttons = 0)
		assertTrue(view.binding.fdBackdrop.height >= 0)
		assertTrue(view.measuredHeight < backdropHeight)
	}
}

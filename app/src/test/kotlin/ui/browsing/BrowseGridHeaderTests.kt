package org.jellyfin.androidtv.ui.browsing

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.jellyfin.androidtv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 198: the library grid's header changes with every focus step. A header view sized by its own
 * text asks for a new layout on each change, and on the SK4 Pro that layout reached every card in
 * the grid: one missed frame per row move. Exact widths make a text change a redraw only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowseGridHeaderTests {
	private val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_Jellyfin)

	/** layout_width of each view in the grid screen's layout, by id, read from the compiled XML. */
	private fun layoutWidths(): Map<Int, Int> {
		val widths = mutableMapOf<Int, Int>()
		val parser = context.resources.getLayout(R.layout.horizontal_grid_browse)
		val android = "http://schemas.android.com/apk/res/android"
		while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
			if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
			val id = parser.getAttributeResourceValue(android, "id", 0)
			if (id == 0) continue
			val raw = parser.getAttributeValue(android, "layout_width") ?: continue
			widths[id] = when (raw) {
				"-2" -> ViewGroup.LayoutParams.WRAP_CONTENT
				"-1" -> ViewGroup.LayoutParams.MATCH_PARENT
				else -> 1
			}
		}
		return widths
	}

	@Test
	fun `title, info row and counter have exact widths`() {
		val widths = layoutWidths()
		for ((name, id) in listOf("title" to R.id.title, "infoRow" to R.id.infoRow, "counter" to R.id.counter)) {
			assertTrue("$name not in the layout", id in widths)
			assertNotEquals("$name is wrap_content", ViewGroup.LayoutParams.WRAP_CONTENT, widths[id])
		}
	}

	/** A one-line text view in a laid-out parent, with [width] as its layout width. */
	private fun laidOutText(width: Int): Pair<android.widget.FrameLayout, android.widget.TextView> {
		val parent = android.widget.FrameLayout(context)
		val text = android.widget.TextView(context).apply { maxLines = 1; this.text = "Ratatouille" }
		parent.addView(text, android.widget.FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
		parent.measure(
			View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
		)
		parent.layout(0, 0, 1920, 1080)
		return parent to text
	}

	@Test
	fun `a new text in an exact-width one-line view only redraws`() {
		val (parent, text) = laidOutText(ViewGroup.LayoutParams.MATCH_PARENT)
		assertFalse(parent.isLayoutRequested)
		text.text = "Rango"
		assertFalse("exact width asked for a layout", parent.isLayoutRequested)
	}

	@Test
	fun `a new text in a wrap_content view asks for a layout (the old header)`() {
		val (parent, text) = laidOutText(ViewGroup.LayoutParams.WRAP_CONTENT)
		text.text = "Rango"
		assertTrue(parent.isLayoutRequested)
	}

	@Test
	fun `hiding a view with INVISIBLE does not ask for a layout, GONE does`() {
		val (parent, text) = laidOutText(ViewGroup.LayoutParams.MATCH_PARENT)
		text.visibility = View.INVISIBLE
		assertFalse(parent.isLayoutRequested)
		text.visibility = View.GONE
		assertTrue(parent.isLayoutRequested)
	}

	@Test
	fun `the header waits while the grid slides and not at rest`() {
		assertTrue(headerWaitsForRest(RecyclerView.SCROLL_STATE_SETTLING))
		assertTrue(headerWaitsForRest(RecyclerView.SCROLL_STATE_DRAGGING))
		assertFalse(headerWaitsForRest(RecyclerView.SCROLL_STATE_IDLE))
		assertEquals(false, headerWaitsForRest(0))
	}
}

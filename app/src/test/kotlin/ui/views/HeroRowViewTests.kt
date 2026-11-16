package org.jellyfin.androidtv.ui.home

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.FrameLayout
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hero row's view inside leanback's row machinery. hero.62 crashed on start because the row
 * view was a plain View: RowPresenter casts it to ViewGroup to add its header.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HeroRowViewTests {
	private val context = ContextThemeWrapper(
		RuntimeEnvironment.getApplication(),
		androidx.leanback.R.style.Theme_Leanback,
	)

	@Test
	fun `leanback can create the hero row with its header`() {
		val presenter = HomeHeroRowPresenter { _, _, _ -> }
		val holder = presenter.onCreateViewHolder(FrameLayout(context))
		assertTrue(holder.view is ViewGroup)
	}

	@Test
	fun `the hero row view takes the focus and the keys`() {
		val presenter = HomeHeroRowPresenter { _, _, _ -> }
		val holder = presenter.onCreateViewHolder(FrameLayout(context))
		val rowView = presenter.getRowViewHolder(holder).view
		assertTrue(rowView is ViewGroup)
		assertTrue(rowView.isFocusable)
	}
}

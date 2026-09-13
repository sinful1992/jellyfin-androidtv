package org.jellyfin.androidtv.ui.presentation

import androidx.recyclerview.widget.RecyclerView

/**
 * Keep [maxPerType] card views of each kind, instead of RecyclerView's default five.
 *
 * Leanback tunes this for rows and leaves grids alone. `ListRowPresenter` hands every row a shared
 * pool sized `DEFAULT_RECYCLED_POOL_SIZE = 24` per presenter, while `VerticalGridPresenter` and
 * `HorizontalGridPresenter` expose no pool API at all and inherit RecyclerView's default of five.
 *
 * A library grid is eleven cards to a line at the default poster size. Eleven views fall out of the
 * far side on every scroll, five of them are kept, and the other six are built again from nothing —
 * a [androidx.compose.ui.platform.ComposeView] and a first composition each, on the UI thread,
 * inside the scroll's own animation callback. Measured on a Chromecast before this class existed:
 * the home rows, which get leanback's 24, spike to 67 ms the first time down and 10 ms on every
 * pass after; the Movies grid, which gets five, spikes to 131 ms the first time and then sits at
 * ~50 ms for ever. This is the grid asking for what the rows already have.
 *
 * The size is applied when a view type is first seen rather than up front, because leanback numbers
 * its view types by the order presenters turn up in the adapter's `PresenterSelector` — which
 * integer means "card" is not known until one arrives.
 */
class CardRecycledViewPool(private val maxPerType: Int) : RecyclerView.RecycledViewPool() {
	private val sizedTypes = mutableSetOf<Int>()

	override fun putRecycledView(scrap: RecyclerView.ViewHolder) {
		if (sizedTypes.add(scrap.itemViewType)) setMaxRecycledViews(scrap.itemViewType, maxPerType)

		super.putRecycledView(scrap)
	}
}

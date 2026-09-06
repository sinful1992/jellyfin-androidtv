package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.leanback.widget.Row
import androidx.leanback.widget.RowPresenter
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.CardPresenter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber

/**
 * How many of the newest unwatched titles the hero offers.
 *
 * The viewer steps through these one press at a time and the strip draws a mark for each, so this
 * is a count of dots as much as it is a depth of shortlist: enough that there is a choice, few
 * enough that the far end is a handful of presses away and everything in it still counts as news.
 */
private const val HERO_CANDIDATES = 10

/** The title the hero is showing, where it sits in the shortlist, and which way it was reached. */
@Immutable
data class HomeHeroSelection(
	val item: BaseItemDto,
	val index: Int,
	val count: Int,
	/**
	 * Which way the press that landed here was going, so the change can be drawn moving that way.
	 *
	 * Carried rather than worked out from the two indexes, because the strip wraps: a right press
	 * off the last dot lands on the first, and any sum of the indexes reads that as a move
	 * backwards and would draw it sliding the wrong way.
	 */
	val forward: Boolean = true,
)

/**
 * The row the hero is drawn in, holding the whole shortlist and which of it is on show.
 *
 * The title changes inside the row rather than by putting a different row at the top of the screen:
 * replacing the row rebinds the view holder, and a view holder rebuilt underneath the focus is how
 * the focus gets lost.
 *
 * Deliberately not a [androidx.leanback.widget.ListRow]: a list row puts its contents inside a
 * horizontal grid, and that grid fixes its height from the first item it measures and then measures
 * everything else to match. Right for a row of cards, wrong for one block the width of the screen,
 * which comes out clipped to whatever the grid settled on no matter what the view or the content
 * asks for. A plain row goes straight into the vertical list and keeps its own size.
 */
class HomeHeroRow(items: List<BaseItemDto>) : Row() {
	init {
		require(items.isNotEmpty()) { "A hero row needs at least one title" }
	}

	private var items = items

	private val _selection = MutableStateFlow(HomeHeroSelection(items.first(), 0, items.size))

	/** The title currently on show. */
	val selection: StateFlow<HomeHeroSelection> = _selection.asStateFlow()

	/**
	 * Move [delta] places along the shortlist, wrapping round at either end.
	 *
	 * Wrapping because the strip is short enough to be walked from one end to the other, and a key
	 * that stops answering at the edge of a handful of dots reads as the hero having broken rather
	 * than as having run out.
	 *
	 * Returns whether there was anywhere to go, so a shortlist of one leaves the key for leanback
	 * instead of swallowing it.
	 */
	fun step(delta: Int): Boolean {
		if (items.size < 2) return false

		val index = (_selection.value.index + delta).mod(items.size)
		_selection.value = HomeHeroSelection(items[index], index, items.size, forward = delta > 0)
		return true
	}

	/**
	 * Take a freshly drawn shortlist, leaving the viewer where they were in it.
	 *
	 * The title on the screen was chosen — the strip is a place walked to, one press at a time —
	 * and a list redrawn underneath it is no reason to take it off whoever chose it. It only ever
	 * moves when what they were on has genuinely gone: played, or removed from the library.
	 *
	 * The order is held for the same reason. The shortlist is dealt shuffled, so the hero does not
	 * open on the same title every evening, but that deal happens once, when the row is built.
	 * Dealing again on every refresh would move every mark on the strip under someone who is
	 * reading them as positions. Titles that have arrived since keep the order they were dealt in
	 * and go on the end, where the dot for them is new rather than in place of another.
	 */
	fun replace(items: List<BaseItemDto>) {
		val order = this.items.map { it.id }
		// Stable, so everything the list has not seen before — all of it equally last — stays in
		// the order it arrived in rather than being shuffled again among itself.
		this.items = items.sortedBy { item ->
			order.indexOf(item.id).takeIf { it >= 0 } ?: order.size
		}

		val shownId = _selection.value.item.id
		val index = this.items.indexOfFirst { it.id == shownId }.takeIf { it >= 0 } ?: 0

		_selection.value = HomeHeroSelection(this.items[index], index, this.items.size)
	}
}

/**
 * Put a handful of titles at the top of the home screen, the way a channel leads with what it wants
 * watched, and let the viewer move between them.
 *
 * Recently added and not yet played, which is the one thing the rows underneath cannot say:
 * Continue watching and Next up are both about what has already been started.
 */
class HomeFragmentHeroRow(
	private val lifecycleScope: LifecycleCoroutineScope,
	private val api: ApiClient,
	private val navigationRepository: NavigationRepository,
	private val playbackHelper: PlaybackHelper,
) : HomeFragmentRow {
	var row: HomeHeroRow? = null
		private set

	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		refresh(rowsAdapter)
	}

	/**
	 * Draw a new shortlist.
	 *
	 * Called again whenever the home screen is come back to, so an evening spent going in and out
	 * of titles is not one list held all night. What the viewer is on carries across it — see
	 * [HomeHeroRow.replace] — so this is a list being brought up to date rather than a new one
	 * being handed over.
	 */
	fun refresh(
		rowsAdapter: MutableObjectAdapter<Row>,
		onAdded: (HomeHeroRow) -> Unit = {},
	) {
		lifecycleScope.launch {
			val items = loadItems()
			if (items.isEmpty()) return@launch

			val existing = row
			if (existing == null) {
				// Added only once it has something to say, so a library with nothing new in it
				// gets the rows on their own rather than a gap where the hero would be.
				val added = HomeHeroRow(items)
				row = added
				rowsAdapter.add(0, added)
				// Told after the insert, and only on the insert: whoever is watching the rows has
				// to move the selection onto a row that did not exist a moment ago, and a later
				// refresh replacing the shortlist is not a reason to do that again.
				onAdded(added)
			} else {
				existing.replace(items)
			}
		}
	}

	private suspend fun loadItems(): List<BaseItemDto> = withContext(Dispatchers.IO) {
		try {
			val response by api.itemsApi.getItems(
				includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
				recursive = true,
				filters = listOf(ItemFilter.IS_UNPLAYED),
				sortBy = listOf(ItemSortBy.DATE_CREATED),
				sortOrder = listOf(SortOrder.DESCENDING),
				// Filters the results down to titles that have one, rather than asking for the tag.
				// A hero with nothing behind it is a caption on an empty screen.
				imageTypes = listOf(ImageType.BACKDROP),
				fields = ItemRepository.browseFields,
				limit = HERO_CANDIDATES,
			)

			// Shuffled so the title the hero opens on is not the same one every time. Which title
			// is shown after that is the viewer's to decide, but which one they are handed first
			// is not, and handing over the same one every evening is the thing that made the hero
			// look like a fixture rather than a shortlist.
			//
			// This is the deal for a row being built. A row that already exists keeps the order it
			// was dealt and takes only what is new out of this — see [HomeHeroRow.replace].
			response.items.shuffled()
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to load items for the home hero")
			emptyList()
		}
	}

	fun onAction(context: Context, item: BaseItemDto, action: HomeHeroAction) = when (action) {
		HomeHeroAction.Play -> playbackHelper.retrieveAndPlay(item.id, false, context)
		HomeHeroAction.Details -> navigationRepository.navigate(Destinations.itemDetails(item.id))
	}
}

/**
 * Draw the hero, and move between its own controls itself.
 *
 * The view takes the focus as a whole rather than letting Compose place it on one of the buttons:
 * leanback drives focus through the rows with its own code, and the two do not agree about who owns
 * it once Compose has focusable content inside a row. So the arrow keys pick the control here and
 * the composable is told which one is picked, which costs a few lines and behaves the same every
 * time.
 *
 * Two stops, stacked: the strip that picks the title, and the buttons that act on it. Up and down
 * move between them, and each is left with a whole axis of its own so neither has to share left and
 * right with the other.
 */
class HomeHeroRowPresenter(
	private val onAction: (context: Context, item: BaseItemDto, action: HomeHeroAction) -> Unit,
) : RowPresenter() {
	init {
		syncActivatePolicy = SYNC_ACTIVATED_CUSTOM
	}

	override fun onSelectLevelChanged(holder: ViewHolder) = Unit

	override fun createRowViewHolder(parent: ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			isFocusable = true
			isFocusableInTouchMode = true

			// The row goes into a vertical list, so it takes the width it is handed and asks for
			// the height its content needs.
			layoutParams = ViewGroup.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT,
			)
		}

		return HeroViewHolder(view)
	}

	override fun onBindRowViewHolder(viewHolder: ViewHolder, item: Any) {
		super.onBindRowViewHolder(viewHolder, item)
		if (viewHolder !is HeroViewHolder) return
		if (item !is HomeHeroRow) return

		viewHolder.bind(item)
	}

	override fun onUnbindRowViewHolder(viewHolder: ViewHolder) {
		super.onUnbindRowViewHolder(viewHolder)
		if (viewHolder !is HeroViewHolder) return

		viewHolder.bind(null)
	}

	private inner class HeroViewHolder(composeView: ComposeView) : ViewHolder(composeView) {
		private val _row = MutableStateFlow<HomeHeroRow?>(null)
		private val _focus = MutableStateFlow(HomeHeroFocus.Play)
		private val _focused = MutableStateFlow(false)

		init {
			composeView.setContent {
				val row by _row.collectAsState()
				val focus by _focus.collectAsState()
				val focused by _focused.collectAsState()

				val currentRow = row
				if (currentRow != null) {
					val selection by currentRow.selection.collectAsState()

					HomeHero(
						selection = selection,
						focus = focus,
						focused = focused,
					)
				}
			}

			_focused.value = view.isFocused
			composeView.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
				_focused.value = focused
				// Coming back to the hero from a row below should offer the thing it is there to
				// offer, not whatever was left highlighted last time. Which title is on show is
				// left exactly as it was: it was chosen, and moving away is not a change of mind.
				if (!focused) _focus.value = HomeHeroFocus.Play
			}

			composeView.setOnKeyListener { _, keyCode, event -> onKey(keyCode, event) }
		}

		private fun onKey(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
			KeyEvent.KEYCODE_DPAD_LEFT -> onSideways(-1, to = HomeHeroFocus.Play, from = HomeHeroFocus.Details, event)
			KeyEvent.KEYCODE_DPAD_RIGHT -> onSideways(1, to = HomeHeroFocus.Details, from = HomeHeroFocus.Play, event)

			// The strip is a stop above the buttons and the toolbar is above that, which leanback
			// reaches on its own the moment this stops claiming the key. With one title there is
			// no strip drawn, so there is nothing to stop at and the toolbar stays one press away.
			KeyEvent.KEYCODE_DPAD_UP -> when {
				_focus.value == HomeHeroFocus.Dots -> false
				!hasStrip() -> false
				else -> {
					if (event.action == KeyEvent.ACTION_DOWN) _focus.value = HomeHeroFocus.Dots
					true
				}
			}

			KeyEvent.KEYCODE_DPAD_DOWN -> when (_focus.value) {
				HomeHeroFocus.Dots -> {
					if (event.action == KeyEvent.ACTION_DOWN) _focus.value = HomeHeroFocus.Play
					true
				}
				// Off the bottom of the hero is the first row of cards, which is leanback's.
				else -> false
			}

			KeyEvent.KEYCODE_DPAD_CENTER,
			KeyEvent.KEYCODE_ENTER,
			KeyEvent.KEYCODE_NUMPAD_ENTER -> {
				val item = _row.value?.selection?.value?.item
				if (item != null && event.action == KeyEvent.ACTION_UP) onSelect(item)
				item != null
			}

			else -> false
		}

		/** Whether there is a strip drawn to stop at, which there is not for a shortlist of one. */
		private fun hasStrip(): Boolean = (_row.value?.selection?.value?.count ?: 0) > 1

		private fun onSelect(item: BaseItemDto) = when (_focus.value) {
			// The strip picks a title rather than doing anything with it, so taking the pick puts
			// the focus on what there is to do with it. A press that lands nowhere would be the
			// only dead key on the screen.
			HomeHeroFocus.Dots -> _focus.value = HomeHeroFocus.Play
			HomeHeroFocus.Play -> onAction(view.context, item, HomeHeroAction.Play)
			HomeHeroFocus.Details -> onAction(view.context, item, HomeHeroAction.Details)
		}

		/**
		 * Left and right, which mean whichever stop is being used: a step along the shortlist on
		 * the strip, and a move between the buttons below it.
		 *
		 * On the buttons, only a press with another button that way is claimed. Letting the rest
		 * through leaves leanback to do whatever it does at the edge of a row, rather than
		 * swallowing the press and looking broken.
		 */
		private fun onSideways(delta: Int, to: HomeHeroFocus, from: HomeHeroFocus, event: KeyEvent): Boolean {
			if (_focus.value == HomeHeroFocus.Dots) {
				val row = _row.value ?: return false
				// Held down, this repeats, which is how the far end of the strip is reached
				// without ten separate presses.
				if (event.action != KeyEvent.ACTION_DOWN) return hasStrip()
				return row.step(delta)
			}

			if (_focus.value != from) return false
			if (event.action == KeyEvent.ACTION_DOWN) _focus.value = to
			return true
		}

		fun bind(row: HomeHeroRow?) {
			_row.value = row
			_focus.value = HomeHeroFocus.Play
			_focused.value = view.isFocused
		}
	}
}

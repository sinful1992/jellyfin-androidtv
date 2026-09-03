package org.jellyfin.androidtv.ui.home

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
 * How many of the newest unwatched titles the hero picks from.
 *
 * Always showing the single newest would make the home screen the same picture every evening until
 * something is added. Picking out of a handful of them keeps it alive without reaching so far back
 * that it stops being news.
 */
private const val HERO_CANDIDATES = 20

/**
 * The row the hero is drawn in.
 *
 * Deliberately not a [androidx.leanback.widget.ListRow]: a list row puts its contents inside a
 * horizontal grid, and that grid fixes its height from the first item it measures and then measures
 * everything else to match. Right for a row of cards, wrong for one block the width of the screen,
 * which comes out clipped to whatever the grid settled on no matter what the view or the content
 * asks for. A plain row goes straight into the vertical list and keeps its own size.
 */
class HomeHeroRow(val item: BaseItemDto) : Row()

/**
 * Put one title at the top of the home screen, the way a channel leads with what it wants watched.
 *
 * Something recently added and not yet played, which is the one thing the rows underneath cannot
 * say: Continue watching and Next up are both about what has already been started.
 */
class HomeFragmentHeroRow(
	private val lifecycleScope: LifecycleCoroutineScope,
	private val api: ApiClient,
	private val navigationRepository: NavigationRepository,
	private val playbackHelper: PlaybackHelper,
) : HomeFragmentRow {
	override fun addToRowsAdapter(context: Context, cardPresenter: CardPresenter, rowsAdapter: MutableObjectAdapter<Row>) {
		lifecycleScope.launch {
			val item = loadItem() ?: return@launch

			rowsAdapter.add(0, HomeHeroRow(item))
		}
	}

	private suspend fun loadItem(): BaseItemDto? = withContext(Dispatchers.IO) {
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

			response.items.randomOrNull()
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to load an item for the home hero")
			null
		}
	}

	fun onAction(context: Context, item: BaseItemDto, action: HomeHeroAction) = when (action) {
		HomeHeroAction.Play -> playbackHelper.retrieveAndPlay(item.id, false, context)
		HomeHeroAction.Details -> navigationRepository.navigate(Destinations.itemDetails(item.id))
	}
}

/**
 * Draw the hero, and move between its two buttons itself.
 *
 * The view takes the focus as a whole rather than letting Compose place it on one of the buttons:
 * leanback drives focus through the rows with its own code, and the two do not agree about who owns
 * it once Compose has focusable content inside a row. So left and right pick the button here and
 * the composable is told which one is picked, which costs a few lines and behaves the same every
 * time.
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

		viewHolder.bind(item.item)
	}

	override fun onUnbindRowViewHolder(viewHolder: ViewHolder) {
		super.onUnbindRowViewHolder(viewHolder)
		if (viewHolder !is HeroViewHolder) return

		viewHolder.bind(null)
	}

	private inner class HeroViewHolder(composeView: ComposeView) : ViewHolder(composeView) {
		private val _item = MutableStateFlow<BaseItemDto?>(null)
		private val _action = MutableStateFlow(HomeHeroAction.Play)
		private val _focused = MutableStateFlow(false)

		init {
			composeView.setContent {
				val item by _item.collectAsState()
				val action by _action.collectAsState()
				val focused by _focused.collectAsState()

				item?.let {
					HomeHero(
						item = it,
						action = action,
						focused = focused,
					)
				}
			}

			_focused.value = view.isFocused
			composeView.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
				_focused.value = focused
				// Coming back to the hero from a row below should offer the thing it is there to
				// offer, not whatever was left highlighted last time.
				if (!focused) _action.value = HomeHeroAction.Play
			}

			composeView.setOnKeyListener { _, keyCode, event -> onKey(keyCode, event) }
		}

		private fun onKey(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
			// Only claim a sideways press when there is another button that way. Letting the rest
			// through leaves leanback to do whatever it does at the edge of a row, rather than
			// swallowing the press and looking broken.
			KeyEvent.KEYCODE_DPAD_LEFT -> moveTo(HomeHeroAction.Play, from = HomeHeroAction.Details, event)
			KeyEvent.KEYCODE_DPAD_RIGHT -> moveTo(HomeHeroAction.Details, from = HomeHeroAction.Play, event)

			KeyEvent.KEYCODE_DPAD_CENTER,
			KeyEvent.KEYCODE_ENTER,
			KeyEvent.KEYCODE_NUMPAD_ENTER -> {
				val item = _item.value
				if (item != null && event.action == KeyEvent.ACTION_UP) onAction(view.context, item, _action.value)
				item != null
			}

			else -> false
		}

		private fun moveTo(to: HomeHeroAction, from: HomeHeroAction, event: KeyEvent): Boolean {
			if (_action.value != from) return false
			if (event.action == KeyEvent.ACTION_DOWN) _action.value = to
			return true
		}

		fun bind(item: BaseItemDto?) {
			_item.value = item
			_action.value = HomeHeroAction.Play
			_focused.value = view.isFocused
		}
	}
}

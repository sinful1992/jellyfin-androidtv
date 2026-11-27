package org.jellyfin.androidtv.ui.browsing

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.model.FilterOptions
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.browsing.grid.LibraryGrid
import org.jellyfin.androidtv.ui.browsing.grid.LibraryGridMemory
import org.jellyfin.androidtv.ui.browsing.grid.LibraryGridSource
import org.jellyfin.androidtv.ui.browsing.grid.LibraryGridState
import org.jellyfin.androidtv.ui.browsing.grid.apiGridFetcher
import org.jellyfin.androidtv.ui.itemhandling.AudioQueueBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.presentation.MutableObjectAdapter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import java.util.UUID

fun BrowseGridFragment.createSettingsVisibility() = MutableStateFlow(false)

fun BrowseGridFragment.addSettings(
	view: ComposeView,
	itemId: UUID,
	displayPreferencesId: String,
	visible: MutableStateFlow<Boolean>,
) {
	view.setContent {
		val isVisible by visible.collectAsState(false)

		ProvideRouter(
			routes,
			Routes.LIBRARIES_DISPLAY,
			mapOf("itemId" to itemId.toString(), "displayPreferencesId" to displayPreferencesId)
		) {
			SettingsDialog(
				visible = isVisible,
				onDismissRequest = {
					visible.value = false
					onResume()
				}
			) {
				SettingsRouterContent()
			}
		}
	}
}

/** Page size of the Compose grid, as the leanback grid used (buildAdapter). */
private const val ComposeGridChunkSize = 100

/** What the Compose grid tells BrowseGridFragment, in the leanback listeners' place. */
interface ComposeGridCallbacks {
	fun onItemFocused(index: Int, item: BaseRowItem)
	fun onItemClicked(item: BaseRowItem)
	fun onItemLongClicked(item: BaseRowItem)
	fun onScrollingChanged(scrolling: Boolean)

	/** A fetch finished (an answer or an error), as ItemRowAdapter's retrieve-finished listener. */
	fun onAnswer(itemsLoaded: Int, totalItems: Int)
}

/** The sizes the fragment computed for the grid (setAutoCardGridValues), in the same units it used. */
class ComposeGridLayout(
	@JvmField val columns: Int,
	@JvmField val cardHeight: Int,
	@JvmField val imageType: ImageType,
	@JvmField val spacingHorizontalPx: Int,
	@JvmField val spacingVerticalPx: Int,
	@JvmField val paddingHorizontalPx: Int,
	@JvmField val paddingVerticalPx: Int,
)

/** True when a state is the end of a fetch the fragment has not been told about yet. */
internal fun isNewAnswer(state: LibraryGridState): Boolean = !state.loading && (state.totalItems >= 0 || state.error != null)

/**
 * The Compose library grid of a BrowseGridFragment (task 200): its data ([LibraryGridSource]), what
 * it keeps while its view is gone ([LibraryGridMemory]) and the filter options the toolbar edits.
 * Lives as long as the fragment, not its view, so Back from details shows the same items at once.
 */
class ComposeGridHost(
	owner: LifecycleOwner,
	query: GetItemsRequest,
	keepSetsTogether: Boolean,
	preferParentThumb: Boolean,
	staticHeight: Boolean,
	api: ApiClient,
) {
	val memory = LibraryGridMemory()
	val source = LibraryGridSource(
		baseQuery = query,
		chunkSize = ComposeGridChunkSize,
		keepSetsTogether = keepSetsTogether,
		scope = owner.lifecycleScope,
		fetcher = apiGridFetcher(api),
		transform = { BaseItemDtoBaseRowItem(it, preferParentThumb, staticHeight) },
	)

	/** ItemLauncher wants an adapter; for a movie it reads nothing from it. */
	val launchAdapter = MutableObjectAdapter<Any>()

	var filterOptions: FilterOptions = FilterOptions()
		private set

	fun setFilterOptions(options: FilterOptions) {
		filterOptions = options
		source.setFilters(options.filters)
	}

	fun setSort(option: BrowseGridFragment.SortOption) = source.setSort(option.value, option.order)

	fun setStartLetter(letter: String?) = source.setStartLetter(letter)

	val startLetter: String? get() = source.startLetter
	val sortBy get() = source.sortBy
	val totalItems: Int get() = source.state.value.totalItems.coerceAtLeast(0)
	val itemsLoaded: Int get() = source.state.value.items.size

	fun start() = source.start()

	/** Index of this item in the grid (this instance first, else one with its id), or -1. */
	fun indexOf(item: BaseRowItem?): Int {
		if (item == null) return -1
		val items = source.state.value.items
		val same = items.indexOfFirst { it === item }
		return if (same >= 0) same else items.indexOf(item)
	}

	/**
	 * Puts the grid into [container] and keeps [callbacks] told about it until [viewOwner]'s view is
	 * destroyed.
	 */
	fun show(container: FrameLayout, viewOwner: LifecycleOwner, layout: ComposeGridLayout, callbacks: ComposeGridCallbacks): ComposeView {
		val view = ComposeView(container.context).apply {
			layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				val state by source.state.collectAsState()

				LibraryGrid(
					items = state.items,
					generation = state.generation,
					columns = layout.columns,
					cardHeight = layout.cardHeight,
					imageType = layout.imageType,
					spacingHorizontalPx = layout.spacingHorizontalPx,
					spacingVerticalPx = layout.spacingVerticalPx,
					paddingHorizontalPx = layout.paddingHorizontalPx,
					paddingVerticalPx = layout.paddingVerticalPx,
					memory = memory,
					onFocused = { index, item ->
						source.loadMore(index)
						callbacks.onItemFocused(index, item)
					},
					onClick = callbacks::onItemClicked,
					onLongClick = callbacks::onItemLongClicked,
					onScrollingChanged = callbacks::onScrollingChanged,
				)
			}
		}
		container.removeAllViews()
		container.addView(view)

		answers?.cancel()
		answers = viewOwner.lifecycleScope.launch {
			source.state
				.filter(::isNewAnswer)
				.map { AnswerKey(it.generation, it.items.size, it.totalItems, it.error) }
				.distinctUntilChanged()
				.collect { callbacks.onAnswer(it.itemsLoaded, it.totalItems) }
		}

		return view
	}

	/**
	 * Loads [item] again (after details or playback), then replaces it in the grid, or removes it when
	 * [stillShown] says the active filters no longer show it ([onRemoving] runs first, to move the
	 * focus off it). A grid that was re-queried meanwhile ignores the answer (generation).
	 */
	fun refreshItem(
		api: ApiClient,
		owner: LifecycleOwner,
		item: BaseRowItem,
		stillShown: (BaseRowItem) -> Boolean,
		onRemoving: () -> Unit,
		onRemoved: () -> Unit,
	) {
		if (item !is BaseItemDtoBaseRowItem || item is AudioQueueBaseRowItem) return
		val baseItem = item.baseItem ?: return
		val generation = source.state.value.generation

		owner.lifecycleScope.launch {
			val refreshed = runCatching {
				withContext(Dispatchers.IO) { api.userLibraryApi.getItem(itemId = baseItem.id).content }
			}.onFailure { Timber.w(it, "Refresh of %s failed", baseItem.id) }.getOrNull() ?: return@launch

			val index = indexOf(item)
			if (index == -1) return@launch

			val replacement = BaseItemDtoBaseRowItem(refreshed, item.preferParentThumb, item.staticHeight)
			if (stillShown(replacement)) {
				source.replaceItem(generation, index, replacement)
			} else {
				onRemoving()
				source.removeItem(generation, index)
				onRemoved()
			}
		}
	}

	private var answers: Job? = null

	/** Stops telling the fragment about answers: this host was replaced (new card size or settings). */
	fun detach() {
		answers?.cancel()
		answers = null
	}

	private data class AnswerKey(val generation: Int, val itemsLoaded: Int, val totalItems: Int, val error: Throwable?)
}

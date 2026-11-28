package org.jellyfin.androidtv.ui.browsing.grid

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.loadMoviesWithSets
import org.jellyfin.androidtv.ui.itemhandling.setItemsFilter
import org.jellyfin.androidtv.ui.itemhandling.setItemsSorting
import org.jellyfin.androidtv.ui.itemhandling.setItemsStartLetter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID

/** One page answer. */
data class GridPage(val items: List<BaseItemDto>, val totalRecordCount: Int)

/** How [LibraryGridSource] fetches; the app passes [apiGridFetcher], tests pass fakes. */
interface GridFetcher {
	suspend fun page(query: GetItemsRequest, startIndex: Int, limit: Int): GridPage

	/** Whole library in name order with box sets kept together ([loadMoviesWithSets]). */
	suspend fun moviesWithSets(query: GetItemsRequest, descending: Boolean): List<BaseItemDto>
}

fun apiGridFetcher(api: ApiClient): GridFetcher = object : GridFetcher {
	override suspend fun page(query: GetItemsRequest, startIndex: Int, limit: Int): GridPage {
		val result = withContext(Dispatchers.IO) {
			api.itemsApi.getItems(query.copy(startIndex = startIndex, limit = limit)).content
		}
		return GridPage(result.items, result.totalRecordCount)
	}

	override suspend fun moviesWithSets(query: GetItemsRequest, descending: Boolean) =
		loadMoviesWithSets(api, query, descending)
}

/**
 * Not a data class on purpose: MutableStateFlow drops a value that equals the current one, and a
 * data class would compare every item (BaseItemDtoBaseRowItem compares whole DTOs) on each update, and
 * drop a replaced item that equals the old one although the grid's card binds by instance.
 * Every state is its own value (identity equality).
 */
class LibraryGridState(
	/** Loaded so far; index == grid position. Never mutated: every change is a new list. */
	val items: List<BaseRowItem> = emptyList(),
	/** TotalRecordCount of the newest answer; -1 before the first answer of this generation. */
	val totalItems: Int = -1,
	/** A fetch is in flight. */
	val loading: Boolean = false,
	/** +1 on every re-query (sort, filters, letter); the grid scrolls to 0 and drops its saved focus. */
	val generation: Int = 0,
	/** Last failed fetch of this generation; null after a success. */
	val error: Throwable? = null,
) {
	val fullyLoaded: Boolean get() = totalItems >= 0 && items.size >= totalItems

	fun copy(
		items: List<BaseRowItem> = this.items,
		totalItems: Int = this.totalItems,
		loading: Boolean = this.loading,
		generation: Int = this.generation,
		error: Throwable? = this.error,
	) = LibraryGridState(items, totalItems, loading, generation, error)

	override fun toString() =
		"LibraryGridState(items=${items.size}, totalItems=$totalItems, loading=$loading, generation=$generation, error=$error)"
}

/**
 * The library grid's items as Compose-friendly state (task 200), with the paging of ItemRowAdapter:
 * pages of [chunkSize] fetched as focus nears the end, or, for a movie library in name order with
 * no start letter, the whole list at once with each box set's movies kept together.
 *
 * Every call is made on [scope]'s thread (main). Only one fetch runs at a time; a re-query cancels
 * the one in flight, and an answer for an older generation is dropped, never merged.
 */
class LibraryGridSource(
	baseQuery: GetItemsRequest,
	private val chunkSize: Int,
	private val keepSetsTogether: Boolean,
	private val scope: CoroutineScope,
	private val fetcher: GridFetcher,
	private val transform: (BaseItemDto) -> BaseRowItem,
	/** Where [setPreviewUrlBuilder]'s builder runs; tests pass an immediate one. */
	private val urlDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
	init {
		require(chunkSize > 0) { "chunkSize must be > 0, was $chunkSize" }
	}

	private val _state = MutableStateFlow(LibraryGridState())
	val state: StateFlow<LibraryGridState> = _state.asStateFlow()

	var sortBy: ItemSortBy = baseQuery.sortBy?.firstOrNull() ?: ItemSortBy.SORT_NAME
		private set
	var sortOrder: SortOrder = baseQuery.sortOrder?.firstOrNull() ?: SortOrder.ASCENDING
		private set
	var startLetter: String? = normalizeLetter(baseQuery.nameStartsWith)
		private set
	var filters: Set<ItemFilter> = baseQuery.filters?.toSet().orEmpty()
		private set

	val isKeepingSetsTogether: Boolean
		get() = keepSetsTogether && sortBy == ItemSortBy.SORT_NAME && startLetter == null

	private var query: GetItemsRequest = baseQuery
	private var started = false
	private var job: Job? = null

	/** Bumped by every fetch and every cancel; an answer whose number is not the newest is dropped. */
	private var fetchSeq = 0

	/** The fetch in flight is a next page (not a first load or a refresh). */
	private var pageInFlight = false

	/**
	 * Card picture URLs built ahead, off the main thread (task 201): building one in a card's first
	 * composition was ~12 % of a new line's cost on the SK4 Pro. Keyed by item id, so a card can never
	 * get another item's picture; read with [previewUrl], which is not Compose state, so filling it
	 * recomposes nothing. Replaced whole (never mutated), so a read on main sees one consistent map.
	 */
	@Volatile
	private var previewUrls: Map<UUID, String> = emptyMap()
	private var previewUrlBuilder: ((BaseRowItem) -> String?)? = null
	private var urlJob: Job? = null

	/** Bumped when the builder changes or the generation moves on; an older result is dropped. */
	private var urlSeq = 0

	/**
	 * Sets how a card's preview URL is built (the card side's maths: image type, card size, server).
	 * It runs on [urlDispatcher], so it must only read its argument. Null stops building and drops
	 * the URLs built so far; a new builder builds every loaded item's URL again.
	 */
	fun setPreviewUrlBuilder(builder: ((BaseRowItem) -> String?)?) {
		previewUrlBuilder = builder
		resetPreviewUrls()
		buildPreviewUrls()
	}

	/** The URL built ahead for the item with this id, or null (not built yet: build it as before). */
	fun previewUrl(id: UUID?): String? = id?.let { previewUrls[it] }

	/** The query the next fetch sends (sort, filters and letter applied). */
	val currentQuery: GetItemsRequest get() = query

	init {
		rebuildQuery()
	}

	/** First load; a second call does nothing (use [refresh]). */
	fun start() {
		if (started) return
		started = true
		fetchFirst()
	}

	/** Call with the focused index on every focus change. */
	fun loadMore(pos: Int) {
		if (!started || job?.isActive == true) return
		val current = _state.value
		// No answer yet for this generation (the first one failed): try it again.
		if (current.totalItems < 0) {
			fetchFirst()
			return
		}
		if (isKeepingSetsTogether || current.fullyLoaded) return
		if (pos < current.items.size - chunkSize / 1.7) return
		fetchPage(current.items.size)
	}

	/** A non-name sort clears the start letter. Re-queries only when something changed. */
	fun setSort(sortBy: ItemSortBy, order: SortOrder) {
		if (sortBy == this.sortBy && order == sortOrder) return
		this.sortBy = sortBy
		sortOrder = order
		if (sortBy != ItemSortBy.SORT_NAME) startLetter = null
		requery()
	}

	fun setFilters(filters: Set<ItemFilter>) {
		if (filters == this.filters) return
		this.filters = filters.toSet()
		requery()
	}

	/** "#" means no letter, as in ItemRowAdapter. */
	fun setStartLetter(letter: String?) {
		val normalized = normalizeLetter(letter)
		if (normalized == startLetter) return
		startLetter = normalized
		requery()
	}

	/**
	 * Fetches again with the same settings (ReRetrieveIfNeeded triggers). The generation stays the
	 * same and the loaded items stay on screen until the answer, so focus and scroll survive; every
	 * item loaded so far comes back in one answer (at least one chunk).
	 */
	fun refresh() {
		if (!started) {
			start()
			return
		}
		cancelFetch()
		val current = _state.value
		val generation = current.generation
		_state.value = current.copy(loading = true)
		if (isKeepingSetsTogether) {
			launchFetch(generation, fetch = { fetchSets() }, onAnswer = ::replaceAll)
		} else {
			val limit = maxOf(chunkSize, current.items.size)
			launchFetch(generation, fetch = { fetcher.page(query, 0, limit) }, onAnswer = ::replaceAll)
		}
	}

	/** refreshCurrentItem: the item changed. A stale [generation] or an index outside items does nothing. */
	fun replaceItem(generation: Int, index: Int, item: BaseRowItem) {
		val current = _state.value
		if (generation != current.generation || index !in current.items.indices) return
		// The new instance may have a new picture (image tag): drop its old URL until it is built again.
		current.items[index].baseItem?.id?.let { previewUrls = previewUrls - it }
		item.baseItem?.id?.let { previewUrls = previewUrls - it }
		_state.value = current.copy(items = current.items.toMutableList().also { it[index] = item })
		buildPreviewUrls()
	}

	/**
	 * refreshCurrentItem: the item no longer matches the filters. Later items shift down and
	 * totalItems drops by one. A page in flight was asked for at the old size and would skip an item,
	 * so it is cancelled; the next [loadMore] asks again at the new size.
	 * A stale [generation] or an index outside items does nothing.
	 */
	fun removeItem(generation: Int, index: Int) {
		val current = _state.value
		if (generation != current.generation || index !in current.items.indices) return
		val cancelledPage = pageInFlight
		if (cancelledPage) cancelFetch()
		_state.value = current.copy(
			items = current.items.toMutableList().also { it.removeAt(index) },
			totalItems = (current.totalItems - 1).coerceAtLeast(current.items.size - 1),
			loading = if (cancelledPage) false else current.loading,
		)
	}

	private fun cancelFetch() {
		job?.cancel()
		job = null
		fetchSeq++
		pageInFlight = false
	}

	private fun requery() {
		rebuildQuery()
		if (!started) return
		cancelFetch()
		_state.value = LibraryGridState(generation = _state.value.generation + 1)
		resetPreviewUrls()
		fetchFirst()
	}

	private fun rebuildQuery() {
		var q = setItemsSorting(query, sortBy, sortOrder)
		q = setItemsFilter(q, filters)
		q = setItemsStartLetter(q, startLetter)
		query = q
	}

	private fun fetchFirst() {
		val generation = _state.value.generation
		_state.value = _state.value.copy(loading = true)
		if (isKeepingSetsTogether) {
			launchFetch(generation, fetch = { fetchSets() }, onAnswer = ::replaceAll)
		} else {
			launchFetch(generation, fetch = { fetcher.page(query, 0, chunkSize) }, onAnswer = ::replaceAll)
		}
	}

	private fun fetchPage(startIndex: Int) {
		val generation = _state.value.generation
		_state.value = _state.value.copy(loading = true)
		launchFetch(generation, fetch = { fetcher.page(query, startIndex, chunkSize) }, onAnswer = { page ->
			append(startIndex, page)
		})
		// Still waiting (not answered at once by an immediate dispatcher).
		if (job != null) pageInFlight = true
	}

	private suspend fun fetchSets(): GridPage {
		val movies = fetcher.moviesWithSets(query, sortOrder == SortOrder.DESCENDING)
		return GridPage(movies, movies.size)
	}

	private fun launchFetch(
		generation: Int,
		fetch: suspend () -> GridPage,
		onAnswer: (GridPage) -> Unit,
	) {
		val seq = ++fetchSeq
		pageInFlight = false
		val launched = scope.launch {
			val result = try {
				Result.success(fetch())
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				Result.failure(error)
			}
			// An answer for an older generation, or one that was cancelled or overtaken, is dropped.
			if (seq != fetchSeq || generation != _state.value.generation) return@launch
			pageInFlight = false
			result.fold(
				onSuccess = { page ->
					try {
						onAnswer(page)
					} catch (error: CancellationException) {
						throw error
					} catch (error: Throwable) {
						_state.value = _state.value.copy(loading = false, error = error)
					}
				},
				onFailure = { error ->
					_state.value = _state.value.copy(loading = false, error = error)
				},
			)
		}
		// With an immediate dispatcher the fetch may already have answered; keep job only while live.
		job = launched.takeIf { it.isActive }
	}

	private fun replaceAll(page: GridPage) {
		val items = page.items.map(transform)
		_state.value = _state.value.copy(
			items = items,
			totalItems = settledTotal(page, items.size),
			loading = false,
			error = null,
		)
		// A refresh brings new instances (new watched state, maybe a new picture): build them all again.
		resetPreviewUrls()
		buildPreviewUrls()
	}

	private fun append(startIndex: Int, page: GridPage) {
		val current = _state.value
		// Items were removed since the page was asked for: its place is gone (removeItem also cancels it).
		if (startIndex != current.items.size) {
			_state.value = current.copy(loading = false)
			return
		}
		val items = current.items + page.items.map(transform)
		_state.value = current.copy(
			items = items,
			totalItems = settledTotal(page, items.size),
			loading = false,
			error = null,
		)
		buildPreviewUrls()
	}

	private fun resetPreviewUrls() {
		urlJob?.cancel()
		urlJob = null
		urlSeq++
		previewUrls = emptyMap()
	}

	/**
	 * Builds the URLs the loaded items don't have yet, on [urlDispatcher], and adds them in one step.
	 * The items are published first and never wait for this: a card without a URL builds it as before.
	 */
	private fun buildPreviewUrls() {
		val builder = previewUrlBuilder ?: return
		val state = _state.value
		val have = previewUrls
		val todo = state.items.filter { item -> item.baseItem?.id?.let { it !in have } == true }
		if (todo.isEmpty()) return
		urlJob?.cancel()
		val seq = ++urlSeq
		val generation = state.generation
		urlJob = scope.launch {
			val built = withContext(urlDispatcher) {
				val out = HashMap<UUID, String>(todo.size * 2)
				for (item in todo) {
					ensureActive()
					val id = item.baseItem?.id ?: continue
					// One item the builder can't handle keeps today's path; it never stops the rest.
					val url = try {
						builder(item)
					} catch (error: CancellationException) {
						throw error
					} catch (error: Throwable) {
						null
					}
					if (url != null) out[id] = url
				}
				out
			}
			if (seq != urlSeq || generation != _state.value.generation) return@launch
			previewUrls = previewUrls + built
		}
	}

	/**
	 * The total to keep: the server's newest count, but never below what is loaded, and an empty page
	 * before the end ends the list (no endless retry loop).
	 */
	private fun settledTotal(page: GridPage, loaded: Int): Int = when {
		page.items.isEmpty() -> loaded
		else -> page.totalRecordCount.coerceAtLeast(loaded)
	}

	private companion object {
		fun normalizeLetter(letter: String?): String? = letter?.takeUnless { it == "#" || it.isEmpty() }
	}
}

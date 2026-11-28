package org.jellyfin.androidtv.ui.browsing.grid

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import java.util.UUID
import kotlin.coroutines.CoroutineContext

private fun movie(n: Int) = BaseItemDto(
	id = UUID(0, n.toLong()),
	type = BaseItemKind.MOVIE,
	name = "m$n",
)

private fun movies(range: IntRange) = range.map(::movie)

private val List<BaseRowItem>.names get() = map { it.baseItem?.name }

/** One request the source made; the test answers it when it wants to. */
private class Call(
	val query: GetItemsRequest,
	val startIndex: Int?,
	val limit: Int?,
	val sets: Boolean,
	val descending: Boolean?,
) {
	val answer = CompletableDeferred<GridPage>()
	fun reply(items: List<BaseItemDto>, total: Int = items.size) = answer.complete(GridPage(items, total))
	fun fail(error: Throwable) = answer.completeExceptionally(error)
}

/** Records calls and suspends until the test answers. [ignoreCancel] models a fetch that answers anyway. */
private class FakeFetcher(val ignoreCancel: Boolean = false) : GridFetcher {
	val calls = mutableListOf<Call>()
	val last get() = calls.last()

	private suspend fun await(call: Call): GridPage {
		calls += call
		return if (ignoreCancel) withContext(NonCancellable) { call.answer.await() } else call.answer.await()
	}

	override suspend fun page(query: GetItemsRequest, startIndex: Int, limit: Int) =
		await(Call(query, startIndex, limit, sets = false, descending = null))

	override suspend fun moviesWithSets(query: GetItemsRequest, descending: Boolean) =
		await(Call(query, null, null, sets = true, descending = descending)).items
}


/** Holds the URL builds until the test runs them, so a test can act while one is "in flight". */
private class HeldDispatcher : CoroutineDispatcher() {
	private val queue = ArrayDeque<Runnable>()
	val pending get() = queue.size
	override fun dispatch(context: CoroutineContext, block: Runnable) {
		queue.addLast(block)
	}

	fun runAll() {
		while (queue.isNotEmpty()) queue.removeFirst().run()
	}
}

/** A builder that records what it was asked for: url = "u<n>" from the item's name "m<n>". */
private class Builder(val fail: Set<String> = emptySet(), val none: Set<String> = emptySet(), val tag: String = "u") {
	val asked = mutableListOf<BaseRowItem>()
	val fn: (BaseRowItem) -> String? = { item ->
		asked += item
		val name = item.baseItem?.name.orEmpty()
		when (name) {
			in fail -> error("cannot build $name")
			in none -> null
			else -> tag + name.removePrefix("m")
		}
	}
}

private fun id(n: Int) = UUID(0, n.toLong())

private class Fixture(
	chunkSize: Int = 100,
	keepSetsTogether: Boolean = false,
	baseQuery: GetItemsRequest = GetItemsRequest(parentId = UUID(1, 1)),
	ignoreCancel: Boolean = false,
	transform: (BaseItemDto) -> BaseRowItem = { BaseItemDtoBaseRowItem(it) },
	urlDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) {
	val fetcher = FakeFetcher(ignoreCancel)
	// Unconfined: launches run at once and answers resume in the test's own call, so every step is deterministic.
	val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
	val source = LibraryGridSource(baseQuery, chunkSize, keepSetsTogether, scope, fetcher, transform, urlDispatcher)
	val state get() = source.state.value
}

class LibraryGridSourceTest : FunSpec({
	test("start asks for the first chunk and shows it") {
		val f = Fixture()
		f.source.start()
		f.fetcher.calls.size shouldBe 1
		f.fetcher.last.startIndex shouldBe 0
		f.fetcher.last.limit shouldBe 100
		f.state.loading shouldBe true
		f.state.totalItems shouldBe -1

		f.fetcher.last.reply(movies(0..99), total = 250)
		f.state.items.size shouldBe 100
		f.state.items.first().baseItem?.name shouldBe "m0"
		f.state.totalItems shouldBe 250
		f.state.loading shouldBe false
		f.state.fullyLoaded shouldBe false
		f.state.generation shouldBe 0
	}

	test("a second start does nothing") {
		val f = Fixture()
		f.source.start()
		f.source.start()
		f.fetcher.calls.size shouldBe 1
	}

	test("nothing is fetched before start, and loadMore before start does nothing") {
		val f = Fixture()
		f.source.loadMore(0)
		f.source.setSort(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING)
		f.source.setFilters(setOf(ItemFilter.IS_UNPLAYED))
		f.source.setStartLetter("B")
		f.fetcher.calls.size shouldBe 0
		f.state.generation shouldBe 0
	}

	test("settings made before start go into the first query") {
		val f = Fixture()
		f.source.setFilters(setOf(ItemFilter.IS_FAVORITE))
		f.source.setSort(ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING)
		f.source.start()
		val q = f.fetcher.last.query
		q.sortBy shouldBe setOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME)
		q.sortOrder shouldBe setOf(SortOrder.DESCENDING)
		q.filters shouldBe setOf(ItemFilter.IS_FAVORITE)
		q.parentId shouldBe UUID(1, 1)
	}

	test("the base query's sort, order, letter and filters are the starting settings") {
		val f = Fixture(
			baseQuery = GetItemsRequest(
				sortBy = setOf(ItemSortBy.COMMUNITY_RATING),
				sortOrder = setOf(SortOrder.DESCENDING),
				nameStartsWith = "#",
				filters = setOf(ItemFilter.IS_UNPLAYED),
			)
		)
		f.source.sortBy shouldBe ItemSortBy.COMMUNITY_RATING
		f.source.sortOrder shouldBe SortOrder.DESCENDING
		f.source.startLetter shouldBe null
		f.source.filters shouldBe setOf(ItemFilter.IS_UNPLAYED)
		f.source.currentQuery.nameStartsWith shouldBe null
	}

	test("empty library: total 0, fully loaded, loadMore never fetches") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(emptyList(), total = 0)
		f.state.totalItems shouldBe 0
		f.state.fullyLoaded shouldBe true
		f.state.items shouldBe emptyList()
		f.source.loadMore(0)
		f.fetcher.calls.size shouldBe 1
	}

	test("a library smaller than one chunk is fully loaded after the first answer") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..6), total = 7)
		f.state.fullyLoaded shouldBe true
		f.source.loadMore(6)
		f.fetcher.calls.size shouldBe 1
	}

	test("one item") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..0), total = 1)
		f.state.items.names shouldBe listOf("m0")
		f.state.fullyLoaded shouldBe true
	}

	test("loadMore fetches at items.size - chunkSize / 1.7, like ItemRowAdapter") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 300)
		// 100 - 100 / 1.7 = 41.18
		f.source.loadMore(41)
		f.fetcher.calls.size shouldBe 1
		f.source.loadMore(42)
		f.fetcher.calls.size shouldBe 2
		f.fetcher.last.startIndex shouldBe 100
		f.fetcher.last.limit shouldBe 100
	}

	test("only one fetch at a time") {
		val f = Fixture()
		f.source.start()
		f.source.loadMore(0)
		f.fetcher.calls.size shouldBe 1
		f.fetcher.last.reply(movies(0..99), total = 300)
		f.source.loadMore(99)
		f.source.loadMore(99)
		f.source.loadMore(99)
		f.fetcher.calls.size shouldBe 2
	}

	test("a fast scroll past several chunks loads them one after another, in order") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 350)
		f.source.loadMore(340)
		f.fetcher.last.startIndex shouldBe 100
		f.fetcher.last.reply(movies(100..199), total = 350)
		f.source.loadMore(340)
		f.fetcher.last.startIndex shouldBe 200
		f.fetcher.last.reply(movies(200..299), total = 350)
		f.source.loadMore(340)
		f.fetcher.last.startIndex shouldBe 300
		f.fetcher.last.reply(movies(300..349), total = 350)
		f.state.items.names shouldBe movies(0..349).map { it.name }
		f.state.fullyLoaded shouldBe true
		f.source.loadMore(349)
		f.fetcher.calls.size shouldBe 4
	}

	test("a sort change while a page is loading drops the old answer and starts a new generation") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 300)
		f.source.loadMore(99)
		val oldPage = f.fetcher.last

		f.source.setSort(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING)
		f.state.generation shouldBe 1
		f.state.items shouldBe emptyList()
		f.state.totalItems shouldBe -1
		f.state.loading shouldBe true
		val newFirst = f.fetcher.last
		newFirst.startIndex shouldBe 0
		newFirst.query.sortBy shouldBe setOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME)

		oldPage.reply(movies(100..199), total = 300)
		f.state.items shouldBe emptyList()

		newFirst.reply(movies(500..599), total = 300)
		f.state.items.first().baseItem?.name shouldBe "m500"
		f.state.generation shouldBe 1
	}

	test("an answer that arrives after its fetch was cancelled is still dropped") {
		val f = Fixture(ignoreCancel = true)
		f.source.start()
		val first = f.fetcher.last
		f.source.setStartLetter("C")
		val second = f.fetcher.last
		first.reply(movies(0..99), total = 300)
		f.state.items shouldBe emptyList()
		f.state.loading shouldBe true
		second.reply(movies(0..4), total = 5)
		f.state.items.size shouldBe 5
	}

	test("the same sort, filters or letter again does not re-query") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 10)
		f.source.setSort(ItemSortBy.SORT_NAME, SortOrder.ASCENDING)
		f.source.setFilters(emptySet())
		f.source.setStartLetter(null)
		f.source.setStartLetter("#")
		f.fetcher.calls.size shouldBe 1
		f.state.generation shouldBe 0
	}

	test("a non-name sort clears the start letter") {
		val f = Fixture()
		f.source.setStartLetter("K")
		f.source.start()
		f.fetcher.last.query.nameStartsWith shouldBe "K"
		f.source.setSort(ItemSortBy.DATE_CREATED, SortOrder.ASCENDING)
		f.source.startLetter shouldBe null
		f.fetcher.last.query.nameStartsWith shouldBe null
	}

	test("the name sort keeps the start letter") {
		val f = Fixture()
		f.source.setStartLetter("K")
		f.source.setSort(ItemSortBy.SORT_NAME, SortOrder.DESCENDING)
		f.source.startLetter shouldBe "K"
	}

	test("a letter jump re-queries from 0 with nameStartsWith, and # means no letter") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 300)
		f.source.setStartLetter("M")
		f.state.generation shouldBe 1
		f.fetcher.last.startIndex shouldBe 0
		f.fetcher.last.query.nameStartsWith shouldBe "M"
		f.fetcher.last.reply(movies(0..9), total = 10)
		f.source.setStartLetter("#")
		f.state.generation shouldBe 2
		f.fetcher.last.query.nameStartsWith shouldBe null
	}

	test("filters go into the query and re-query") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 10)
		f.source.setFilters(setOf(ItemFilter.IS_UNPLAYED, ItemFilter.IS_FAVORITE))
		f.state.generation shouldBe 1
		f.fetcher.last.query.filters shouldBe setOf(ItemFilter.IS_UNPLAYED, ItemFilter.IS_FAVORITE)
	}

	test("an empty page before the end ends the list (no endless retry)") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 300)
		f.source.loadMore(99)
		f.fetcher.last.reply(emptyList(), total = 300)
		f.state.totalItems shouldBe 100
		f.state.fullyLoaded shouldBe true
		f.source.loadMore(99)
		f.fetcher.calls.size shouldBe 2
	}

	test("a total that shrank between pages is taken, never below what is loaded") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 300)
		f.source.loadMore(99)
		f.fetcher.last.reply(movies(100..149), total = 120)
		f.state.totalItems shouldBe 150
		f.state.fullyLoaded shouldBe true
	}

	test("a grown total keeps paging") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 150)
		f.source.loadMore(99)
		f.fetcher.last.reply(movies(100..149), total = 400)
		f.state.totalItems shouldBe 400
		f.source.loadMore(149)
		f.fetcher.last.startIndex shouldBe 150
	}

	test("a failed page keeps the items, shows the error, and the next loadMore retries") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 300)
		f.source.loadMore(99)
		val boom = IllegalStateException("502")
		f.fetcher.last.fail(boom)
		f.state.items.size shouldBe 100
		f.state.error shouldBe boom
		f.state.loading shouldBe false
		f.source.loadMore(99)
		f.fetcher.calls.size shouldBe 3
		f.fetcher.last.startIndex shouldBe 100
		f.fetcher.last.reply(movies(100..199), total = 300)
		f.state.error shouldBe null
		f.state.items.size shouldBe 200
	}

	test("a failed first load is retried by the next loadMore") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.fail(RuntimeException("offline"))
		f.state.totalItems shouldBe -1
		f.state.error.shouldBeInstanceOf<RuntimeException>()
		f.source.loadMore(0)
		f.fetcher.calls.size shouldBe 2
		f.fetcher.last.startIndex shouldBe 0
		f.fetcher.last.reply(movies(0..4), total = 5)
		f.state.items.size shouldBe 5
		f.state.error shouldBe null
	}

	test("a cancelled fetch is never reported as an error") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.fail(CancellationException("gone"))
		f.state.error shouldBe null
	}

	test("a transform that throws is reported as the error") {
		var n = 0
		val f = Fixture(transform = { if (n++ == 3) error("bad item") else BaseItemDtoBaseRowItem(it) })
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 10)
		f.state.error.shouldBeInstanceOf<IllegalStateException>()
		f.state.loading shouldBe false
	}

	test("closing the screen cancels the fetch without touching the state") {
		val f = Fixture()
		f.source.start()
		f.scope.cancel()
		f.state.error shouldBe null
		f.state.items shouldBe emptyList()
	}

	test("movies in name order with no letter come in one answer, box sets kept together, never paged") {
		val f = Fixture(keepSetsTogether = true)
		f.source.isKeepingSetsTogether shouldBe true
		f.source.start()
		f.fetcher.last.sets shouldBe true
		f.fetcher.last.descending shouldBe false
		f.fetcher.last.reply(movies(0..799))
		f.state.items.size shouldBe 800
		f.state.totalItems shouldBe 800
		f.state.fullyLoaded shouldBe true
		f.source.loadMore(799)
		f.fetcher.calls.size shouldBe 1
	}

	test("name order descending asks the sets loader for descending") {
		val f = Fixture(keepSetsTogether = true)
		f.source.setSort(ItemSortBy.SORT_NAME, SortOrder.DESCENDING)
		f.source.start()
		f.fetcher.last.sets shouldBe true
		f.fetcher.last.descending shouldBe true
	}

	test("a letter or a non-name sort turns set grouping off and pages again") {
		val f = Fixture(keepSetsTogether = true)
		f.source.start()
		f.fetcher.last.reply(movies(0..799))
		f.source.setStartLetter("A")
		f.source.isKeepingSetsTogether shouldBe false
		f.fetcher.last.sets shouldBe false
		f.fetcher.last.limit shouldBe 100
		f.fetcher.last.reply(movies(0..99), total = 120)
		f.source.setStartLetter(null)
		f.fetcher.last.sets shouldBe true
		f.fetcher.last.reply(movies(0..799))
		f.source.setSort(ItemSortBy.DATE_CREATED, SortOrder.DESCENDING)
		f.fetcher.last.sets shouldBe false
	}

	test("a non-movie library never groups sets") {
		val f = Fixture(keepSetsTogether = false)
		f.source.isKeepingSetsTogether shouldBe false
		f.source.start()
		f.fetcher.last.sets shouldBe false
	}

	test("refresh keeps the generation and the items on screen, and reloads all loaded items in one answer") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 300)
		f.source.loadMore(99)
		f.fetcher.last.reply(movies(100..199), total = 300)
		f.source.refresh()
		f.state.generation shouldBe 0
		f.state.items.size shouldBe 200
		f.state.loading shouldBe true
		f.fetcher.last.startIndex shouldBe 0
		f.fetcher.last.limit shouldBe 200
		f.fetcher.last.reply(movies(1000..1199), total = 301)
		f.state.items.first().baseItem?.name shouldBe "m1000"
		f.state.totalItems shouldBe 301
		f.state.generation shouldBe 0
	}

	test("a refresh answer shorter than what was loaded replaces the items and paging goes on from there") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 300)
		f.source.loadMore(99)
		f.fetcher.last.reply(movies(100..199), total = 300)
		f.source.refresh()
		f.fetcher.last.reply(movies(0..198), total = 299)
		f.state.items.size shouldBe 199
		f.state.totalItems shouldBe 299
		f.state.fullyLoaded shouldBe false
		f.source.loadMore(198)
		f.fetcher.last.startIndex shouldBe 199
	}

	test("a refresh that finds the library emptied shows an empty grid") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 10)
		f.source.refresh()
		f.fetcher.last.reply(emptyList(), total = 0)
		f.state.items shouldBe emptyList()
		f.state.totalItems shouldBe 0
		f.state.fullyLoaded shouldBe true
	}

	test("a failed refresh keeps the old items and generation") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 10)
		f.source.refresh()
		f.fetcher.last.fail(RuntimeException("timeout"))
		f.state.items.size shouldBe 10
		f.state.generation shouldBe 0
		f.state.loading shouldBe false
		f.state.error.shouldBeInstanceOf<RuntimeException>()
	}

	test("refresh while a page is loading drops that page") {
		val f = Fixture(ignoreCancel = true)
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 300)
		f.source.loadMore(99)
		val page = f.fetcher.last
		f.source.refresh()
		val refresh = f.fetcher.last
		page.reply(movies(100..199), total = 300)
		f.state.items.size shouldBe 100
		refresh.reply(movies(0..99), total = 300)
		f.state.items.size shouldBe 100
		f.state.loading shouldBe false
	}

	test("refresh of a set-grouped library asks the sets loader again") {
		val f = Fixture(keepSetsTogether = true)
		f.source.start()
		f.fetcher.last.reply(movies(0..9))
		f.source.refresh()
		f.fetcher.last.sets shouldBe true
	}

	test("refresh before start is a start") {
		val f = Fixture()
		f.source.refresh()
		f.fetcher.calls.size shouldBe 1
		f.source.start()
		f.fetcher.calls.size shouldBe 1
	}

	test("replaceItem swaps one item; a stale generation or an index outside does nothing") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 10)
		val before = f.state.items
		f.source.replaceItem(0, 3, BaseItemDtoBaseRowItem(movie(33)))
		f.state.items.names[3] shouldBe "m33"
		before.names[3] shouldBe "m3"
		f.source.replaceItem(1, 4, BaseItemDtoBaseRowItem(movie(44)))
		f.source.replaceItem(0, 10, BaseItemDtoBaseRowItem(movie(44)))
		f.source.replaceItem(0, -1, BaseItemDtoBaseRowItem(movie(44)))
		f.state.items.names shouldBe listOf("m0", "m1", "m2", "m33", "m4", "m5", "m6", "m7", "m8", "m9")
	}

	test("replaceItem with an equal copy of an item still reaches collectors as that new instance") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 10)
		val seen = mutableListOf<LibraryGridState>()
		val collector = f.scope.launch { f.source.state.collect { seen += it } }
		val refreshed = BaseItemDtoBaseRowItem(movie(3))
		// Equal to the old item (same DTO), but a new instance: the card binds by instance.
		(refreshed == f.state.items[3]) shouldBe true
		f.source.replaceItem(0, 3, refreshed)
		f.state.items[3] shouldBeSameInstanceAs refreshed
		seen.size shouldBe 2
		seen.last().items[3] shouldBeSameInstanceAs refreshed
		collector.cancel()
	}

	test("a refresh answer with the same items still reaches collectors as new instances") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 10)
		val before = f.state.items[0]
		f.source.refresh()
		f.fetcher.last.reply(movies(0..9), total = 10)
		(f.state.items[0] === before) shouldBe false
	}

	test("removeItem shifts later items down and lowers the total; stale or outside does nothing") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 10)
		f.source.removeItem(0, 2)
		f.state.items.names shouldBe listOf("m0", "m1", "m3", "m4", "m5", "m6", "m7", "m8", "m9")
		f.state.totalItems shouldBe 9
		f.state.fullyLoaded shouldBe true
		f.source.removeItem(5, 0)
		f.source.removeItem(0, 9)
		f.state.items.size shouldBe 9
	}

	test("removeItem on an empty grid does nothing") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(emptyList(), total = 0)
		f.source.removeItem(0, 0)
		f.state.totalItems shouldBe 0
	}

	test("removeItem while a page loads cancels it, and the next loadMore asks at the new size") {
		val f = Fixture(ignoreCancel = true)
		f.source.start()
		f.fetcher.last.reply(movies(0..99), total = 300)
		f.source.loadMore(99)
		val page = f.fetcher.last
		page.startIndex shouldBe 100
		f.source.removeItem(0, 50)
		f.state.loading shouldBe false
		f.state.totalItems shouldBe 299
		page.reply(movies(100..199), total = 300)
		f.state.items.size shouldBe 99
		f.source.loadMore(98)
		f.fetcher.last.startIndex shouldBe 99
		f.fetcher.last.reply(movies(101..200), total = 299)
		f.state.items.size shouldBe 199
	}

	test("removeItem during a refresh keeps the refresh going") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 10)
		f.source.refresh()
		f.source.removeItem(0, 0)
		f.state.loading shouldBe true
		f.fetcher.last.reply(movies(1..9), total = 9)
		f.state.items.size shouldBe 9
		f.state.loading shouldBe false
	}

	test("chunkSize must be positive") {
		shouldThrow<IllegalArgumentException> { Fixture(chunkSize = 0) }
	}
	test("preview URLs: no builder, no URLs, and the items are as before") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..4))
		f.state.items.size shouldBe 5
		f.source.previewUrl(id(0)) shouldBe null
		f.source.previewUrl(null) shouldBe null
	}

	test("preview URLs: a builder set before start builds every item of the first answer") {
		val f = Fixture()
		val b = Builder()
		f.source.setPreviewUrlBuilder(b.fn)
		b.asked.size shouldBe 0
		f.source.start()
		f.fetcher.last.reply(movies(0..9))
		(0..9).map { f.source.previewUrl(id(it)) } shouldBe (0..9).map { "u$it" }
		b.asked.size shouldBe 10
	}

	test("preview URLs: the items are published at once, the URLs when they are built") {
		val held = HeldDispatcher()
		val f = Fixture(urlDispatcher = held)
		f.source.setPreviewUrlBuilder(Builder().fn)
		f.source.start()
		f.fetcher.last.reply(movies(0..9))
		f.state.items.size shouldBe 10
		f.state.loading shouldBe false
		f.source.previewUrl(id(3)) shouldBe null
		held.runAll()
		f.source.previewUrl(id(3)) shouldBe "u3"
	}

	test("preview URLs: a builder set after the load builds the loaded items") {
		val f = Fixture()
		f.source.start()
		f.fetcher.last.reply(movies(0..4))
		val b = Builder()
		f.source.setPreviewUrlBuilder(b.fn)
		f.source.previewUrl(id(4)) shouldBe "u4"
		b.asked.size shouldBe 5
	}

	test("preview URLs: a next page builds only its own items") {
		val f = Fixture(chunkSize = 10)
		val b = Builder()
		f.source.setPreviewUrlBuilder(b.fn)
		f.source.start()
		f.fetcher.last.reply(movies(0..9), total = 20)
		f.source.loadMore(9)
		f.fetcher.last.reply(movies(10..19), total = 20)
		b.asked.size shouldBe 20
		f.source.previewUrl(id(15)) shouldBe "u15"
		f.source.previewUrl(id(2)) shouldBe "u2"
	}

	test("preview URLs: replaceItem builds that item again from the new instance") {
		val f = Fixture()
		val b = Builder()
		f.source.setPreviewUrlBuilder(b.fn)
		f.source.start()
		f.fetcher.last.reply(movies(0..4))
		val fresh = BaseItemDtoBaseRowItem(movie(2))
		f.source.replaceItem(0, 2, fresh)
		b.asked.last() shouldBeSameInstanceAs fresh
		b.asked.size shouldBe 6
		f.source.previewUrl(id(2)) shouldBe "u2"
	}

	test("preview URLs: after replaceItem the old URL is gone until the new one is built") {
		val held = HeldDispatcher()
		val f = Fixture(urlDispatcher = held)
		f.source.setPreviewUrlBuilder(Builder().fn)
		f.source.start()
		f.fetcher.last.reply(movies(0..4))
		held.runAll()
		f.source.previewUrl(id(2)) shouldBe "u2"
		f.source.replaceItem(0, 2, BaseItemDtoBaseRowItem(movie(2)))
		f.source.previewUrl(id(2)) shouldBe null
		f.source.previewUrl(id(1)) shouldBe "u1"
		held.runAll()
		f.source.previewUrl(id(2)) shouldBe "u2"
	}

	test("preview URLs: a re-query drops them, and a build still running for the old generation is dropped") {
		val held = HeldDispatcher()
		val f = Fixture(urlDispatcher = held)
		f.source.setPreviewUrlBuilder(Builder().fn)
		f.source.start()
		f.fetcher.last.reply(movies(0..4))
		held.pending shouldBe 1
		f.source.setSort(ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING)
		held.runAll()
		f.source.previewUrl(id(0)) shouldBe null
		f.fetcher.last.reply(movies(5..6))
		held.runAll()
		f.source.previewUrl(id(5)) shouldBe "u5"
		f.source.previewUrl(id(0)) shouldBe null
	}

	test("preview URLs: an item the builder fails on or has no URL for keeps the old path; the rest are built") {
		val f = Fixture()
		f.source.setPreviewUrlBuilder(Builder(fail = setOf("m1"), none = setOf("m3")).fn)
		f.source.start()
		f.fetcher.last.reply(movies(0..4))
		f.source.previewUrl(id(1)) shouldBe null
		f.source.previewUrl(id(3)) shouldBe null
		listOf(0, 2, 4).map { f.source.previewUrl(id(it)) } shouldBe listOf("u0", "u2", "u4")
	}

	test("preview URLs: a null builder drops them; a new builder replaces the old URLs") {
		val f = Fixture()
		f.source.setPreviewUrlBuilder(Builder().fn)
		f.source.start()
		f.fetcher.last.reply(movies(0..2))
		f.source.setPreviewUrlBuilder(null)
		f.source.previewUrl(id(0)) shouldBe null
		f.source.setPreviewUrlBuilder(Builder(tag = "v").fn)
		f.source.previewUrl(id(0)) shouldBe "v0"
	}

	test("preview URLs: a builder changed while a build runs: the old build's URLs never land") {
		val held = HeldDispatcher()
		val f = Fixture(urlDispatcher = held)
		f.source.setPreviewUrlBuilder(Builder().fn)
		f.source.start()
		f.fetcher.last.reply(movies(0..2))
		f.source.setPreviewUrlBuilder(Builder(tag = "v").fn)
		held.runAll()
		(0..2).map { f.source.previewUrl(id(it)) } shouldBe listOf("v0", "v1", "v2")
	}

	test("preview URLs: a refresh builds every item again (new instances)") {
		val f = Fixture()
		val b = Builder()
		f.source.setPreviewUrlBuilder(b.fn)
		f.source.start()
		f.fetcher.last.reply(movies(0..4))
		f.source.refresh()
		f.fetcher.last.reply(movies(0..4))
		b.asked.size shouldBe 10
		f.source.previewUrl(id(4)) shouldBe "u4"
	}

	test("preview URLs: a large library (5000) is built in full") {
		val f = Fixture(chunkSize = 5000)
		f.source.setPreviewUrlBuilder(Builder().fn)
		f.source.start()
		f.fetcher.last.reply(movies(0..4999))
		f.source.previewUrl(id(4999)) shouldBe "u4999"
	}

	test("preview URLs: an empty answer builds nothing and fails nothing") {
		val f = Fixture()
		val b = Builder()
		f.source.setPreviewUrlBuilder(b.fn)
		f.source.start()
		f.fetcher.last.reply(emptyList(), total = 0)
		b.asked.size shouldBe 0
	}
})

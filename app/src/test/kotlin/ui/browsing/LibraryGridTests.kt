package org.jellyfin.androidtv.ui.browsing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.browsing.grid.LibraryGridState
import org.jellyfin.androidtv.ui.browsing.grid.centerScrollDistance
import org.jellyfin.androidtv.ui.browsing.grid.restoreIndex
import org.jellyfin.androidtv.ui.browsing.grid.retryFocus
import org.jellyfin.androidtv.ui.browsing.grid.isPlaced
import org.jellyfin.androidtv.ui.browsing.grid.RefocusGuard
import org.jellyfin.androidtv.ui.browsing.grid.refocusAfterFocusLeft
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.ui.presentation.BoundCard
import org.jellyfin.androidtv.ui.presentation.gridBinding

private fun state(items: Int = 0, total: Int = -1, loading: Boolean = false, generation: Int = 0, error: Throwable? = null) =
	LibraryGridState(
		items = List(items) { GridButtonBaseRowItem(GridButton(it, "b$it")) },
		totalItems = total,
		loading = loading,
		generation = generation,
		error = error,
	)

class LibraryGridTests : FunSpec({
	// Window 900 px tall, a line of cards 300 px tall.
	test("a line below the middle scrolls up until it is in the middle") {
		centerScrollDistance(offset = 600f, size = 300f, containerSize = 900f) shouldBe 300f
	}

	test("a line above the middle scrolls down until it is in the middle") {
		centerScrollDistance(offset = 0f, size = 300f, containerSize = 900f) shouldBe -300f
	}

	test("a line already in the middle does not scroll") {
		centerScrollDistance(offset = 300f, size = 300f, containerSize = 900f) shouldBe 0f
	}

	test("a line taller than the window still centres") {
		centerScrollDistance(offset = 0f, size = 1100f, containerSize = 900f) shouldBe 100f
	}

	test("restore: the same generation focuses the saved card") {
		restoreIndex(saved = 37, savedGeneration = 2, generation = 2, count = 167) shouldBe 37
	}

	test("restore: a new generation (sort, filter, letter) starts at the first card") {
		restoreIndex(saved = 37, savedGeneration = 2, generation = 3, count = 167) shouldBe 0
	}

	test("restore: a saved card past a shorter list clamps to the last card") {
		restoreIndex(saved = 166, savedGeneration = 1, generation = 1, count = 160) shouldBe 159
	}

	test("restore: nothing saved yet focuses the first card") {
		restoreIndex(saved = -1, savedGeneration = 1, generation = 1, count = 10) shouldBe 0
	}

	test("restore: an empty grid has nothing to focus") {
		restoreIndex(saved = 5, savedGeneration = 1, generation = 1, count = 0) shouldBe -1
		restoreIndex(saved = 5, savedGeneration = 1, generation = 2, count = 0) shouldBe -1
	}

	test("binding: the same item instance keeps its binding, so a recompose reloads nothing") {
		val holder = arrayOfNulls<BoundCard>(1)
		val item = GridButtonBaseRowItem(GridButton(1, "one"))
		val first = gridBinding(holder, item)
		gridBinding(holder, item) shouldBeSameInstanceAs first
	}

	test("binding: a new instance with the same id (refreshed item) gets a new binding") {
		val holder = arrayOfNulls<BoundCard>(1)
		val first = gridBinding(holder, GridButtonBaseRowItem(GridButton(1, "one")))
		val second = gridBinding(holder, GridButtonBaseRowItem(GridButton(1, "one")))
		second shouldNotBeSameInstanceAs first
		holder[0] shouldBeSameInstanceAs second
	}

	test("binding: a grid binding never waits or holds a size") {
		val bound = gridBinding(arrayOfNulls(1), GridButtonBaseRowItem(GridButton(1, "one")))
		bound.bodyDelayFrames shouldBe 0
		bound.holdSize shouldBe null
	}

	test("answer: an answer that landed is told to the fragment") {
		isNewAnswer(state(items = 100, total = 167)) shouldBe true
	}

	test("answer: an empty library answer is told too, so the toolbar gets the focus") {
		isNewAnswer(state(items = 0, total = 0)) shouldBe true
	}

	test("answer: nothing before the first answer or while a fetch is in flight") {
		isNewAnswer(state(total = -1)) shouldBe false
		isNewAnswer(state(items = 100, total = 167, loading = true)) shouldBe false
	}

	test("answer: a failed page with items kept is still an end of fetch") {
		isNewAnswer(state(items = 100, total = 167, error = RuntimeException("x"))) shouldBe true
	}

	test("answer: a failed first fetch is told too, as the old retrieve-finished listener did") {
		isNewAnswer(state(total = -1, error = RuntimeException("offline"))) shouldBe true
	}

	test("focus: a first false is retried after a frame, and the second try takes") {
		val answers = ArrayDeque(listOf(false, true))
		var frames = 0
		retryFocus(10, { answers.removeFirst() }, { frames++ }) shouldBe true
		frames shouldBe 1
	}

	test("focus: a card that never takes the focus gives up after the attempts, one frame each") {
		var tries = 0
		var frames = 0
		retryFocus(10, { tries++; false }, { frames++ }) shouldBe false
		tries shouldBe 10
		frames shouldBe 10
	}

	test("focus: a card that takes it at once waits no frame") {
		var frames = 0
		retryFocus(10, { true }, { frames++ }) shouldBe true
		frames shouldBe 0
	}

	test("placed: a visible card is focused at once, a cached or far one is scrolled to first") {
		val visible = (22..43).toList()
		isPlaced(visible, 30) shouldBe true
		isPlaced(visible, 50) shouldBe false
		isPlaced(visible, 5) shouldBe false
		isPlaced(emptyList(), 0) shouldBe false
	}

	test("focus left: only a window with no focus, while the grid's screen is resumed and shown, gets the card back") {
		refocusAfterFocusLeft(focusSomewhere = false, attached = true, shown = true, resumed = true, windowFocused = true) shouldBe true
		// Details opened, a toolbar button or the settings overlay took it: leave it there.
		refocusAfterFocusLeft(focusSomewhere = true, attached = true, shown = true, resumed = true, windowFocused = true) shouldBe false
		// The grid is going away (detached, hidden, paused) or another window has the focus (a dialog).
		refocusAfterFocusLeft(focusSomewhere = false, attached = false, shown = true, resumed = true, windowFocused = true) shouldBe false
		refocusAfterFocusLeft(focusSomewhere = false, attached = true, shown = false, resumed = true, windowFocused = true) shouldBe false
		refocusAfterFocusLeft(focusSomewhere = false, attached = true, shown = true, resumed = false, windowFocused = true) shouldBe false
		refocusAfterFocusLeft(focusSomewhere = false, attached = true, shown = true, resumed = true, windowFocused = false) shouldBe false
	}

	test("focus left: at most 3 returns within 2 s, then again once the window has passed") {
		val guard = RefocusGuard()
		guard.take(0) shouldBe true
		guard.take(500) shouldBe true
		guard.take(1_000) shouldBe true
		guard.take(1_500) shouldBe false
		guard.take(2_001) shouldBe true
	}
})

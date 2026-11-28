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
import org.jellyfin.androidtv.ui.browsing.grid.OneLineCacheWindow
import org.jellyfin.androidtv.ui.browsing.grid.linePitchPx
import androidx.compose.ui.unit.Density
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

	test("cache window: the pitch is the tallest laid-out card plus the line gap") {
		linePitchPx(intArrayOf(380, 412, 395), 30, 327) shouldBe 442
		// No layout yet: the start value (card + gap).
		linePitchPx(IntArray(0), 30, 327) shouldBe 327
	}

	test("cache window: reach each way is one full pitch, so a cut line can't use it all up") {
		val window = OneLineCacheWindow(327)
		val density = Density(1f)
		with(window) { density.calculateAheadWindow(1080) } shouldBe 327
		window.linePx = 442
		with(window) { density.calculateAheadWindow(1080) } shouldBe 442
		with(window) { density.calculateBehindWindow(1080) } shouldBe 442
		// The window fills lines while (reach - hidden part of the cut line) > 0: with a full pitch,
		// a cut line hides at most pitch - 1 px, so at least one more line is always kept ahead.
		(window.linePx - (442 - 1) > 0) shouldBe true
		// Never 0 (0 turns the window off).
		window.linePx = 0
		window.linePx shouldBe 1
		OneLineCacheWindow(0).linePx shouldBe 1
	}
})

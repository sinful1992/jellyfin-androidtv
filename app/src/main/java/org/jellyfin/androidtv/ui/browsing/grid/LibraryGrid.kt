package org.jellyfin.androidtv.ui.browsing.grid

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.browsing.GridWrap
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.presentation.GridCardContent

/**
 * Where the focused line sits: the leanback grid kept it in the middle of the window (window and item
 * alignment both 50 %), and the scroll bounds stop it at the two ends. Compose's default only scrolls
 * a card that is off the edge, which would make the grid move at a different time than it used to.
 */
internal fun centerScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
	offset - (containerSize - size) / 2f

/** The index to focus when the grid shows [count] items: the saved one if it is still valid, else 0. */
internal fun restoreIndex(saved: Int, savedGeneration: Int, generation: Int, count: Int): Int {
	if (count <= 0) return -1
	if (savedGeneration != generation) return 0
	return saved.coerceIn(0, count - 1)
}

/**
 * What the grid keeps across its view going away (details, then Back): the focused index and the
 * generation it belongs to, and the scroll position, so the new composition opens where the old one
 * was and puts the focus back on the same card.
 */
@Stable
class LibraryGridMemory {
	var focusedIndex: Int = -1
		internal set
	var generation: Int = Int.MIN_VALUE
		internal set
	internal var firstVisibleItemIndex = 0
	internal var firstVisibleItemScrollOffset = 0

	/** Set by the grid while it is composed: moves the focus to an index, scrolling to it first. */
	internal var focusIndex: ((Int) -> Unit)? = null

	/** Puts the focus on [index] (the fragment calls this, e.g. focus back from the toolbar). */
	fun requestFocus(index: Int) {
		focusIndex?.invoke(index)
	}
}

@OptIn(ExperimentalFoundationApi::class)
private val CenterBringIntoViewSpec = object : BringIntoViewSpec {
	override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
		centerScrollDistance(offset, size, containerSize)
}

/**
 * The library grid: one LazyVerticalGrid of cards in one composition, in place of leanback's
 * VerticalGridView with a ComposeView per card (task 200). Same cards ([GridCardContent]), column
 * count, spacing and padding as the leanback grid the fragment sized (setAutoCardGridValues).
 *
 * Each card keeps its own focused state, so a move recomposes two cards, not the screen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGrid(
	items: List<BaseRowItem>,
	generation: Int,
	columns: Int,
	cardHeight: Int,
	imageType: ImageType,
	spacingHorizontalPx: Int,
	spacingVerticalPx: Int,
	paddingHorizontalPx: Int,
	paddingVerticalPx: Int,
	memory: LibraryGridMemory,
	onFocused: (index: Int, item: BaseRowItem) -> Unit,
	onClick: (BaseRowItem) -> Unit,
	onLongClick: (BaseRowItem) -> Unit,
	onScrollingChanged: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	val density = LocalDensity.current
	val gridState = rememberLazyGridState(memory.firstVisibleItemIndex, memory.firstVisibleItemScrollOffset)
	val scope = rememberCoroutineScope()
	val requesters = remember { HashMap<Int, FocusRequester>() }
	val currentOnFocused by rememberUpdatedState(onFocused)
	val currentOnClick by rememberUpdatedState(onClick)
	val currentOnLongClick by rememberUpdatedState(onLongClick)
	val currentItems by rememberUpdatedState(items)

	DisposableEffect(memory, gridState) {
		memory.focusIndex = { index -> scope.launch { focusIndex(gridState, requesters, index) } }
		onDispose {
			memory.focusIndex = null
			memory.firstVisibleItemIndex = gridState.firstVisibleItemIndex
			memory.firstVisibleItemScrollOffset = gridState.firstVisibleItemScrollOffset
		}
	}

	LaunchedEffect(gridState) {
		snapshotFlow { gridState.isScrollInProgress }
			.distinctUntilChanged()
			.collect { onScrollingChanged(it) }
	}

	// The first time items show for a generation: the saved card when it is the same generation
	// (Back from details), otherwise the first card, at the top (a new sort, filter or letter).
	val hasItems = items.isNotEmpty()
	LaunchedEffect(generation, hasItems) {
		if (!hasItems) return@LaunchedEffect
		val target = restoreIndex(memory.focusedIndex, memory.generation, generation, currentItems.size)
		if (memory.generation != generation) {
			memory.generation = generation
			memory.focusedIndex = target
			gridState.scrollToItem(0)
		}
		focusIndex(gridState, requesters, target)
	}

	val spacingH = with(density) { spacingHorizontalPx.toDp() }
	val spacingV = with(density) { spacingVerticalPx.toDp() }
	val paddingH = with(density) { paddingHorizontalPx.toDp() }
	val paddingV = with(density) { paddingVerticalPx.toDp() }

	CompositionLocalProvider(LocalBringIntoViewSpec provides CenterBringIntoViewSpec) {
		LazyVerticalGrid(
			columns = GridCells.Fixed(columns.coerceAtLeast(1)),
			state = gridState,
			contentPadding = PaddingValues(horizontal = paddingH, vertical = paddingV),
			horizontalArrangement = Arrangement.spacedBy(spacingH, Alignment.CenterHorizontally),
			verticalArrangement = Arrangement.spacedBy(spacingV),
			modifier = modifier.focusRestorer(),
		) {
			items(count = items.size, contentType = { 0 }) { index ->
				val item = items[index]
				var focused by remember { mutableStateOf(false) }
				val requester = remember { FocusRequester() }

				DisposableEffect(index, requester) {
					requesters[index] = requester
					onDispose { if (requesters[index] === requester) requesters.remove(index) }
				}

				Box(
					contentAlignment = Alignment.BottomCenter,
					modifier = Modifier
						.focusRequester(requester)
						.onFocusChanged { state ->
							if (state.isFocused == focused) return@onFocusChanged
							focused = state.isFocused
							if (state.isFocused) {
								memory.focusedIndex = index
								currentItems.getOrNull(index)?.let { currentOnFocused(index, it) }
							}
						}
						.onPreviewKeyEvent { event ->
							if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
							val target = GridWrap.target(event.nativeKeyEvent.keyCode, index, columns, currentItems.size)
							if (target == GridWrap.NONE) return@onPreviewKeyEvent false
							scope.launch { focusIndex(gridState, requesters, target) }
							true
						}
						.combinedClickable(
							interactionSource = null,
							indication = null,
							onLongClick = { currentItems.getOrNull(index)?.let(currentOnLongClick) },
							onClick = { currentItems.getOrNull(index)?.let(currentOnClick) },
						),
				) {
					GridCardContent(
						item = item,
						focused = focused,
						imageType = imageType,
						staticHeight = cardHeight,
					)
				}
			}
		}
	}
}

/**
 * Focuses the card at [index]: at once when it is composed, else after scrolling it into the window
 * and waiting (a few frames at most) for its cell to register its FocusRequester.
 */
private suspend fun focusIndex(state: LazyGridState, requesters: Map<Int, FocusRequester>, index: Int) {
	if (index < 0) return
	val tryFocus = { requesters[index]?.requestFocusOrFalse() ?: false }
	if (tryFocus()) return

	state.scrollToItem(index)
	retryFocus(FocusWaitFrames, tryFocus) { withFrameNanos { } }
}

private const val FocusWaitFrames = 10

/**
 * Tries [tryFocus] up to [attempts] times, waiting [nextFrame] after each miss; true once it took.
 * A miss is a false answer: the card is not composed yet, or its node could not take the focus.
 */
internal suspend fun retryFocus(attempts: Int, tryFocus: () -> Boolean, nextFrame: suspend () -> Unit): Boolean {
	repeat(attempts) {
		if (tryFocus()) return true
		nextFrame()
	}
	return false
}

/**
 * The Boolean form of requestFocus: the plain requestFocus() returns Unit even when nothing took the
 * focus, which would end the wait above on a miss (linus, review of b64eeba07). Not yet attached
 * (an exception) is a miss too.
 */
private fun FocusRequester.requestFocusOrFalse(): Boolean =
	runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)

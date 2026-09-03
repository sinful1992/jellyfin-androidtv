package org.jellyfin.androidtv.ui.player.base

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The height the controls occupy above the overscan inset.
 *
 * A floor the controls keep to rather than a measurement of them, so anything drawn over the video
 * can be parked clear of the controls without waiting for them to be laid out once.
 */
val PlayerControlsHeight = 100.dp

/**
 * How much of the screen each scrim covers. Both run well past the content they carry: a ramp that
 * ends where the text starts reads as a band laid over the picture rather than as shading.
 */
private const val HeaderScrimFraction = 1f / 3
private const val ControlsScrimFraction = 0.45f

private val HeaderScrim = arrayOf(
	0f to Color.Black.copy(alpha = 0.75f),
	0.45f to Color.Black.copy(alpha = 0.25f),
	1f to Color.Transparent,
)

private val ControlsScrim = arrayOf(
	0f to Color.Transparent,
	0.55f to Color.Black.copy(alpha = 0.5f),
	1f to Color.Black.copy(alpha = 0.92f),
)

@Composable
fun PlayerOverlayLayout(
	modifier: Modifier = Modifier,
	visibilityState: PlayerOverlayVisibilityState = rememberPlayerOverlayVisibility(),
	header: (@Composable () -> Unit)? = null,
	controls: (@Composable () -> Unit)? = null,
	/**
	 * Whether moving the focus off the top of the controls closes them. Callers that put
	 * something focusable above the controls turn this off while it is there, so the focus can
	 * reach it instead.
	 */
	hideOnFocusExitUp: Boolean = true,
	/**
	 * What the select key does while the controls are down, if anything.
	 *
	 * Note that this only ever fires while this layout holds the focus. Anything a caller draws
	 * over the video as a sibling of this layout — the next up card, say — takes the focus off it
	 * and owns the select key for as long as it is there, which is what keeps one press from
	 * being answered twice.
	 */
	onSelect: (() -> Unit)? = null,
	/**
	 * The layout's own focus target, for callers that take the focus away from it and need to give
	 * it back.
	 */
	focusRequester: FocusRequester = remember { FocusRequester() },
) = Box(
	modifier = modifier
		.fillMaxSize()
		.focusRequester(focusRequester)
		.focusable()
		.onPreviewKeyEvent {
			// Reset hide timer on key presses
			if (visibilityState.visible) visibilityState.show()

			// Otherwise, only act on key down
			if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

			val isSelect = it.key == Key.DirectionCenter || it.key == Key.Enter

			if (it.key == Key.Back && visibilityState.visible) {
				visibilityState.hide()
				true
			} else if (isSelect && onSelect != null && !visibilityState.visible) {
				// Select acts on what is playing rather than opening the controls so the same
				// action can be gone and found in them. This has to be tested before the catch-all
				// below, which counts select among the keys that only open the controls and would
				// otherwise swallow it — leaving no press that pauses at all.
				onSelect()
				visibilityState.show()
				true
			} else if (!it.nativeKeyEvent.isSystem && !visibilityState.visible) {
				visibilityState.show()
				true
			} else {
				false
			}
		}
		.onKeyEvent {
			// With the controls up the select key belongs to whatever holds the focus, so this runs
			// on the way back out instead: after the focused control has had its turn and passed.
			// The buttons take their own presses and never reach here, while the seek bar ignores
			// everything but left and right and falls through — which is where the focus lands when
			// the controls open, and so where pausing has to work from to be reversible.
			if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
			if (onSelect == null || !visibilityState.visible) return@onKeyEvent false
			if (it.key != Key.DirectionCenter && it.key != Key.Enter) return@onKeyEvent false

			onSelect()
			true
		}
) {
	if (header != null) {
		AnimatedVisibility(
			visible = visibilityState.visible,
			modifier = Modifier
				.align(Alignment.TopCenter),
			enter = slideInVertically() + fadeIn(),
			exit = slideOutVertically() + fadeOut(),
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.fillMaxHeight(HeaderScrimFraction)
					.background(brush = Brush.verticalGradient(*HeaderScrim))
					.overscan()
			) {
				header()
			}
		}
	}

	if (controls != null) {
		AnimatedVisibility(
			visible = visibilityState.visible,
			modifier = Modifier
				.align(Alignment.BottomCenter),
			// The scrim is taller than the controls it carries, so sliding by the whole of it
			// would throw them up the screen. A short rise under the fade is enough.
			enter = slideInVertically(initialOffsetY = { it / 4 }) + fadeIn(),
			exit = slideOutVertically(targetOffsetY = { it / 4 }) + fadeOut(),
		) {
			// Focus trap to detect when controls need to be closed by moving the focus up
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(0.dp)
					.focusTarget()
			)

			Box(
				contentAlignment = Alignment.BottomCenter,
				modifier = Modifier
					.fillMaxWidth()
					.fillMaxHeight(ControlsScrimFraction)
					.background(brush = Brush.verticalGradient(*ControlsScrim))
					.overscan()
					.focusProperties {
						// Hide overlay when focus is moved out by going up
						onExit = {
							if (hideOnFocusExitUp && requestedFocusDirection == FocusDirection.Up) {
								Timber.i("Hide reason: focus moved up")
								visibilityState.hide()
								cancelFocusChange()
							}
						}
					},
			) {
				JellyfinTheme(
					colorScheme = JellyfinTheme.colorScheme.copy(
						button = Color.Transparent
					)
				) {
					controls()
				}
			}
		}
	}
}

data class PlayerOverlayVisibilityState(
	val visible: Boolean,

	val toggle: () -> Unit,
	val show: () -> Unit,
	val hide: () -> Unit,
)

@Composable
fun rememberPlayerOverlayVisibility(
	timeout: Duration = 5.seconds,
	/**
	 * Whether the controls, once up, should stay up.
	 *
	 * The timeout exists because the controls sit over a moving picture. Over a stopped one there
	 * is nothing to be in the way of, and letting them time out leaves a still frame with no sign
	 * of why it is still.
	 */
	hold: Boolean = false,
): PlayerOverlayVisibilityState {
	var timerVisible by remember { mutableStateOf(false) }

	// Bumped rather than read, so that asking for the controls while they are already up restarts
	// the countdown instead of leaving the original one to run out under the request.
	var showCount by remember { mutableIntStateOf(0) }

	LaunchedEffect(showCount, timerVisible, hold) {
		if (!timerVisible || hold) return@LaunchedEffect

		delay(timeout)
		timerVisible = false
	}

	fun show() {
		timerVisible = true
		showCount++
	}

	fun hide() {
		timerVisible = false
	}

	fun toggle() {
		if (timerVisible) hide()
		else show()
	}

	// Force visibility when not the active window, reset timer when it changes
	// to make sure popups keep the overlay visible
	val windowInfo = LocalWindowInfo.current
	val visible = timerVisible || !windowInfo.isWindowFocused

	var previousIsWindowFocused by remember { mutableStateOf(windowInfo.isWindowFocused) }
	LaunchedEffect(windowInfo.isWindowFocused) {
		if (windowInfo.isWindowFocused != previousIsWindowFocused) show()
		previousIsWindowFocused = windowInfo.isWindowFocused
	}

	return PlayerOverlayVisibilityState(
		visible = visible,
		toggle = ::toggle,
		show = ::show,
		hide = ::hide,
	)
}

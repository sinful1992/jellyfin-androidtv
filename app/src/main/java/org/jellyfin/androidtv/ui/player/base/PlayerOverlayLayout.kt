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
	/**
	 * Whether closing the controls hands the focus back to this layout. Callers that put something
	 * focusable over the video turn this off while it is there, so it can hold the focus instead.
	 */
	reclaimFocusOnHide: Boolean = true,
) = Box(
	modifier = modifier
		.fillMaxSize()
		.focusRequester(focusRequester)
		.focusable()
		.onPreviewKeyEvent {
			val action = playerOverlayKeyAction(
				isKeyDown = it.type == KeyEventType.KeyDown,
				key = it.key,
				isSystem = it.nativeKeyEvent.isSystem,
				visible = visibilityState.visible,
				hasOnSelect = onSelect != null,
			)

			when (action) {
				PlayerOverlayKeyAction.Ignore -> false
				PlayerOverlayKeyAction.Hide -> {
					visibilityState.hide()
					true
				}
				PlayerOverlayKeyAction.SelectAndShow -> {
					onSelect?.invoke()
					visibilityState.show()
					true
				}
				PlayerOverlayKeyAction.Show -> {
					visibilityState.show()
					true
				}
				PlayerOverlayKeyAction.RestartTimer -> {
					visibilityState.show()
					false
				}
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
	// Closing the controls takes them out of the composition, and whatever inside them held the
	// focus goes with them. Over a bare picture nothing else is asking for it, so it is left
	// nowhere at all and the next press reaches no handler — not even the one that brings the
	// controls back, which is the state the player gets stuck in after using them once.
	LaunchedEffect(visibilityState.visible, reclaimFocusOnHide) {
		if (visibilityState.visible || !reclaimFocusOnHide) return@LaunchedEffect

		focusRequester.requestFocus()
	}

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

internal enum class PlayerOverlayKeyAction {
	/** Not ours: let the key go on to whatever is focused, or out of the player. */
	Ignore,
	Hide,
	SelectAndShow,
	Show,
	/** Not ours either, but it counts as activity while the controls are up. */
	RestartTimer,
}

/**
 * What the overlay does with a key, from the key and the state the overlay was last drawn with.
 *
 * Only key down acts. The visible flag here is read from the last composition, and a Back up
 * that arrives before the next frame (adb input sends down and up within a few ms) still sees
 * the controls as up: restarting their timer on it put them straight back after Back closed them,
 * so Back never closed the controls and never left the player (task 196).
 */
internal fun playerOverlayKeyAction(
	isKeyDown: Boolean,
	key: Key,
	isSystem: Boolean,
	visible: Boolean,
	hasOnSelect: Boolean,
): PlayerOverlayKeyAction {
	if (!isKeyDown) return PlayerOverlayKeyAction.Ignore

	val isSelect = key == Key.DirectionCenter || key == Key.Enter

	return when {
		key == Key.Back && visible -> PlayerOverlayKeyAction.Hide
		// Select acts on what is playing rather than opening the controls. It has to be tested
		// before the catch-all below, which would otherwise swallow it.
		isSelect && hasOnSelect && !visible -> PlayerOverlayKeyAction.SelectAndShow
		!isSystem && !visible -> PlayerOverlayKeyAction.Show
		visible -> PlayerOverlayKeyAction.RestartTimer
		else -> PlayerOverlayKeyAction.Ignore
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

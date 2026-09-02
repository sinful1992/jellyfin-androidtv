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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The height the controls occupy above the overscan inset.
 *
 * Fixed rather than measured so anything drawn over the video can be parked clear of the controls
 * without waiting for them to be laid out once.
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
) = Box(
	modifier = modifier
		.fillMaxSize()
		.focusable()
		.onPreviewKeyEvent {
			// Reset hide timer on key presses
			if (visibilityState.visible) visibilityState.show()

			// Otherwise, only act on key down
			if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

			if (it.key == Key.Back && visibilityState.visible) {
				visibilityState.hide()
				true
			} else if (!it.nativeKeyEvent.isSystem && !visibilityState.visible) {
				visibilityState.show()
				true
			} else {
				false
			}
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
							if (requestedFocusDirection == FocusDirection.Up) {
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
): PlayerOverlayVisibilityState {
	val scope = rememberCoroutineScope()
	var timerVisible by remember { mutableStateOf(false) }
	var timerJob by remember { mutableStateOf<Job?>(null) }

	fun show() {
		timerJob?.cancel()
		timerVisible = true
		timerJob = scope.launch {
			delay(timeout)
			timerVisible = false
		}
	}

	fun hide() {
		timerJob?.cancel()
		timerJob = null
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

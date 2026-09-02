package org.jellyfin.androidtv.ui.player.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.player.base.PlayerControlsHeight
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayLayout
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.koin.compose.koinInject

/** Keys that mean "go somewhere else", as opposed to acting on what is in front of you. */
private val NavigationKeys = setOf(
	Key.DirectionUp,
	Key.DirectionDown,
	Key.DirectionLeft,
	Key.DirectionRight,
)

@Composable
fun VideoPlayerOverlay(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	mediaToastRegistry: MediaToastRegistry,
) {
	val visibilityState = rememberPlayerOverlayVisibility()
	val nextUp = rememberPlayerNextUpState(playbackManager)
	val coroutineScope = rememberCoroutineScope()
	val nextUpFocusRequester = remember { FocusRequester() }

	// The card announces itself without dragging the controls along, so the picture stays clear.
	// While it is up Back dismisses it rather than leaving playback.
	BackHandler(enabled = nextUp.item != null, onBack = nextUp.dismiss)

	// Offering to skip ahead is worth nothing if the offer cannot be taken. With the controls
	// down there is nothing else asking for the focus, so the card holds it; when they open they
	// take it back, and moving up from the seek bar returns it.
	LaunchedEffect(nextUp.item, visibilityState.visible) {
		if (nextUp.item == null || visibilityState.visible) return@LaunchedEffect

		// This runs as soon as the composition is applied, which can be before the card it names
		// has been laid out and can take anything. Waiting a frame is enough for it to exist.
		withFrameNanos { }
		nextUpFocusRequester.requestFocus()
	}

	// The overlay's own key handling is a sibling of the card rather than a parent of it, so
	// while the card holds the focus nothing else can bring the controls up. Trying to navigate
	// away from the card is the request to do so.
	fun showControlsOnNavigation(event: KeyEvent): Boolean {
		if (event.type != KeyEventType.KeyDown) return false
		if (visibilityState.visible || event.key !in NavigationKeys) return false

		visibilityState.show()
		return true
	}

	var showPlaybackInfo by remember { mutableStateOf(false) }

	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value

	Box(modifier = modifier) {
		PlayerOverlayLayout(
			visibilityState = visibilityState,
			// While the card is up, moving off the top of the controls reaches it instead of
			// closing them.
			hideOnFocusExitUp = nextUp.item == null,
			header = {
				Column {
					VideoPlayerHeader(
						item = item,
					)
				}
			},
			controls = {
				VideoPlayerControls(
					playbackManager = playbackManager,
					onPlaybackInfoClick = { showPlaybackInfo = !showPlaybackInfo },
					nextUpFocusRequester = nextUpFocusRequester.takeIf { nextUp.item != null },
				)
			},
		)

		// Kept out of the controls so it can be shown on its own, and parked just above where
		// they end rather than aligned to the screen, so it clears them when they are open and
		// does not move when they come and go.
		nextUp.item?.let { nextItem ->
			PlayerNextUpCard(
				item = nextItem,
				showThumbnail = nextUp.showThumbnail,
				focusRequester = nextUpFocusRequester,
				onPlay = {
					nextUp.dismiss()
					coroutineScope.launch { playbackManager.queue.next() }
				},
				modifier = Modifier
					.align(Alignment.BottomEnd)
					.overscan()
					.padding(bottom = PlayerControlsHeight + 16.dp)
					.widthIn(max = 460.dp)
					.onPreviewKeyEvent { showControlsOnNavigation(it) },
			)
		}

		// Playback info overlay - positioned below header area, always visible when enabled
		if (showPlaybackInfo) {
			PlaybackInfoOverlay(
				playbackManager = playbackManager,
				modifier = Modifier
					.align(Alignment.TopStart)
					.padding(start = 48.dp, top = 100.dp)
			)
		}

		MediaToasts(mediaToastRegistry)
	}
}

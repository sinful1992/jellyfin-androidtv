package org.jellyfin.androidtv.ui.player.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.ui.player.base.PlayerOverlayLayout
import org.jellyfin.androidtv.ui.player.base.rememberPlayerOverlayVisibility
import org.jellyfin.androidtv.ui.player.base.toast.MediaToastRegistry
import org.jellyfin.androidtv.ui.player.base.toast.MediaToasts
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.baseItemFlow
import org.koin.compose.koinInject

@Composable
fun VideoPlayerOverlay(
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	mediaToastRegistry: MediaToastRegistry,
) {
	val visibilityState = rememberPlayerOverlayVisibility()
	val nextUp = rememberPlayerNextUpState(playbackManager)
	val coroutineScope = rememberCoroutineScope()

	// The card announces itself without dragging the controls along, so the picture stays clear.
	// While it is up Back dismisses it rather than leaving playback.
	BackHandler(enabled = nextUp.item != null, onBack = nextUp.dismiss)

	var showPlaybackInfo by remember { mutableStateOf(false) }

	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.run { baseItemFlow.collectAsState(baseItem) }?.value

	Box(modifier = modifier) {
		PlayerOverlayLayout(
			visibilityState = visibilityState,
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
				)
			},
		)

		// Kept out of the controls so it can be shown on its own, and in the top corner so it
		// never lands on them when they are open.
		nextUp.item?.let { nextItem ->
			PlayerNextUpCard(
				item = nextItem,
				showThumbnail = nextUp.showThumbnail,
				onPlay = {
					nextUp.dismiss()
					coroutineScope.launch { playbackManager.queue.next() }
				},
				modifier = Modifier
					.align(Alignment.TopEnd)
					.overscan()
					.widthIn(max = 460.dp),
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

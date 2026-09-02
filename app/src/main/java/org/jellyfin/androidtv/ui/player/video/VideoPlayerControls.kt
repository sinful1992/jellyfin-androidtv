package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.SeekbarDefaults
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.popover.Popover
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.ui.player.base.PlayerControlsHeight
import org.jellyfin.androidtv.ui.player.base.PlayerSeekbar
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.queue
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Composable
fun VideoPlayerControls(
	playbackManager: PlaybackManager = koinInject(),
	onPlaybackInfoClick: () -> Unit = {},
	/**
	 * What moving up off the seek bar reaches, when there is something above the controls to
	 * reach. Null closes the controls instead, which is what going up out of them normally does.
	 */
	nextUpFocusRequester: FocusRequester? = null,
) {
	val playState by playbackManager.state.playState.collectAsState()

	var seekPosition by remember { mutableStateOf(Duration.ZERO) }

	val seekbarFocusRequester = remember { FocusRequester() }
	val playPauseFocusRequester = remember { FocusRequester() }

	BoxWithConstraints {
		// The seek bar leads: it is what the controls are opened for, and the rest is arranged
		// around it. The height is fixed so anything else drawn over the video knows where the
		// controls end without having to wait for them to be measured.
		Column(
			verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
			modifier = Modifier
				// A floor rather than a fixed height: at the largest display size the text grows
				// and the row has to grow with it, and a card overlapping the position by a few
				// points is a better failure than controls with their bottom cut off.
				.heightIn(min = PlayerControlsHeight)
				.onVisibilityChanged { visible ->
					if (!visible) return@onVisibilityChanged

					// The controls are opened to move through the entry far more often than to
					// change anything about it, so the bar is what they open on. The position is
					// read rather than observed: all this needs is whether the bar can take the
					// focus right now. Live television has no duration, which leaves the bar
					// disabled and nothing to hand the focus to but the buttons.
					if (playbackManager.state.positionInfo.duration > Duration.ZERO) seekbarFocusRequester.requestFocus()
					else playPauseFocusRequester.requestFocus()
				},
		) {
			PositionText(playbackManager)

			PlayerSeekbar(
				playbackManager = playbackManager,
				onSeek = { position -> seekPosition = position },
				// The shared colours are made for a solid background. Over a picture that can be
				// any colour at all the rail has to carry its own contrast.
				colors = SeekbarDefaults.colors(
					backgroundColor = Color.White.copy(alpha = 0.22f),
					bufferColor = Color.White.copy(alpha = 0.45f),
					knobColor = Color.White,
				),
				modifier = Modifier
					.fillMaxWidth()
					.height(6.dp)
					.focusRequester(seekbarFocusRequester)
					.focusProperties {
						if (nextUpFocusRequester != null) up = nextUpFocusRequester
					}
			)

			Row(
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.fillMaxWidth()
					.focusRestorer()
					.focusGroup()
			) {
				PlayPauseButton(
					playbackManager = playbackManager,
					playState = playState,
					modifier = Modifier.focusRequester(playPauseFocusRequester),
				)
				RewindButton(playbackManager)
				FastForwardButton(playbackManager)

				Spacer(Modifier.weight(1f))

				AudioTrackButton(playbackManager)
				SubtitleTrackButton(playbackManager)
				QualityButton(playbackManager)

				PlaybackInfoButton(onClick = onPlaybackInfoClick)

				MoreOptionsButton {
					PreviousEntryButton(playbackManager)
					NextEntryButton(playbackManager)
				}
			}
		}

		PlayerTrickplayPreviewOverlay(playbackManager, seekPosition)
	}
}

@Composable
private fun PlayPauseButton(
	playbackManager: PlaybackManager,
	playState: PlayState,
	modifier: Modifier = Modifier,
) {
	IconButton(
		onClick = {
			when (playState) {
				PlayState.STOPPED,
				PlayState.ERROR -> playbackManager.state.play()

				PlayState.PLAYING -> playbackManager.state.pause()
				PlayState.PAUSED -> playbackManager.state.unpause()
			}
		},
		modifier = modifier,
	) {
		AnimatedContent(playState) { playState ->
			when (playState) {
				PlayState.PLAYING -> {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_pause),
						contentDescription = stringResource(R.string.lbl_pause),
					)
				}

				PlayState.STOPPED,
				PlayState.PAUSED,
				PlayState.ERROR -> {
					Icon(
						imageVector = ImageVector.vectorResource(R.drawable.ic_play),
						contentDescription = stringResource(R.string.lbl_play),
					)
				}
			}
		}
	}
}

@Composable
private fun RewindButton(
	playbackManager: PlaybackManager,
) = IconButton(
	onClick = { playbackManager.state.rewind() },
) {
	Icon(
		imageVector = ImageVector.vectorResource(R.drawable.ic_rewind),
		contentDescription = stringResource(R.string.rewind),
	)
}

@Composable
private fun FastForwardButton(
	playbackManager: PlaybackManager,
) = IconButton(
	onClick = { playbackManager.state.fastForward() },
) {
	Icon(
		imageVector = ImageVector.vectorResource(R.drawable.ic_fast_forward),
		contentDescription = stringResource(R.string.fast_forward),
	)
}

@Composable
private fun PreviousEntryButton(
	playbackManager: PlaybackManager,
) {
	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val coroutineScope = rememberCoroutineScope()

	IconButton(
		enabled = entryIndex > 0,
		onClick = {
			coroutineScope.launch {
				playbackManager.queue.previous()
			}
		},
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_previous),
			contentDescription = stringResource(R.string.lbl_prev_item),
		)
	}
}

@Composable
private fun NextEntryButton(
	playbackManager: PlaybackManager,
) {
	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val coroutineScope = rememberCoroutineScope()

	IconButton(
		enabled = entryIndex < playbackManager.queue.estimatedSize - 1,
		onClick = {
			coroutineScope.launch {
				playbackManager.queue.next()
			}
		},
	) {
		Icon(
			imageVector = ImageVector.vectorResource(R.drawable.ic_next),
			contentDescription = stringResource(R.string.lbl_next_item),
		)
	}
}

private fun Duration.formatted(includeHours: Boolean): String {
	val totalSeconds = toInt(DurationUnit.SECONDS)
	val hours = totalSeconds / 3600
	val minutes = (totalSeconds % 3600) / 60
	val seconds = totalSeconds % 60

	return if (includeHours) "%d:%02d:%02d".format(hours, minutes, seconds)
	else "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun PositionText(
	playbackManager: PlaybackManager,
) {
	val positionInfo by rememberPlayerPositionInfo(playbackManager, precision = 1.seconds)
	if (positionInfo.duration == Duration.ZERO) return

	val includeHours = positionInfo.duration.inWholeMinutes >= 60
	val style = LocalTextStyle.current.copy(
		fontSize = 15.sp,
		fontWeight = FontWeight.W500,
		letterSpacing = 0.4.sp,
		// The seconds turn over once a second, and proportional digits shuffle the whole line
		// every time they do.
		fontFeatureSettings = "tnum",
	)

	Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(
			text = positionInfo.active.formatted(includeHours),
			style = style.copy(color = Color.White),
		)

		// The runtime is context for the position rather than something to read off it, so it is
		// present without competing.
		Text(
			text = "/ ${positionInfo.duration.formatted(includeHours)}",
			style = style.copy(color = Color.White.copy(alpha = 0.6f)),
		)
	}
}

@Composable
private fun MoreOptionsButton(
	content: @Composable () -> Unit,
) = Box {
	var expanded by remember { mutableStateOf(false) }
	PlayerControlButton(
		icon = ImageVector.vectorResource(R.drawable.ic_more),
		label = stringResource(R.string.lbl_more),
		onClick = { expanded = true },
	)

	Popover(
		expanded = expanded,
		onDismissRequest = { expanded = false },
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, (-5).dp)
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(12.dp),
			modifier = Modifier
				.padding(4.dp)
		) {
			content()
		}
	}
}

@Composable
fun PlaybackInfoButton(
	onClick: () -> Unit,
) = PlayerControlButton(
	icon = ImageVector.vectorResource(R.drawable.ic_info),
	label = stringResource(R.string.playback_info),
	onClick = onClick,
)

package org.jellyfin.androidtv.ui.player.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jellyfin.androidtv.ui.base.Seekbar
import org.jellyfin.androidtv.ui.base.SeekbarColors
import org.jellyfin.androidtv.ui.base.SeekbarDefaults
import org.jellyfin.androidtv.ui.composable.rememberPlayerProgress
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.times

@Composable
fun PlayerSeekbar(
	modifier: Modifier = Modifier,
	colors: SeekbarColors = SeekbarDefaults.colors(),
	playbackManager: PlaybackManager = koinInject<PlaybackManager>(),
	onSeek: ((position: Duration) -> Unit)? = null,
) {
	val playState by playbackManager.state.playState.collectAsState()
	// Bumped on every seek so the position below is re-read and the progress animation restarts
	// from where playback actually is, instead of briefly running on from the old position.
	var seekGeneration by remember { mutableIntStateOf(0) }
	val positionInfo = playbackManager.state.positionInfo
	val progress by rememberPlayerProgress(
		playing = playState == PlayState.PLAYING,
		active = positionInfo.active,
		duration = positionInfo.duration,
		resyncKey = seekGeneration,
	)
	val seekForwardAmount = remember { playbackManager.options.defaultFastForwardAmount() }
	val seekRewindAmount = remember { playbackManager.options.defaultRewindAmount() }

	Seekbar(
		progress = progress.toDouble() * positionInfo.duration,
		buffer = positionInfo.buffer,
		duration = positionInfo.duration,
		seekForwardAmount = seekForwardAmount,
		seekRewindAmount = seekRewindAmount,
		onScrubbing = { scrubbing -> playbackManager.state.setScrubbing(scrubbing) },
		onSeek = { position ->
			onSeek?.invoke(position)
			playbackManager.state.seek(position)
			seekGeneration++
		},
		modifier = modifier,
		colors = colors,
		enabled = positionInfo.duration > Duration.ZERO,
	)
}

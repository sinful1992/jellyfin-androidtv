package org.jellyfin.androidtv.ui.player.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack
import org.jellyfin.playback.core.mediastream.MediaStreamTrack
import org.jellyfin.playback.core.mediastream.mediaStreamFlow
import org.jellyfin.playback.core.mediastream.selectAudioStream
import org.jellyfin.playback.core.mediastream.selectSubtitleStream
import org.jellyfin.playback.core.mediastream.selectedAudioStreamIndexFlow
import org.jellyfin.playback.core.mediastream.selectedSubtitleStreamIndexFlow

/**
 * Spike-grade track pickers for the rewrite player. These exist to drive the stream re-resolve by
 * hand while the mechanism underneath is being proven, and are deliberately plain: no grouping by
 * language and no external subtitle handling.
 */

private fun MediaStreamTrack.label(fallback: String) =
	title ?: language ?: "$fallback $index"

@Composable
fun AudioTrackButton(
	playbackManager: PlaybackManager,
) {
	val entry by rememberQueueEntry(playbackManager)
	val currentEntry = entry ?: return
	val mediaStream by currentEntry.mediaStreamFlow.collectAsState(null)
	val selectedIndex by currentEntry.selectedAudioStreamIndexFlow.collectAsState(null)

	val tracks = mediaStream?.tracks.orEmpty().filterIsInstance<MediaStreamAudioTrack>()
	// Nothing to choose between.
	if (tracks.size < 2) return

	// Until the user picks a track there is no explicit selection, so fall back to whichever track
	// the server marked default.
	val activeIndex = selectedIndex ?: tracks.firstOrNull { it.isDefault }?.index

	PlayerOptionPicker(
		icon = R.drawable.ic_select_audio,
		contentDescription = stringResource(R.string.lbl_audio_track),
		options = tracks.map { it.index to it.label("Audio") },
		activeOption = activeIndex,
		onSelect = { playbackManager.selectAudioStream(it) },
	)
}

@Composable
fun SubtitleTrackButton(
	playbackManager: PlaybackManager,
) {
	val entry by rememberQueueEntry(playbackManager)
	val currentEntry = entry ?: return
	val mediaStream by currentEntry.mediaStreamFlow.collectAsState(null)
	val selectedIndex by currentEntry.selectedSubtitleStreamIndexFlow.collectAsState(null)

	val tracks = mediaStream?.tracks.orEmpty().filterIsInstance<MediaStreamSubtitleTrack>()
	if (tracks.isEmpty()) return

	val activeIndex = selectedIndex ?: tracks.firstOrNull { it.isDefault }?.index
		?: MediaStreamSubtitleTrack.INDEX_NONE

	// "None" is always offered, so subtitles can be turned back off.
	val options = buildList {
		add(MediaStreamSubtitleTrack.INDEX_NONE to stringResource(R.string.lbl_none))
		addAll(tracks.map { it.index to it.label("Subtitle") })
	}

	PlayerOptionPicker(
		icon = R.drawable.ic_select_subtitle,
		contentDescription = stringResource(R.string.lbl_subtitle_track),
		options = options,
		activeOption = activeIndex,
		onSelect = { playbackManager.selectSubtitleStream(it) },
	)
}

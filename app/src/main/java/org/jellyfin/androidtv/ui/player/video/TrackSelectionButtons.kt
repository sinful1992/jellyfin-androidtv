package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.popover.Popover
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
 * language, no external subtitle handling, no remembering a choice across entries.
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

	TrackPicker(
		icon = R.drawable.ic_select_audio,
		contentDescription = stringResource(R.string.lbl_audio_track),
		tracks = tracks.map { it.index to it.label("Audio") },
		activeIndex = activeIndex,
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

	TrackPicker(
		icon = R.drawable.ic_select_subtitle,
		contentDescription = stringResource(R.string.lbl_subtitle_track),
		tracks = options,
		activeIndex = activeIndex,
		onSelect = { playbackManager.selectSubtitleStream(it) },
	)
}

@Composable
private fun TrackPicker(
	icon: Int,
	contentDescription: String,
	tracks: List<Pair<Int, String>>,
	activeIndex: Int?,
	onSelect: suspend (index: Int) -> Unit,
) = Box {
	var expanded by remember { mutableStateOf(false) }
	val coroutineScope = rememberCoroutineScope()

	IconButton(onClick = { expanded = true }) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = contentDescription,
		)
	}

	Popover(
		expanded = expanded,
		onDismissRequest = { expanded = false },
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, (-5).dp),
	) {
		Column(
			modifier = Modifier
				.padding(4.dp)
				.widthIn(min = 220.dp, max = 400.dp)
				.verticalScroll(rememberScrollState())
		) {
			for ((index, label) in tracks) {
				ListButton(
					onClick = {
						expanded = false
						coroutineScope.launch { onSelect(index) }
					},
					headingContent = { Text(label) },
					trailingContent = { RadioButton(checked = index == activeIndex) },
				)
			}
		}
	}
}

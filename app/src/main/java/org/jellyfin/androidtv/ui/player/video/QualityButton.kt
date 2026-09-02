package org.jellyfin.androidtv.ui.player.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.getQualityProfiles
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.mediastream.reloadStream
import org.koin.compose.koinInject

/**
 * Pick the maximum bitrate the server may stream at, forcing a transcode when the source exceeds
 * it. This writes the same preference the settings screen does, so the choice outlives the current
 * playback, matching the legacy player.
 */
@Composable
fun QualityButton(
	playbackManager: PlaybackManager,
	userPreferences: UserPreferences = koinInject(),
) {
	val context = LocalContext.current
	val options = remember(context) { getQualityProfiles(context).toList() }
	var quality by remember { mutableStateOf(userPreferences[UserPreferences.maxBitrate]) }

	PlayerOptionPicker(
		icon = R.drawable.ic_select_quality,
		label = stringResource(R.string.lbl_quality),
		options = options,
		activeOption = quality,
		onSelect = { value ->
			if (value != quality) {
				quality = value
				userPreferences[UserPreferences.maxBitrate] = value
				// The device profile is rebuilt for every resolve, so re-resolving is enough to
				// make the server honour the new limit.
				playbackManager.reloadStream()
			}
		},
	)
}

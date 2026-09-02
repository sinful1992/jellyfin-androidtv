package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.NEXTUP_TIMER_DISABLED
import org.jellyfin.androidtv.preference.constant.NextUpBehavior
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.rememberPlayerPositionInfo
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.sdk.getDisplayName
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long before the end of an entry the card is shown when the user turned the legacy Next Up
 * timer off. The rewrite player advances on its own, so there is no countdown to disable - the
 * preference only decides how much warning there is.
 */
private val DefaultLeadTime = 10.seconds

@Immutable
data class PlayerNextUpState(
	/**
	 * The entry that plays next, or null when the card should not be shown right now.
	 */
	val item: BaseItemDto? = null,
	val showThumbnail: Boolean = false,
	val dismiss: () -> Unit = {},
)

/**
 * Track what plays after the current entry and when to announce it.
 *
 * The queue advances on its own when an entry ends, so this only decides when to tell the user what
 * is coming and offer to skip ahead to it. Dismissing applies to the current entry only.
 */
@Composable
fun rememberPlayerNextUpState(
	playbackManager: PlaybackManager,
	userPreferences: UserPreferences = koinInject(),
): PlayerNextUpState {
	val behavior = remember(userPreferences) { userPreferences[UserPreferences.nextUpBehavior] }
	val leadTime = remember(userPreferences) {
		userPreferences[UserPreferences.nextUpTimeout]
			.takeIf { it != NEXTUP_TIMER_DISABLED }
			?.milliseconds
			?: DefaultLeadTime
	}

	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val positionInfo by rememberPlayerPositionInfo(playbackManager)

	var nextItem by remember { mutableStateOf<BaseItemDto?>(null) }
	var dismissedIndex by remember { mutableStateOf<Int?>(null) }

	LaunchedEffect(entryIndex) {
		nextItem = playbackManager.queue.peekNext()?.baseItem
	}

	if (behavior == NextUpBehavior.DISABLED) return PlayerNextUpState()

	val remaining = positionInfo.duration - positionInfo.active
	val visible = dismissedIndex != entryIndex &&
		positionInfo.duration > Duration.ZERO &&
		remaining > Duration.ZERO &&
		remaining <= leadTime

	return PlayerNextUpState(
		item = nextItem.takeIf { visible },
		showThumbnail = behavior == NextUpBehavior.EXTENDED,
		dismiss = { dismissedIndex = entryIndex },
	)
}

/**
 * Announce the entry that plays next, with the option to skip to it instead of sitting through the
 * rest of the current one.
 */
@Composable
fun PlayerNextUpCard(
	item: BaseItemDto,
	showThumbnail: Boolean,
	onPlay: () -> Unit,
	modifier: Modifier = Modifier,
	api: ApiClient = koinInject(),
) {
	val context = LocalContext.current
	val thumbnail = item.itemImages[ImageType.PRIMARY].takeIf { showThumbnail }

	Row(
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = modifier.focusGroup()
	) {
		if (thumbnail != null) {
			AsyncImage(
				url = thumbnail.getUrl(api),
				blurHash = thumbnail.blurHash,
				aspectRatio = thumbnail.aspectRatio ?: 1f,
				modifier = Modifier
					.height(72.dp)
					.aspectRatio(thumbnail.aspectRatio ?: 1f)
					.clip(JellyfinTheme.shapes.extraSmall)
			)
		}

		Column {
			Text(
				text = stringResource(R.string.lbl_next_up),
				style = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp),
			)
			Text(
				text = item.getDisplayName(context),
				style = LocalTextStyle.current.copy(color = Color.White, fontSize = 18.sp),
				overflow = TextOverflow.Ellipsis,
				maxLines = 1,
			)
		}

		Button(
			onClick = onPlay,
			// The player theme makes buttons transparent so the icon row disappears into the video;
			// this one has to read as an actionable button on its own.
			colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.2f)),
		) {
			Text(stringResource(R.string.watch_now))
		}
	}
}

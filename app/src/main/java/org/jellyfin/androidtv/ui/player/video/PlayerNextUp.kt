package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
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
import kotlin.time.Duration.Companion.seconds

/**
 * How long before the end of an entry the card is shown.
 *
 * This is deliberately not [UserPreferences.nextUpTimeout]: that is how long the legacy player
 * holds a stopped countdown for, while this is how much warning to give during playback that
 * continues either way. Seven seconds of a countdown is patient; seven seconds of a notice is not.
 */
private val LeadTime = 30.seconds

/** How heavy the ring around the focused card is. The same weight the home screen cards use. */
private val FocusRingWidth = 3.dp

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

	val entryIndex by playbackManager.queue.entryIndex.collectAsState()
	val positionInfo by rememberPlayerPositionInfo(playbackManager)

	var nextItem by remember { mutableStateOf<BaseItemDto?>(null) }
	var dismissedIndex by remember { mutableStateOf<Int?>(null) }

	LaunchedEffect(entryIndex) {
		// Same flags the queue uses when an entry ends, so this names the entry that will play.
		nextItem = playbackManager.queue.peekNext(usePlaybackOrder = true, useRepeatMode = true)?.baseItem
	}

	if (behavior == NextUpBehavior.DISABLED) return PlayerNextUpState()

	val remaining = positionInfo.duration - positionInfo.active
	val visible = dismissedIndex != entryIndex &&
		positionInfo.duration > Duration.ZERO &&
		remaining > Duration.ZERO &&
		remaining <= LeadTime

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
	focusRequester: FocusRequester = remember { FocusRequester() },
	api: ApiClient = koinInject(),
) {
	val context = LocalContext.current
	val thumbnail = item.itemImages[ImageType.PRIMARY].takeIf { showThumbnail }

	// The focus lands on the button inside the card, so the card itself is never the focused node
	// and has to watch for the focus being somewhere within it instead.
	var focused by remember { mutableStateOf(false) }

	// A pill changing colour is a small thing to spot from across a room, and the card is what the
	// remote is actually pointed at: while it holds the focus, up and down are its keys and not the
	// player's. So the whole card is ringed, in the same colour and weight that marks the focused
	// card on the home screen.
	val ringAlpha by animateFloatAsState(if (focused) 1f else 0f, label = "next up focus ring")

	Row(
		horizontalArrangement = Arrangement.spacedBy(16.dp),
		verticalAlignment = Alignment.CenterVertically,
		// The card sits over moving video, so it needs its own ground to be readable at a glance
		// rather than relying on whatever frame happens to be behind it.
		modifier = modifier
			.onFocusChanged { focused = it.hasFocus }
			.focusGroup()
			.background(JellyfinTheme.colorScheme.surface, JellyfinTheme.shapes.medium)
			.border(
				width = FocusRingWidth,
				color = JellyfinTheme.colorScheme.buttonFocused.let { it.copy(alpha = it.alpha * ringAlpha) },
				shape = JellyfinTheme.shapes.medium,
			)
			.padding(horizontal = 20.dp, vertical = 16.dp)
	) {
		if (thumbnail != null) {
			AsyncImage(
				url = thumbnail.getUrl(api),
				blurHash = thumbnail.blurHash,
				aspectRatio = thumbnail.aspectRatio ?: 1f,
				modifier = Modifier
					.height(96.dp)
					.aspectRatio(thumbnail.aspectRatio ?: 1f)
					.clip(JellyfinTheme.shapes.extraSmall)
			)
		}

		Column(
			verticalArrangement = Arrangement.spacedBy(4.dp),
			modifier = Modifier.weight(1f),
		) {
			Text(
				text = stringResource(R.string.lbl_next_up),
				style = LocalTextStyle.current.merge(JellyfinTheme.typography.label).copy(
					color = Color.White.copy(alpha = 0.75f),
				),
			)
			Text(
				text = item.getDisplayName(context),
				style = LocalTextStyle.current.merge(JellyfinTheme.typography.title).copy(
					color = Color.White,
				),
				overflow = TextOverflow.Ellipsis,
				maxLines = 1,
			)
		}

		Button(
			onClick = onPlay,
			// The player theme makes buttons transparent so the icon row disappears into the video;
			// this one has to read as an actionable button on its own.
			colors = ButtonDefaults.colors(containerColor = Color.White.copy(alpha = 0.2f)),
			modifier = Modifier.focusRequester(focusRequester),
		) {
			Text(stringResource(R.string.watch_now))
		}
	}
}

package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.ui.base.LocalTextStyle
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.player.base.PlayerHeader
import org.jellyfin.androidtv.util.sdk.getSeasonEpisodeName
import org.jellyfin.sdk.model.api.BaseItemDto

@Composable
@Stable
fun VideoPlayerHeader(
	item: BaseItemDto?,
) {
	val context = LocalContext.current

	PlayerHeader {
		if (item != null) {
			// What is playing is the title; where it sits in a series is only how to find it
			// again. Two lines of near enough the same size say they matter equally, so the
			// series goes above and small, and carries the episode number it is useless without.
			val eyebrow = listOfNotNull(
				item.seriesName?.takeIf(String::isNotEmpty),
				item.getSeasonEpisodeName(context).takeIf(String::isNotEmpty),
			).joinToString(" · ")

			if (eyebrow.isNotEmpty()) Text(
				text = eyebrow.uppercase(),
				overflow = TextOverflow.Ellipsis,
				maxLines = 1,
				style = LocalTextStyle.current.copy(
					color = Color.White.copy(alpha = 0.65f),
					fontSize = 13.sp,
					fontWeight = FontWeight.W600,
					letterSpacing = 1.2.sp,
				),
				modifier = Modifier.padding(bottom = 2.dp),
			)

			Text(
				text = item.name.orEmpty(),
				overflow = TextOverflow.Ellipsis,
				maxLines = 1,
				style = LocalTextStyle.current.copy(
					color = Color.White,
					fontSize = 28.sp,
					fontWeight = FontWeight.W700,
				)
			)
		}
	}
}

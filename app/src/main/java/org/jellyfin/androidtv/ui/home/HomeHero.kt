package org.jellyfin.androidtv.ui.home

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRow
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.koin.compose.koinInject

/** What the select key does while the hero holds the focus. */
enum class HomeHeroAction {
	Play,
	Details,
}

/**
 * How tall the hero stands.
 *
 * Enough for the lettering, a line of facts, two lines of description and the buttons, and no more.
 * What is left of the screen has to still show a row of cards below it, or nothing says there is
 * anything down there to go to.
 */
val HomeHeroHeight = 268.dp

/** How far the text is allowed to run. A line set across a whole television is not read, it is scanned. */
private val HeroMeasure = 640.dp

private val HeroLogoHeight = 76.dp

/**
 * One title, put forward.
 *
 * The picture behind this is the app's own backdrop, which the rows already change as the selection
 * moves, so the hero is only what is said over it. That is also why nothing here draws a background
 * of its own: the artwork runs the full width and height of the screen, behind the toolbar, and
 * anything boxed off on top of it would cut it up.
 */
@Composable
fun HomeHero(
	item: BaseItemDto,
	action: HomeHeroAction,
	focused: Boolean,
	modifier: Modifier = Modifier,
	api: ApiClient = koinInject(),
) {
	val density = LocalDensity.current
	val logo = remember(item) { item.itemImages[ImageType.LOGO] ?: item.parentImages[ImageType.LOGO] }

	Column(
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm, Alignment.Bottom),
		modifier = modifier
			.fillMaxWidth()
			.height(HomeHeroHeight)
			// The row is handed the whole width, unlike the card rows, which leanback insets for
			// itself. Matching that inset by hand is what keeps the hero's text on the same left
			// edge as every row header below it.
			.padding(horizontal = dimensionResource(androidx.leanback.R.dimen.lb_browse_padding_start))
			.padding(bottom = Tokens.Space.spaceLg),
	) {
		// Why this title and not another. Without it the hero is a picture of something arbitrary;
		// with it, it is an answer to what has arrived lately and not been watched.
		Text(
			text = stringResource(R.string.home_hero_label).uppercase(),
			style = JellyfinTheme.typography.default.copy(
				color = Tokens.Color.colorWhite.copy(alpha = 0.6f),
				fontSize = 12.sp,
				fontWeight = FontWeight.W500,
				letterSpacing = 1.6.sp,
			),
		)

		// The title in its own lettering, where the library has it. It is the one piece of type on
		// the screen that is drawn rather than set, and it is what makes the top of the home screen
		// belong to this title instead of to the app.
		if (logo != null) {
			AsyncImage(
				url = logo.getUrl(api, maxHeight = with(density) { HeroLogoHeight.roundToPx() }),
				scaleType = ImageView.ScaleType.FIT_START,
				modifier = Modifier
					.height(HeroLogoHeight)
					.widthIn(max = HeroMeasure)
					.fillMaxWidth(),
			)
		} else {
			Text(
				text = item.name.orEmpty(),
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				style = JellyfinTheme.typography.default.copy(
					color = Tokens.Color.colorWhite,
					fontSize = 34.sp,
					fontWeight = FontWeight.Bold,
				),
				modifier = Modifier.widthIn(max = HeroMeasure),
			)
		}

		// The same facts, in the same chips, as the detail page the second button leads to.
		//
		// Its items fill the height they are given, which on a detail page is a line and here is
		// the whole hero — one parental rating chip stretched down the screen with everything else
		// pushed out of the way. Holding the row to its intrinsic height gives it the line back.
		Row(modifier = Modifier.height(IntrinsicSize.Min)) {
			BaseItemInfoRow(
				item = item,
				mediaSource = null,
				// A series has no runtime of its own and reports zero rather than nothing, which
				// the row is happy to print as 0:00.
				includeRuntime = (item.runTimeTicks ?: 0L) > 0L,
			)
		}

		item.overview?.let { overview ->
			Text(
				text = overview,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				style = JellyfinTheme.typography.default.copy(
					color = Tokens.Color.colorGrey100,
					fontSize = 15.sp,
					lineHeight = 21.sp,
				),
				modifier = Modifier.widthIn(max = HeroMeasure),
			)
		}

		Row(
			horizontalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm),
			modifier = Modifier.padding(top = Tokens.Space.spaceXs),
		) {
			HeroButton(
				icon = ImageVector.vectorResource(R.drawable.ic_play),
				label = stringResource(R.string.lbl_play),
				selected = focused && action == HomeHeroAction.Play,
			)

			HeroButton(
				icon = ImageVector.vectorResource(R.drawable.ic_info),
				label = stringResource(R.string.home_hero_details),
				selected = focused && action == HomeHeroAction.Details,
			)
		}
	}
}

/**
 * A button that is told whether it is the selected one rather than working it out.
 *
 * The hero takes the focus as a single view and moves between its own buttons on the left and right
 * keys, because leanback and Compose do not agree about who owns the focus inside a row. So these
 * cannot read a focus state that never reaches them, and are drawn from what the row says instead.
 * The shape, padding and colours are the shared button's, so they still read as the same control.
 */
@Composable
private fun HeroButton(
	icon: ImageVector,
	label: String,
	selected: Boolean,
) {
	val colors = ButtonDefaults.colors()
	val containerColor = if (selected) colors.focusedContainerColor else colors.containerColor
	val contentColor = if (selected) colors.focusedContentColor else colors.contentColor

	Row(
		horizontalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm),
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.background(containerColor, ButtonDefaults.Shape)
			.padding(PaddingValues(horizontal = 20.dp, vertical = 10.dp)),
	) {
		Icon(
			imageVector = icon,
			contentDescription = null,
			tint = contentColor,
			modifier = Modifier.size(18.dp),
		)

		Text(
			text = label,
			style = JellyfinTheme.typography.default.copy(
				color = contentColor,
				fontSize = 15.sp,
				fontWeight = FontWeight.W500,
			),
		)
	}
}

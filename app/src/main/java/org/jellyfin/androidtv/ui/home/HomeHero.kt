package org.jellyfin.androidtv.ui.home

import android.widget.ImageView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Which of the hero's controls the sideways and select keys belong to.
 *
 * Two stops stacked vertically: the strip that picks which title is on show, and the buttons that
 * do something with the one that is. Up and down move between them, left and right mean whichever
 * of the two is being used.
 */
enum class HomeHeroFocus {
	Dots,
	Play,
	Details,
}

/**
 * How tall the hero stands.
 *
 * Enough for the strip, the lettering, a line of facts, two lines of description and the buttons,
 * and no more. What is left of the screen has to still show a row of cards below it, or nothing
 * says there is anything down there to go to.
 */
val HomeHeroHeight = 288.dp

/**
 * How far the text is allowed to run. A line set across a whole television is not read, it is
 * scanned.
 *
 * 576dp rather than the 640dp it began at, for two reasons that happen to want the same number. At
 * the description's size this is a little under 80 characters, which is where a line stops being
 * read in one go; and it keeps the words inside the wash that
 * [org.jellyfin.androidtv.ui.background.AppBackground] lays down for them. At 640 they ran to 72%
 * of the width while the wash was gone by 65%, so the end of every description sat on bare artwork.
 * Both were widened to meet: the wash reaches further, and the words stop sooner.
 */
val HomeHeroMeasure = 576.dp

private val HeroLogoHeight = 76.dp

/** How much of the description is offered. The rest is what the second button is for. */
private const val HeroOverviewLines = 2

private val HeroDotSize = 8.dp

/** What the whole strip grows to while it is the stop being used. */
private val HeroDotActiveSize = 11.dp

/** The one on show is drawn long rather than merely brighter, so its place reads from across a room. */
private val HeroDotCurrentWidth = 22.dp

/** And longer again while the strip is the thing the keys are talking to. */
private val HeroDotCurrentActiveWidth = 30.dp

/**
 * How long the words take to change from one title to the next.
 *
 * Half a second, which is what the web client's own hero uses, and this shortlist is the same
 * shortlist. Anything much shorter reads as the text having been swapped rather than as a step
 * having been taken.
 */
private const val HeroChangeMillis = 500

/**
 * How far the words travel while they change.
 *
 * A nudge, not a sweep. It exists to say which way the press went, and the eye reads direction from
 * a movement long before the movement is big enough to be watched; anything further and the text is
 * flying across the screen rather than being replaced on it.
 */
private val HeroChangeTravel = 28.dp

/**
 * One title, put forward, out of a handful the viewer moves between.
 *
 * The picture behind this is the app's own backdrop, and on the home screen the hero is the only
 * thing that sets it — the rows below leave the screen on the flat theme colour. So the hero is
 * only what is said over that picture. Nothing here draws a background of its own either: the
 * artwork runs the full width and height of the screen, behind the toolbar, and anything boxed off
 * on top of it would cut it up.
 *
 * What the hero does instead is ask for that backdrop plain — see
 * [org.jellyfin.androidtv.data.service.BackgroundService.plainBackground]. Everywhere else the
 * backdrop is blurred and taken down by more than half so it cannot compete with a screenful of
 * text, which here left every title looking like the same dark smear with a name on it. Plain, the
 * picture is the picture, and a wash down the side these words are set on carries them instead.
 */
@Composable
fun HomeHero(
	selection: HomeHeroSelection,
	focus: HomeHeroFocus,
	focused: Boolean,
	modifier: Modifier = Modifier,
) {
	val heroTravel = with(LocalDensity.current) { HeroChangeTravel.roundToPx() }

	Column(
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm),
		modifier = modifier
			.fillMaxWidth()
			.height(HomeHeroHeight)
			// The row is handed the whole width, unlike the card rows, which leanback insets for
			// itself. Matching that inset by hand is what keeps the hero's text on the same left
			// edge as every row header below it.
			.padding(horizontal = dimensionResource(androidx.leanback.R.dimen.lb_browse_padding_start))
			.padding(bottom = Tokens.Space.spaceLg),
	) {
		// A shortlist of one is not a shortlist, and a single dot invites a press that does
		// nothing. Left out, which also lets up from the buttons reach the toolbar directly.
		if (selection.count > 1) {
			HeroDots(
				index = selection.index,
				count = selection.count,
				active = focused && focus == HomeHeroFocus.Dots,
			)
		}

		// Only what is drawn from the title changes with it, because the picture behind it is
		// already dissolving and cutting the words against a fading image reads as a fault. The
		// strip above and the buttons below stay put: they are the same controls either way, and
		// fading them would hide which dot has just been moved to.
		//
		// It takes the space the two fixed rows leave rather than its own height, so stepping to a
		// title with a shorter description does not walk the buttons up the screen.
		AnimatedContent(
			targetState = selection,
			// Keyed on the title rather than on the whole selection, so a shortlist redrawn in the
			// background — which builds a new selection for the same title — does not play a change
			// that did not happen.
			contentKey = { it.item.id },
			transitionSpec = {
				// The way the press was going: leftwards for a step back along the strip. The words
				// arriving come from the side being moved towards and the ones leaving go the other
				// way, so the whole block reads as having been pushed along by the key rather than
				// as having been swapped where it stood.
				val travel = if (targetState.forward) heroTravel else -heroTravel

				ContentTransform(
					targetContentEnter = fadeIn(tween(HeroChangeMillis)) +
						slideInHorizontally(tween(HeroChangeMillis)) { travel },
					// The one leaving goes quicker than the one arriving, so the two sets of words
					// are never both legible over each other.
					initialContentExit = fadeOut(tween(HeroChangeMillis / 2)) +
						slideOutHorizontally(tween(HeroChangeMillis / 2)) { -travel },
					// Sized by the weight above rather than by its contents, which is what stops a
					// title with a shorter description dragging the buttons up the screen.
					sizeTransform = SizeTransform(clip = false),
				)
			},
			label = "home hero",
			modifier = Modifier.weight(1f),
		) { shown ->
			HeroDetails(item = shown.item)
		}

		Row(
			horizontalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm),
		) {
			HeroButton(
				icon = ImageVector.vectorResource(R.drawable.ic_play),
				label = stringResource(R.string.lbl_play),
				selected = focused && focus == HomeHeroFocus.Play,
			)

			HeroButton(
				icon = ImageVector.vectorResource(R.drawable.ic_info),
				label = stringResource(R.string.home_hero_details),
				selected = focused && focus == HomeHeroFocus.Details,
			)
		}
	}
}

/**
 * Where the viewer is in the shortlist, and the one thing that says the shortlist can be moved
 * through at all.
 *
 * Lit only while it is the stop being used, so the hero does not look like it is offering two
 * things at once when the focus is on the buttons.
 */
@Composable
private fun HeroDots(
	index: Int,
	count: Int,
	active: Boolean,
) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		// Whether the strip is the stop being used has to be readable from a sofa, and a change of
		// alpha on an 8dp dot is not — from three metres 70% white and 100% white are the same
		// white. So having the focus changes the size of the strip as well as its brightness: the
		// dots grow, and the one on show grows further. Shape carries across a room where tone does
		// not, and the two together are hard to miss without either being loud.
		val height by animateDpAsState(
			if (active) HeroDotActiveSize else HeroDotSize,
			label = "home hero dot height",
		)

		repeat(count) { position ->
			val current = position == index

			val color by animateColorAsState(
				when {
					current && active -> Tokens.Color.colorWhite
					current -> Tokens.Color.colorWhite.copy(alpha = 0.75f)
					active -> Tokens.Color.colorWhite.copy(alpha = 0.5f)
					else -> Tokens.Color.colorWhite.copy(alpha = 0.3f)
				},
				label = "home hero dot",
			)

			// Animated so a step reads as a move along the strip rather than as two separate dots
			// changing at once, which is what says the press did something.
			val width by animateDpAsState(
				when {
					current && active -> HeroDotCurrentActiveWidth
					current -> HeroDotCurrentWidth
					else -> height
				},
				label = "home hero dot width",
			)

			Box(
				modifier = Modifier
					.height(height)
					.width(width)
					.background(color, CircleShape)
			)
		}
	}
}

/** Everything the hero says about the title it is showing, and the only part that changes with it. */
@Composable
private fun HeroDetails(
	item: BaseItemDto,
	api: ApiClient = koinInject(),
) {
	val density = LocalDensity.current
	val logo = remember(item) { item.itemImages[ImageType.LOGO] ?: item.parentImages[ImageType.LOGO] }

	Column(
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm, Alignment.Bottom),
		modifier = Modifier.fillMaxHeight(),
	) {
		// Why this title and not another. Without it the hero is a picture of something arbitrary;
		// with it, it is an answer to what has arrived lately and not been watched.
		//
		// Set as a phrase and not as a label. It was small capitals with the letters driven apart,
		// which is the house style of no house in particular — and it sat directly above the one
		// piece of lettering on this screen that belongs to the title rather than to the app. Two
		// things competing to be the first read. Sentence case, letters left where they fall, and
		// it goes back to being a quiet line that answers a question.
		Text(
			text = stringResource(R.string.home_hero_label),
			color = Tokens.Color.colorWhite.copy(alpha = 0.75f),
			style = JellyfinTheme.typography.label,
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
					.widthIn(max = HomeHeroMeasure)
					.fillMaxWidth(),
			)
		} else {
			Text(
				text = item.name.orEmpty(),
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
				color = Tokens.Color.colorWhite,
				style = JellyfinTheme.typography.display,
				modifier = Modifier.widthIn(max = HomeHeroMeasure),
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
			HeroOverview(
				overview = overview,
				modifier = Modifier.widthIn(max = HomeHeroMeasure),
			)
		}
	}
}

/**
 * The opening of the description, cut at a word.
 *
 * Two passes. The first sets the whole thing and is told where the last line ran out; the second
 * draws it again, cut back to the last whole word before that point. Left to itself the ellipsis
 * lands wherever the character happens to fall, and a word broken in half — "a mysterious assi…" —
 * reads as something having gone wrong rather than as a sentence carrying on out of sight.
 *
 * The guard is an identity check rather than a flag: the trimmed string is a new object, so the
 * second layout cannot start a third.
 */
@Composable
private fun HeroOverview(
	overview: String,
	modifier: Modifier = Modifier,
) {
	var shown by remember(overview) { mutableStateOf(overview) }

	Text(
		text = shown,
		maxLines = HeroOverviewLines,
		overflow = TextOverflow.Ellipsis,
		color = Tokens.Color.colorGrey100,
		style = JellyfinTheme.typography.body,
		onTextLayout = { layout ->
			if (shown === overview && layout.hasVisualOverflow) {
				val end = layout.getLineEnd(HeroOverviewLines - 1, visibleEnd = true)
				val lastSpace = overview.lastIndexOf(' ', (end - 1).coerceAtLeast(0))

				if (lastSpace > 0) {
					shown = overview.substring(0, lastSpace).trimEnd(' ', ',', ';', ':', '-', '—') + "…"
				}
			}
		},
		modifier = modifier,
	)
}

/**
 * A button that is told whether it is the selected one rather than working it out.
 *
 * The hero takes the focus as a single view and moves between its own controls on the arrow keys,
 * because leanback and Compose do not agree about who owns the focus inside a row. So these cannot
 * read a focus state that never reaches them, and are drawn from what the row says instead. The
 * shape, padding and colours are the shared button's, so they still read as the same control.
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
			color = contentColor,
			style = JellyfinTheme.typography.body.copy(fontWeight = FontWeight.W500),
		)
	}
}

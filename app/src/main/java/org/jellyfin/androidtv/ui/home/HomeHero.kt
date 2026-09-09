package org.jellyfin.androidtv.ui.home

import android.widget.ImageView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRow
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
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
 * How much of the screen the hero's row takes, open or closed.
 *
 * Fixed, whatever the hero is drawing inside it. Leanback measures the row and lays the rest of the
 * screen out from that, and a row that changes height under the selection is what has gone wrong
 * twice on this branch. Everything that opens and closes below happens inside this box.
 */
val HomeHeroHeight = 288.dp

/** The card itself, and the gap it leaves to the row of cards underneath. */
private val HeroCardGap = Tokens.Space.spaceSm
private val HeroCardHeight = HomeHeroHeight - HeroCardGap

/**
 * What is left of the card once the viewer has moved down to the cards.
 *
 * Leanback holds the selected row a fixed distance down the screen whatever is above it, so moving
 * off the hero does not take it away — it scrolls up and leaves a band of itself on show. The card
 * closes down onto that band and keeps only the picture and the title's lettering, which is the
 * part worth leaving up: what is down here is a row being used, and what is up there is a reminder
 * of what is at the top of the screen.
 *
 * Short enough to sit inside that band, because the card is held to the bottom of the row and it is
 * the top of the row that scrolls away. Measured on the Chromecast rather than reasoned about: with
 * the first row of cards selected, what leanback leaves on screen is 92dp. A card taller than the
 * band is not shortened, it is cut off at the top, and at 144dp that sliced the title's lettering in
 * half.
 */
private val HeroCardCompactHeight = 88.dp

/** The card's own margin, wider across than down: a band this shape has room to spare sideways. */
private val HeroCardPaddingHorizontal = Tokens.Space.spaceXl
private val HeroCardPaddingVertical = Tokens.Space.spaceMd

/**
 * The lettering's height once the card is down to its band.
 *
 * The open card's logo does not fit the band the way it fits the card: at its full height it clears
 * the band's edges by six pixels-worth and reads as wedged into the slot rather than set inside it.
 * Taken as the band less the card's own margin top and bottom, so the lettering is held off the
 * edge by the same measure as everything else on the card rather than by a number picked to look
 * right.
 */
private val HeroLogoCompactHeight = HeroCardCompactHeight - HeroCardPaddingVertical * 2

/**
 * How far the text is allowed to run. A line set across a whole television is not read, it is
 * scanned.
 *
 * At the description's size this is a little under 80 characters, which is where a line stops being
 * read in one go, and it leaves the far side of the card to the picture. Kept in step with
 * [HeroCardScrim], which is what makes those words readable: the wash has to still be there where
 * they end.
 */
val HomeHeroMeasure = 576.dp

/**
 * The cover the words are set on.
 *
 * A card that carries its own lettering keeps it to one side — the title, the facts and the buttons
 * go down the left and the rest of the width is left to the picture — so the darkness goes there
 * too and thins out towards the far side, where the artwork is actually visible.
 *
 * Three stops rather than two. A straight line from full to nothing reaches nothing *at* the stop,
 * so whatever sits near the end of the run gets no cover at all, and the tail of every description
 * would sit on bare artwork. The middle stop holds a little darkness across the rest of the words
 * and lets it go only once they have ended.
 *
 * At the card's width the text runs to a little over 70% of it, so the wash is not gone until 85%.
 */
private val HeroCardScrim = Brush.horizontalGradient(
	0.00f to Color.Black.copy(alpha = 0.70f),
	0.55f to Color.Black.copy(alpha = 0.25f),
	0.85f to Color.Transparent,
)

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
 * How long the card takes to close, and to open again.
 *
 * Roughly the time leanback's own scroll takes, so the card closing and the rows sliding up read as
 * one movement rather than as two things happening near each other.
 */
private const val HeroCloseMillis = 200

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
 * A card, on the same ground as every row below it. The hero used to be the screen: it put its
 * artwork behind the whole app, full width and height and behind the toolbar, and every other row
 * left that screen flat — so moving one place down swapped a picture for black and the app appeared
 * to change what it was made of. The picture lives inside the card now. Nothing the hero does
 * changes the ground, because the ground is not the hero's to change.
 *
 * Its edges line up with the cards in the row beneath rather than with their titles, which is the
 * alignment that reads: a surface, in a column of surfaces. The lettering is inset from the card's
 * own edge, the way the writing on any card is.
 */
@Composable
fun HomeHero(
	selection: HomeHeroSelection,
	focus: HomeHeroFocus,
	focused: Boolean,
	expanded: Boolean,
	modifier: Modifier = Modifier,
) {
	// Animated inside a box of fixed height, so the row leanback measured never changes.
	val height by animateDpAsState(
		targetValue = if (expanded) HeroCardHeight else HeroCardCompactHeight,
		animationSpec = tween(HeroCloseMillis),
		label = "home hero height",
	)

	Box(
		contentAlignment = Alignment.BottomStart,
		modifier = modifier
			.fillMaxWidth()
			.height(HomeHeroHeight)
			// The row is handed the whole width, unlike the card rows, which leanback insets for
			// itself. Matching that inset by hand is what puts the card's edges on the same two
			// lines as the cards below it.
			.padding(horizontal = dimensionResource(androidx.leanback.R.dimen.lb_browse_padding_start))
			.padding(bottom = HeroCardGap),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.height(height)
				.clip(JellyfinTheme.shapes.large)
				// The same surface every other card in the app sits on, for the moment before the
				// picture arrives and for the corners the picture does not reach.
				.background(JellyfinTheme.colorScheme.surface),
		) {
			HeroArtwork(
				item = selection.item,
				modifier = Modifier.matchParentSize(),
			)

			Box(
				modifier = Modifier
					.matchParentSize()
					.background(HeroCardScrim)
			)

			Crossfade(
				targetState = expanded,
				animationSpec = tween(HeroCloseMillis),
				label = "home hero card",
				modifier = Modifier.fillMaxSize(),
			) { open ->
				if (open) {
					HeroCardOpen(
						selection = selection,
						focus = focus,
						focused = focused,
					)
				} else {
					HeroCardClosed(item = selection.item)
				}
			}
		}
	}
}

/**
 * The picture the card is, behind everything the card says.
 *
 * Crossfaded on its own rather than inside the block of text, which slides sideways as it changes:
 * a picture dragged across the card would read as the whole card being swiped away, and the point
 * of the movement is to say a step was taken along a strip, not that the screen has gone somewhere.
 *
 * Asked for at the open card's size whatever size the card currently is. The height is animating
 * while the card closes, and asking the server for a new crop on every frame of that is a request a
 * frame and a picture that never finishes arriving.
 */
@Composable
private fun HeroArtwork(
	item: BaseItemDto,
	modifier: Modifier = Modifier,
	api: ApiClient = koinInject(),
) {
	val density = LocalDensity.current

	BoxWithConstraints(modifier = modifier) {
		val width = constraints.maxWidth
		val height = with(density) { HeroCardHeight.roundToPx() }

		Crossfade(
			targetState = item,
			animationSpec = tween(HeroChangeMillis),
			label = "home hero artwork",
			modifier = Modifier.fillMaxSize(),
		) { shown ->
			val image = remember(shown) {
				shown.itemBackdropImages.firstOrNull() ?: shown.parentBackdropImages.firstOrNull()
			}

			if (image != null) {
				AsyncImage(
					url = image.getUrl(api, maxWidth = width, maxHeight = height),
					blurHash = image.blurHash,
					scaleType = ImageView.ScaleType.CENTER_CROP,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
	}
}

/** The card while the hero is the row being used: everything it has to say, and what to do about it. */
@Composable
private fun HeroCardOpen(
	selection: HomeHeroSelection,
	focus: HomeHeroFocus,
	focused: Boolean,
) {
	val heroTravel = with(LocalDensity.current) { HeroChangeTravel.roundToPx() }

	Column(
		verticalArrangement = Arrangement.spacedBy(Tokens.Space.spaceSm),
		modifier = Modifier
			.fillMaxSize()
			.padding(
				horizontal = HeroCardPaddingHorizontal,
				vertical = HeroCardPaddingVertical,
			),
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
		// title with a shorter description does not walk the buttons up the card.
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
					// title with a shorter description dragging the buttons up the card.
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
 * The card once the viewer has moved on: the picture, and whose picture it is.
 *
 * Nothing to do and nothing to read. The controls are drawn out of a card nobody is on because a
 * control that answers no key is worse than no control, and the description goes with them because
 * a paragraph is not something the eye keeps beside a row it is actually using.
 */
@Composable
private fun HeroCardClosed(
	item: BaseItemDto,
) {
	Box(
		contentAlignment = Alignment.CenterStart,
		modifier = Modifier
			.fillMaxSize()
			.padding(horizontal = HeroCardPaddingHorizontal),
	) {
		// A library without a logo for this title falls back to setting its name, and two lines of
		// display type is 80dp — which fits the open card and does not fit the band. Down here the
		// card is a reminder of what is at the top of the screen, not the thing being read, so the
		// name is held to one line rather than the band being sized around the longer case.
		HeroTitle(item = item, lines = 1, logoHeight = HeroLogoCompactHeight)
	}
}

/** Everything the hero says about the title it is showing, and the only part that changes with it. */
@Composable
private fun HeroDetails(
	item: BaseItemDto,
) {
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

		HeroTitle(item = item)

		// The same facts, in the same chips, as the detail page the second button leads to.
		//
		// Its items fill the height they are given, which on a detail page is a line and here is
		// the whole card — one parental rating chip stretched down it with everything else pushed
		// out of the way. Holding the row to its intrinsic height gives it the line back.
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
 * The title in its own lettering, where the library has it.
 *
 * It is the one piece of type on this screen that is drawn rather than set, and it is what makes
 * the card belong to the title instead of to the app — which is why it is the one thing the closed
 * card keeps.
 */
@Composable
private fun HeroTitle(
	item: BaseItemDto,
	lines: Int = 2,
	logoHeight: Dp = HeroLogoHeight,
	api: ApiClient = koinInject(),
) {
	val density = LocalDensity.current
	val logo = remember(item) { item.itemImages[ImageType.LOGO] ?: item.parentImages[ImageType.LOGO] }

	if (logo != null) {
		AsyncImage(
			// Asked for at the open card's height whatever height it is being drawn at, for the
			// same reason the picture is: the card closes onto its band a frame at a time, and a
			// logo re-fetched at the smaller size would be a request the viewer waits on to see
			// lettering that is already on the screen.
			url = logo.getUrl(api, maxHeight = with(density) { HeroLogoHeight.roundToPx() }),
			scaleType = ImageView.ScaleType.FIT_START,
			modifier = Modifier
				.height(logoHeight)
				.widthIn(max = HomeHeroMeasure)
				.fillMaxWidth(),
		)
	} else {
		Text(
			text = item.name.orEmpty(),
			maxLines = lines,
			overflow = TextOverflow.Ellipsis,
			color = Tokens.Color.colorWhite,
			style = JellyfinTheme.typography.display,
			modifier = Modifier.widthIn(max = HomeHeroMeasure),
		)
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
				// Cut at the last line there is, which is not always the last line asked for. The
				// card shrinks to its band while the crossfade is still holding this text, so the
				// description is measured in a box that fits one line before it is taken away, and
				// overflow only says the words did not fit — not that a second line was set. Asking
				// for a line that was never laid out throws, and it took the app down to the
				// launcher.
				val lastLine = (HeroOverviewLines - 1).coerceAtMost(layout.lineCount - 1)
				val end = layout.getLineEnd(lastLine, visibleEnd = true)
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

package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme

/** How heavy the ring around the focused card is. Thin enough to frame the artwork, not crop it. */
private val FocusRingWidth = 4.dp

/** How far the shade extends past the ring, inwards over the artwork. */
private val FocusRingShadeWidth = 1.dp

/**
 * How much bigger the focused card is drawn, as a fraction of its resting size.
 *
 * Remembered, not read every time. This composes once per card per row and recomposes on every
 * focus change — which is exactly when it would be read — and a resource lookup in that path is
 * the sort of thing the rest of this branch is busy taking out.
 */
@Composable
fun rememberCardFocusScale(): Float {
	val context = LocalContext.current
	return remember(context) { context.resources.getFraction(R.fraction.card_scale_focus, 1, 1) }
}

/**
 * Grow a card while it holds focus.
 *
 * The growth happens here rather than in leanback, which scales the whole item view — card and
 * the two lines of text beneath it together — so a focused card used to carry its own title
 * down with it while its neighbours' titles stayed put, and the row rippled as you moved along
 * it. Drawn rather than measured, so the words below are laid out against the card's resting
 * size and do not move at all.
 *
 * It grows from its own bottom edge, not its middle. The gap to the title is the one distance
 * on a card that has to stay put; growing from the middle closes it by half of whatever the
 * card gains, and at the sizes here that gap is only a few dp to begin with.
 */
@Composable
fun Modifier.cardFocusScale(focused: Boolean): Modifier {
	val cardScale = rememberCardFocusScale()
	val scale by animateFloatAsState(if (focused) cardScale else 1f, label = "card focus scale")

	return graphicsLayer {
		scaleX = scale
		scaleY = scale
		transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)
	}
}

/**
 * Darkens a card while something else holds the focus.
 *
 * A scrim rather than an alpha on the card: alpha forces every unfocused card in the row into its
 * own offscreen layer, and there are a lot more unfocused cards than focused ones.
 *
 * The parent clips to the card's shape, so this needs no shape of its own.
 */
@Composable
fun ItemCardUnfocusedScrim(
	focused: Boolean,
	modifier: Modifier,
) {
	val scrim = JellyfinTheme.colorScheme.cardUnfocusedScrim
	val dim by animateFloatAsState(if (focused) 0f else 1f, label = "card unfocused scrim")
	if (dim <= 0f) return

	Box(modifier = modifier.background(scrim.copy(alpha = scrim.alpha * dim)))
}

/**
 * Draw the ring that marks the focused card, over whatever the card contains.
 *
 * Being larger than its neighbours is all that otherwise marks the focused card, and on a row that
 * runs off the edge of the screen there is often nothing beside it to be larger than. A ring says
 * where the focus is without needing anything to compare against.
 *
 * Drawn from the modifier chain rather than as a child of the card, after the content: a child
 * would have to be told how big the card is, and the one way to ask for that inside a Box —
 * matchParentSize — does not survive the way leanback measures these rows.
 *
 * Two strokes, both centred on the card's own edge and both clipped to it, so only their inner
 * halves land: the ring, and a wider shaded one under it that shows as a dark band on the inside
 * edge. Sharing the edge is what keeps them concentric at the corners. The shade matters because
 * the ring is drawn over the artwork — against a bright poster a white ring alone is a band you
 * cannot find, while the side facing outwards already has the dark screen behind it.
 */
@Composable
fun Modifier.itemCardFocusRing(
	focused: Boolean,
	shape: Shape,
): Modifier {
	val ringAlpha by animateFloatAsState(if (focused) 1f else 0f, label = "card focus ring")
	val ringColor = JellyfinTheme.colorScheme.cardFocusRing
	val shadeColor = JellyfinTheme.colorScheme.cardFocusRingShade

	return drawWithContent {
		drawContent()

		if (ringAlpha <= 0f) return@drawWithContent

		val path = Path().apply {
			when (val outline = shape.createOutline(size, layoutDirection, this@drawWithContent)) {
				is Outline.Rectangle -> addRect(outline.rect)
				is Outline.Rounded -> addRoundRect(outline.roundRect)
				is Outline.Generic -> addPath(outline.path)
			}
		}

		fun Color.fade() = copy(alpha = alpha * ringAlpha)

		clipPath(path) {
			drawPath(path, color = shadeColor.fade(), style = Stroke((FocusRingWidth + FocusRingShadeWidth).toPx() * 2))
			drawPath(path, color = ringColor.fade(), style = Stroke(FocusRingWidth.toPx() * 2))
		}
	}
}

@Composable
@Stable
fun ItemCard(
	modifier: Modifier = Modifier,
	image: @Composable BoxScope.() -> Unit,
	overlay: (@Composable BoxScope.() -> Unit)? = null,
	shape: Shape = JellyfinTheme.shapes.medium,
	focused: Boolean = false,
) {
	Box(
		modifier = modifier
			.cardFocusScale(focused)
			.clip(shape)
			.background(JellyfinTheme.colorScheme.surface, shape)
			.itemCardFocusRing(focused = focused, shape = shape)
	) {
		image()

		// Over the artwork but under the overlay. The overlay carries the resume bar and the
		// unplayed badge, which say things about the item that are no less true while something
		// else has the focus — dimming those would be dimming the information, not the picture.
		ItemCardUnfocusedScrim(focused = focused, modifier = Modifier.fillMaxSize())

		if (overlay != null) {
			Box(
				modifier = Modifier.fillMaxSize(),
				content = overlay
			)
		}
	}
}

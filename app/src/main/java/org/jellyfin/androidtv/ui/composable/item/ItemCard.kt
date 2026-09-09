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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
fun BoxScope.ItemCardUnfocusedScrim(focused: Boolean) {
	val scrim = JellyfinTheme.colorScheme.cardUnfocusedScrim
	val dim by animateFloatAsState(if (focused) 0f else 1f, label = "card unfocused scrim")
	if (dim <= 0f) return

	Box(
		modifier = Modifier
			.matchParentSize()
			.background(scrim.copy(alpha = scrim.alpha * dim))
	)
}

/**
 * The ring that marks the focused card, drawn over the artwork inside the card's own bounds.
 *
 * Being larger than its neighbours is all that otherwise marks the focused card, and on a row that
 * runs off the edge of the screen there is often nothing beside it to be larger than. A ring says
 * where the focus is without needing anything to compare against.
 *
 * Two bands, not one, and both struck from the same bounds with the same [shape] so they stay
 * concentric at the corners: a wider shaded one underneath, then the ring itself over its outer
 * part. What is left showing is the ring with a dark edge on its inside, which is the side facing
 * the artwork and the only side that is not already against the screen's dark background.
 */
@Composable
fun BoxScope.ItemCardFocusRing(
	focused: Boolean,
	shape: Shape,
) {
	val ringAlpha by animateFloatAsState(if (focused) 1f else 0f, label = "card focus ring")
	if (ringAlpha <= 0f) return

	fun Color.fade() = copy(alpha = alpha * ringAlpha)

	Box(
		modifier = Modifier
			.matchParentSize()
			.border(FocusRingWidth + FocusRingShadeWidth, JellyfinTheme.colorScheme.cardFocusRingShade.fade(), shape)
			.border(FocusRingWidth, JellyfinTheme.colorScheme.cardFocusRing.fade(), shape)
	)
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
	) {
		image()

		// Over the artwork but under the overlay. The overlay carries the resume bar and the
		// unplayed badge, which say things about the item that are no less true while something
		// else has the focus — dimming those would be dimming the information, not the picture.
		ItemCardUnfocusedScrim(focused = focused)

		if (overlay != null) {
			Box(
				modifier = Modifier.fillMaxSize(),
				content = overlay
			)
		}

		ItemCardFocusRing(focused = focused, shape = shape)
	}
}

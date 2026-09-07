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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.JellyfinTheme

/** How heavy the ring around the focused card is. Thin enough to frame the artwork, not crop it. */
private val FocusRingWidth = 3.dp

@Composable
@Stable
fun ItemCard(
	modifier: Modifier = Modifier,
	image: @Composable BoxScope.() -> Unit,
	overlay: (@Composable BoxScope.() -> Unit)? = null,
	shape: Shape = JellyfinTheme.shapes.medium,
	focused: Boolean = false,
) {
	// Being larger than its neighbours is all that marks the focused card, and on a row that runs
	// off the edge of the screen there is often nothing beside it to be larger than. A ring says
	// where the focus is without needing anything to compare against, in the same colour the rest
	// of the app uses to say the same thing.
	val ringAlpha by animateFloatAsState(if (focused) 1f else 0f, label = "card focus ring")

	// The growth happens here rather than in leanback, which scales the whole item view — card and
	// the two lines of text beneath it together — so a focused card used to carry its own title
	// down with it while its neighbours' titles stayed put, and the row rippled as you moved along
	// it. Drawn rather than measured, so the words below are laid out against the card's resting
	// size and do not move at all.
	//
	// It grows from its own bottom edge, not its middle. The gap to the title is the one distance
	// on a card that has to stay put; growing from the middle closes it by half of whatever the
	// card gains, and at the sizes here that gap is only a few dp to begin with.
	// Remembered, not read every time. This composes once per card per row and recomposes on every
	// focus change — which is exactly when it would be read — and a resource lookup in that path is
	// the sort of thing the rest of this branch is busy taking out.
	val context = LocalContext.current
	val cardScale = remember(context) { context.resources.getFraction(R.fraction.card_scale_focus, 1, 1) }
	val scale by animateFloatAsState(if (focused) cardScale else 1f, label = "card focus scale")

	Box(
		modifier = modifier
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
				transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)
			}
			.clip(shape)
			.background(JellyfinTheme.colorScheme.surface, shape)
	) {
		image()

		if (overlay != null) {
			Box(
				modifier = Modifier.fillMaxSize(),
				content = overlay
			)
		}

		if (ringAlpha > 0f) {
			Box(
				modifier = Modifier
					.matchParentSize()
					.border(
						width = FocusRingWidth,
						color = JellyfinTheme.colorScheme.buttonFocused.let { it.copy(alpha = it.alpha * ringAlpha) },
						shape = shape,
					)
			)
		}
	}
}

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
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

	Box(
		modifier = modifier
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

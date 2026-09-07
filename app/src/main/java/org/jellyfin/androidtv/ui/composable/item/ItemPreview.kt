package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.ProvideTextStyle
import org.jellyfin.design.Tokens

@Composable
@Stable
fun ItemPreview(
	card: @Composable () -> Unit,
	modifier: Modifier = Modifier,
	title: (@Composable () -> Unit)? = null,
	subtitle: (@Composable () -> Unit)? = null,
	spacing: Dp = Tokens.Space.spaceXs,
	focused: Boolean = false,
) {
	ItemPreviewLayout(
		card = card,
		metadata = {
			ItemPreviewMetadata(
				title = title,
				subtitle = subtitle,
				spacing = spacing,
				focused = focused,
			)
		},
		spacing = spacing,
		modifier = modifier
	)
}

@Composable
@Stable
private fun ItemPreviewMetadata(
	title: (@Composable () -> Unit)?,
	subtitle: (@Composable () -> Unit)?,
	spacing: Dp,
	focused: Boolean,
) {
	Column(
		modifier = Modifier.padding(spacing),
		verticalArrangement = Arrangement.spacedBy(spacing),
	) {
		title?.let { content ->
			ProvideTextStyle(
				value = JellyfinTheme.typography.label.copy(
					// A row of titles set at one weight is a paragraph to read through. Lifting
					// the one under focus makes it the name of what is being looked at.
					color = if (focused) Tokens.Color.colorWhite else Tokens.Color.colorGrey100,
				),
				content = content,
			)
		}

		subtitle?.let { content ->
			ProvideTextStyle(
				value = JellyfinTheme.typography.caption.copy(
					color = Tokens.Color.colorGrey300,
				),
				content = content,
			)
		}
	}
}

@Composable
@Stable
fun ItemPreviewLayout(
	card: @Composable () -> Unit,
	metadata: @Composable () -> Unit,
	spacing: Dp = Tokens.Space.spaceXs,
	modifier: Modifier,
) {
	SubcomposeLayout(
		modifier = modifier,
	) { constraints ->
		val cardPlaceables = subcompose("card", card).map { it.measure(constraints) }
		val cardWidth = cardPlaceables.maxOf { it.width }
		val cardHeight = cardPlaceables.maxOf { it.height }

		val childConstraints = constraints.copy(minWidth = cardWidth, maxWidth = cardWidth)
		val metadataPlaceables = subcompose("metadata", metadata).map { it.measure(childConstraints) }
		val metadataHeight = metadataPlaceables.maxOf { it.height }

		val spacingPx = spacing.roundToPx()
		val height = cardHeight + spacingPx + metadataHeight

		layout(cardWidth, height) {
			cardPlaceables.forEach { it.place(0, 0) }
			metadataPlaceables.forEach { it.place(0, cardHeight + spacingPx) }
		}
	}
}

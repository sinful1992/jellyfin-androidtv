package org.jellyfin.androidtv.ui.player.video

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton

/**
 * A control row button that says what it does while it holds focus.
 *
 * The row is icons only, which from a sofa is a puzzle: what the wave or the card stands for is
 * learned by pressing it and seeing what opens. Labelling every button instead would lay a line of
 * text across the picture for the sake of the one being looked at, so only that one names itself.
 */
@Composable
fun PlayerControlButton(
	icon: ImageVector,
	label: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val focused by interactionSource.collectIsFocusedAsState()

	IconButton(
		onClick = onClick,
		enabled = enabled,
		interactionSource = interactionSource,
		modifier = modifier.animateContentSize(),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(
				imageVector = icon,
				contentDescription = label,
			)

			if (focused) Text(
				text = label,
				maxLines = 1,
				softWrap = false,
			)
		}
	}
}

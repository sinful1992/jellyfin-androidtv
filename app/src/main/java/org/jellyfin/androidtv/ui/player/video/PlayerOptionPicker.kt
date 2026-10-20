package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.popover.Popover

/**
 * A control row button, named [label] while focused, opening a single-choice list of [options],
 * each a value paired with the label to show for it. The option equal to [activeOption] is marked
 * as selected and holds focus when the list opens.
 */
@Composable
fun <T> PlayerOptionPicker(
	icon: Int,
	label: String,
	options: List<Pair<T, String>>,
	activeOption: T?,
	onSelect: (option: T) -> Unit,
) = Box {
	var expanded by remember { mutableStateOf(false) }

	PlayerControlButton(
		icon = ImageVector.vectorResource(icon),
		label = label,
		onClick = { expanded = true },
	)

	// The popover focuses its content as it opens, which lands on the first option: a list of tracks
	// would open on the top one, with nothing but a small tick further down to say which is playing.
	// Redirect that entry to the option already in use, so the list opens on the current choice.
	val activeOptionFocusRequester = remember { FocusRequester() }
	val hasActiveOption = options.any { (option, _) -> option == activeOption }

	// Long option lists scroll, but only inside a popover that still fits the screen: one grown
	// to the full window height has nowhere left to be positioned.
	val windowInfo = LocalWindowInfo.current
	val density = LocalDensity.current
	val maxHeight = remember(windowInfo.containerSize, density) {
		with(density) { windowInfo.containerSize.height.toDp() } * MAX_HEIGHT_FRACTION
	}

	Popover(
		expanded = expanded,
		onDismissRequest = { expanded = false },
		alignment = Alignment.TopCenter,
		offset = DpOffset(0.dp, (-5).dp),
	) {
		Column(
			modifier = Modifier
				.padding(4.dp)
				.widthIn(min = 220.dp, max = 400.dp)
				.heightIn(max = maxHeight)
				.focusGroup()
				.focusProperties {
					// Only when the requester has a row to land on: there is no active option when
					// nothing has been selected and the server marked no default, and requesting
					// focus through an unattached requester throws.
					if (hasActiveOption) onEnter = { activeOptionFocusRequester.requestFocus() }
				}
				.verticalScroll(rememberScrollState())
		) {
			for ((option, label) in options) {
				val isActiveOption = option == activeOption

				ListButton(
					onClick = {
						expanded = false
						onSelect(option)
					},
					modifier = if (isActiveOption) {
						Modifier.focusRequester(activeOptionFocusRequester)
					} else {
						Modifier
					},
					headingContent = { Text(label) },
					trailingContent = { RadioButton(checked = isActiveOption) },
				)
			}
		}
	}
}

/** The most of the screen height a picker may take, leaving room for the controls it opens from. */
private const val MAX_HEIGHT_FRACTION = 0.6f

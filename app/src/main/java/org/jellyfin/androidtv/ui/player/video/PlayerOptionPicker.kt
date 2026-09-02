package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.IconButton
import org.jellyfin.androidtv.ui.base.form.RadioButton
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.popover.Popover

/**
 * A control row button opening a single-choice list of [options], each a value paired with the
 * label to show for it. The option equal to [activeOption] is marked as selected.
 */
@Composable
fun <T> PlayerOptionPicker(
	icon: Int,
	contentDescription: String,
	options: List<Pair<T, String>>,
	activeOption: T?,
	onSelect: (option: T) -> Unit,
) = Box {
	var expanded by remember { mutableStateOf(false) }

	IconButton(onClick = { expanded = true }) {
		Icon(
			imageVector = ImageVector.vectorResource(icon),
			contentDescription = contentDescription,
		)
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
				.verticalScroll(rememberScrollState())
		) {
			for ((option, label) in options) {
				ListButton(
					onClick = {
						expanded = false
						onSelect(option)
					},
					headingContent = { Text(label) },
					trailingContent = { RadioButton(checked = option == activeOption) },
				)
			}
		}
	}
}

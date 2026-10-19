package org.jellyfin.androidtv.ui.presentation

import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ObjectAdapter

/** A [ListRow] that opens scrolled to [selectedPosition] and keeps it when the row is bound again */
class PreselectedListRow(header: HeaderItem, adapter: ObjectAdapter) : ListRow(header, adapter) {
	var selectedPosition = -1
}

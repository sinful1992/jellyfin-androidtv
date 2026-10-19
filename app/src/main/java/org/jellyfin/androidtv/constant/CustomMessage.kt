package org.jellyfin.androidtv.constant

sealed interface CustomMessage {
	data object RefreshCurrentItem : CustomMessage
}

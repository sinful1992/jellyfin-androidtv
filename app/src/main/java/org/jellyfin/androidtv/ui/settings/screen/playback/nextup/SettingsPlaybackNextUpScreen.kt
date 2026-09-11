package org.jellyfin.androidtv.ui.settings.screen.playback.nextup

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.form.Checkbox
import org.jellyfin.androidtv.ui.base.form.RangeControl
import org.jellyfin.androidtv.ui.base.list.ListButton
import org.jellyfin.androidtv.ui.base.list.ListControl
import org.jellyfin.androidtv.ui.base.list.ListSection
import org.jellyfin.androidtv.ui.navigation.LocalRouter
import org.jellyfin.androidtv.ui.navigation.focus.focusKey
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.compat.rememberPreference
import org.jellyfin.androidtv.ui.settings.composable.SettingsColumn
import org.jellyfin.design.Tokens
import org.koin.compose.koinInject
import kotlin.math.roundToInt

@Composable
fun SettingsPlaybackNextUpScreen() {
	val router = LocalRouter.current
	val userPreferences = koinInject<UserPreferences>()

	SettingsColumn {
		item {
			ListSection(
				overlineContent = { Text(stringResource(R.string.pref_playback).uppercase()) },
				headingContent = { Text(stringResource(R.string.pref_playback_next_up)) },
			)
		}

		item {
			var mediaQueuingEnabled by rememberPreference(userPreferences, UserPreferences.mediaQueuingEnabled)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_media_queueing)) },
				trailingContent = { Checkbox(checked = mediaQueuingEnabled) },
				captionContent = { Text(stringResource(R.string.pref_media_queueing_description)) },
				onClick = { mediaQueuingEnabled = !mediaQueuingEnabled },
				modifier = Modifier.focusKey("media_queuing_enabled")
			)
		}

		item {
			var nextUpBehavior by rememberPreference(userPreferences, UserPreferences.nextUpBehavior)

			ListButton(
				headingContent = { Text(stringResource(R.string.pref_next_up_behavior_title)) },
				captionContent = { Text(stringResource(nextUpBehavior.nameRes)) },
				onClick = { router.push(Routes.PLAYBACK_NEXT_UP_BEHAVIOR) },
				modifier = Modifier.focusKey(Routes.PLAYBACK_NEXT_UP_BEHAVIOR)
			)
		}
	}
}

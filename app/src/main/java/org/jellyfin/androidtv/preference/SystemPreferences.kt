package org.jellyfin.androidtv.preference

import android.content.Context
import org.jellyfin.preference.booleanPreference
import org.jellyfin.preference.store.SharedPreferenceStore
import org.jellyfin.preference.stringPreference

/**
 * System preferences are not possible to modify by the user.
 * They are mostly used to store states for various filters and warnings.
 *
 * @param context Context to get the SharedPreferences from
 */
class SystemPreferences(context: Context) : SharedPreferenceStore(
	sharedPreferences = context.getSharedPreferences("systemprefs", Context.MODE_PRIVATE)
) {
	companion object {
		// Live TV - Channel history
		// Live TV - Guide Filters
		// Other persistent variables
		/**
		 * The version name for the latest dismissed beta notification or empty if none.
		 */
		val dismissedBetaNotificationVersion = stringPreference("dismissed_beta_notification_version", "")

		/**
		 * Whether to disable the "UI mode" warning that shows when using the app on non TV devices.
		 */
		val disableUiModeWarning = booleanPreference("disable_ui_mode_warning", false)
	}
}

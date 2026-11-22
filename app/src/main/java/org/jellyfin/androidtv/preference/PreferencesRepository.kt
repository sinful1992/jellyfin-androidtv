package org.jellyfin.androidtv.preference

import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient

/**
 * Repository to access special preference stores.
 */
class PreferencesRepository(
	private val api: ApiClient,
	private val userSettingPreferences: UserSettingPreferences,
) {
	private val libraryPreferences = mutableMapOf<String, LibraryPreferences>()

	// FIXME: Remove the blocking API when the remaining callers support suspension.
	fun getLibraryPreferences(preferencesId: String): LibraryPreferences = runBlocking {
		loadLibraryPreferences(preferencesId)
	}

	suspend fun loadLibraryPreferences(preferencesId: String): LibraryPreferences {
		val store = libraryPreferences[preferencesId] ?: LibraryPreferences(preferencesId, api)

		libraryPreferences[preferencesId] = store

		if (store.shouldUpdate) store.update()

		return store
	}

	suspend fun onSessionChanged() {
		userSettingPreferences.update()

		libraryPreferences.clear()
	}
}

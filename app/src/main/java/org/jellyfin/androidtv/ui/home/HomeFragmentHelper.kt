package org.jellyfin.androidtv.ui.home

import android.content.Context
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.browsing.BrowseRowDef
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest

class HomeFragmentHelper(
	private val context: Context,
	private val userRepository: UserRepository,
) {
	fun loadRecentlyAdded(userViews: Collection<BaseItemDto>): HomeFragmentRow {
		return HomeFragmentLatestRow(userRepository, userViews)
	}

	fun loadResume(title: String, includeMediaTypes: Collection<MediaType>): HomeFragmentRow {
		val query = GetResumeItemsRequest(
			limit = ITEM_LIMIT_RESUME,
			fields = ItemRepository.browseFields,
			imageTypeLimit = 1,
			enableTotalRecordCount = false,
			mediaTypes = includeMediaTypes,
			excludeItemTypes = setOf(BaseItemKind.AUDIO_BOOK),
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(title, query, 0, false, true, arrayOf(ChangeTriggerType.TvPlayback, ChangeTriggerType.MoviePlayback)))
	}

	fun loadResumeVideo(): HomeFragmentRow {
		return loadResume(context.getString(R.string.lbl_continue_watching), listOf(MediaType.VIDEO))
	}

	fun loadResumeAudio(): HomeFragmentRow {
		return loadResume(context.getString(R.string.continue_listening), listOf(MediaType.AUDIO))
	}

	fun loadNextUp(): HomeFragmentRow {
		val query = GetNextUpRequest(
			imageTypeLimit = 1,
			limit = ITEM_LIMIT_NEXT_UP,
			enableResumable = false,
			fields = ItemRepository.browseFields
		)

		return HomeFragmentBrowseRowDefRow(BrowseRowDef(context.getString(R.string.lbl_next_up), query, arrayOf(ChangeTriggerType.TvPlayback)))
	}

	companion object {
		// Maximum amount of items loaded for a row
		private const val ITEM_LIMIT_RESUME = 50
		private const val ITEM_LIMIT_NEXT_UP = 50
	}
}

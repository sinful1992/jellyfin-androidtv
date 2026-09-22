package org.jellyfin.androidtv.ui.browsing

import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.querying.GetSpecialsRequest
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.presentation.GridButtonPresenter
import org.jellyfin.androidtv.util.apiclient.EmptyResponse
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.koin.android.ext.android.inject

class GenericFolderFragment : EnhancedBrowseFragment() {
	private val navigationRepository by inject<NavigationRepository>()

	companion object {
		private val showSpecialViewTypes = setOf(
			BaseItemKind.COLLECTION_FOLDER,
			BaseItemKind.FOLDER,
			BaseItemKind.USER_VIEW,
			BaseItemKind.CHANNEL_FOLDER_ITEM,
		)
	}

	override fun setupQueries(rowLoader: RowLoader) {
		if ((mFolder.childCount == null || mFolder.childCount == 0) && !setOf(
				BaseItemKind.CHANNEL,
				BaseItemKind.CHANNEL_FOLDER_ITEM,
				BaseItemKind.USER_VIEW,
				BaseItemKind.COLLECTION_FOLDER,
			).contains(mFolder.type)
		) return

		if (showSpecialViewTypes.contains(mFolder.type)) {
			if (mFolder.type != BaseItemKind.CHANNEL_FOLDER_ITEM) {
				val resume = GetItemsRequest(
					fields = ItemRepository.itemFields,
					parentId = mFolder.id,
					limit = 50,
					filters = setOf(ItemFilter.IS_RESUMABLE),
					sortBy = setOf(ItemSortBy.DATE_PLAYED),
					sortOrder = setOf(SortOrder.DESCENDING),
				)
				mRows.add(BrowseRowDef(getString(R.string.lbl_continue_watching), resume, 0))
			}

			val latest = GetItemsRequest(
				fields = ItemRepository.itemFields,
				parentId = mFolder.id,
				limit = 50,
				filters = setOf(ItemFilter.IS_UNPLAYED),
				sortBy = setOf(ItemSortBy.DATE_CREATED),
				sortOrder = setOf(SortOrder.DESCENDING),
			)
			mRows.add(BrowseRowDef(getString(R.string.lbl_latest), latest, 0))
		}

		val byName = GetItemsRequest(
			fields = ItemRepository.itemFields,
			parentId = mFolder.id,
		)
		val header = when (mFolder.type) {
			BaseItemKind.SEASON -> mFolder.name
			else -> getString(R.string.lbl_by_name)
		}

		mRows.add(BrowseRowDef(header, byName, 100))

		if (mFolder.type == BaseItemKind.SEASON) {
			val specials = GetSpecialsRequest(mFolder.id)
			mRows.add(BrowseRowDef(getString(R.string.lbl_specials), specials))
		}

		rowLoader.loadRows(mRows)

		if (mFolder.type == BaseItemKind.SEASON) addSeasonsRow()
	}

	// Every season as a text button, the current one marked, directly under the episodes so one
	// press down reaches it while focus still opens on the episodes. One request with the
	// card-only field set; a show with a single season gets no row. The series gets no row of its
	// own: moving between seasons replaces this page (see launchItem), so Back lands on the series.
	private fun addSeasonsRow() {
		val seriesId = mFolder.seriesId ?: mFolder.parentId ?: return
		val seasons = GetItemsRequest(
			fields = ItemRepository.browseFields,
			parentId = seriesId,
			includeItemTypes = setOf(BaseItemKind.SEASON),
			sortBy = setOf(ItemSortBy.INDEX_NUMBER),
		)

		val rowsAdapter = mRowsAdapter
		val presenter = GridButtonPresenter(150, 48, mFolder.id)
		val seasonsAdapter = ItemRowAdapter(requireContext(), seasons, 100, false, true, presenter, rowsAdapter)
		val row = ListRow(HeaderItem(getString(R.string.lbl_seasons)), seasonsAdapter)
		rowsAdapter.add(minOf(1, rowsAdapter.size()), row)
		seasonsAdapter.setRow(row)
		seasonsAdapter.setRetrieveFinishedListener(object : EmptyResponse(lifecycle) {
			override fun onResponse() {
				if (seasonsAdapter.size() == 1) rowsAdapter.remove(row)
			}
		})
		seasonsAdapter.Retrieve()
	}

	override fun launchItem(item: BaseRowItem, adapter: ItemRowAdapter) {
		val baseItem = item.baseItem
		if (mFolder.type == BaseItemKind.SEASON && baseItem?.type == BaseItemKind.SEASON) {
			if (baseItem.id != mFolder.id) navigationRepository.navigate(Destinations.folderBrowser(baseItem), true)
		} else {
			super.launchItem(item, adapter)
		}
	}
}

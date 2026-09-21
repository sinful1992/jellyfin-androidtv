package org.jellyfin.androidtv.ui.browsing

import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.querying.GetSpecialsRequest
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
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

			// One request with the card-only field set. A show with a single season comes back
			// empty and the row removes itself. The series gets no row of its own: moving between
			// seasons replaces this page (see launchItem), so Back always lands on the series.
			val seriesId = mFolder.seriesId ?: mFolder.parentId
			if (seriesId != null) {
				val otherSeasons = GetItemsRequest(
					fields = ItemRepository.browseFields,
					parentId = seriesId,
					includeItemTypes = setOf(BaseItemKind.SEASON),
					excludeItemIds = setOf(mFolder.id),
					sortBy = setOf(ItemSortBy.INDEX_NUMBER),
				)
				mRows.add(BrowseRowDef(getString(R.string.lbl_seasons), otherSeasons, 100))
			}
		}

		rowLoader.loadRows(mRows)
	}

	override fun launchItem(item: BaseRowItem, adapter: ItemRowAdapter) {
		val baseItem = item.baseItem
		if (mFolder.type == BaseItemKind.SEASON && baseItem?.type == BaseItemKind.SEASON) {
			navigationRepository.navigate(Destinations.folderBrowser(baseItem), true)
		} else {
			super.launchItem(item, adapter)
		}
	}
}

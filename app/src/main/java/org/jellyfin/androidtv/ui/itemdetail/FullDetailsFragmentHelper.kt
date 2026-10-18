package org.jellyfin.androidtv.ui.itemdetail

import android.content.ActivityNotFoundException
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.util.TimeUtils
import org.jellyfin.androidtv.util.popupMenu
import org.jellyfin.androidtv.util.sdk.TrailerUtils.getExternalTrailerIntent
import org.jellyfin.androidtv.util.sdk.compat.canResume
import org.jellyfin.androidtv.util.sdk.compat.copyWithUserData
import org.jellyfin.androidtv.util.showIfNotEmpty
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.tvShowsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.extensions.ticks
import org.jellyfin.sdk.model.serializer.toUUID
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun FullDetailsFragment.deleteItem(
	api: ApiClient,
	item: BaseItemDto,
	dataRefreshService: DataRefreshService,
	navigationRepository: NavigationRepository,
) = lifecycleScope.launch {
	Timber.i("Deleting item ${item.name} (id=${item.id})")

	try {
		withContext(Dispatchers.IO) {
			api.libraryApi.deleteItem(item.id)
		}
	} catch (error: ApiClientException) {
		Timber.e(error, "Failed to delete item ${item.name} (id=${item.id})")
		Toast.makeText(
			context,
			getString(R.string.item_deletion_failed, item.name),
			Toast.LENGTH_LONG
		).show()
		return@launch
	}

	dataRefreshService.lastDeletedItemId = item.id

	if (navigationRepository.canGoBack) navigationRepository.goBack()
	else navigationRepository.navigate(Destinations.home)

	Toast.makeText(context, getString(R.string.item_deleted, item.name), Toast.LENGTH_LONG).show()
}

fun FullDetailsFragment.showDetailsMenu(
	view: View,
	baseItemDto: BaseItemDto,
) = popupMenu(requireContext(), view) {
	// for each button check if it exists (not-null) and is invisible (overflow prevention)
	if (queueButton?.isVisible == false) {
		item(getString(R.string.lbl_add_to_queue)) { addItemToQueue() }
	}

	if (shuffleButton?.isVisible == false) {
		item(getString(R.string.lbl_shuffle_all)) { shufflePlay() }
	}

	if (trailerButton?.isVisible == false) {
		item(getString(R.string.lbl_play_trailers)) { playTrailers() }
	}

	if (favButton?.isVisible == false) {
		val favoriteStringRes = when (baseItemDto.userData?.isFavorite) {
			true -> R.string.lbl_remove_favorite
			else -> R.string.lbl_add_favorite
		}

		item(getString(favoriteStringRes)) { toggleFavorite() }
	}

	if (goToSeriesButton?.isVisible == false) {
		item(getString(R.string.lbl_goto_series)) { gotoSeries() }
	}
}.showIfNotEmpty()

fun FullDetailsFragment.toggleFavorite() {
	val itemMutationRepository by inject<ItemMutationRepository>()
	val dataRefreshService by inject<DataRefreshService>()

	lifecycleScope.launch {
		val userData = itemMutationRepository.setFavorite(
			item = mBaseItem.id,
			favorite = !(mBaseItem.userData?.isFavorite ?: false)
		)
		mBaseItem = mBaseItem.copyWithUserData(userData)
		favButton.isActivated = userData.isFavorite
		dataRefreshService.lastFavoriteUpdate = Instant.now()
	}
}

fun FullDetailsFragment.togglePlayed() {
	val itemMutationRepository by inject<ItemMutationRepository>()
	val dataRefreshService by inject<DataRefreshService>()

	lifecycleScope.launch {
		val userData = itemMutationRepository.setPlayed(
			item = mBaseItem.id,
			played = !(mBaseItem.userData?.played ?: false)
		)
		mBaseItem = mBaseItem.copyWithUserData(userData)
		mWatchedToggleButton.isActivated = userData.played

		// Adjust resume
		mResumeButton?.apply {
			isVisible = mBaseItem.canResume
		}

		// Force lists to re-fetch
		dataRefreshService.lastPlayback = Instant.now()
		when (mBaseItem.type) {
			BaseItemKind.MOVIE -> dataRefreshService.lastMoviePlayback = Instant.now()
			BaseItemKind.EPISODE -> dataRefreshService.lastTvPlayback = Instant.now()
			else -> Unit
		}

		showMoreButtonIfNeeded()
	}
}

fun FullDetailsFragment.playTrailers() {
	val localTrailerCount = mBaseItem.localTrailerCount ?: 0

	// External trailer
	if (localTrailerCount < 1) try {
		val intent = getExternalTrailerIntent(requireContext(), mBaseItem)
		if (intent != null) startActivity(intent)
	} catch (exception: ActivityNotFoundException) {
		Timber.w(exception, "Unable to open external trailer")
		Toast.makeText(
			requireContext(),
			getString(R.string.no_player_message),
			Toast.LENGTH_LONG
		).show()
	} else lifecycleScope.launch {
		val api by inject<ApiClient>()

		try {
			val trailers = withContext(Dispatchers.IO) {
				api.userLibraryApi.getLocalTrailers(mBaseItem.id).content
			}
			play(trailers, 0, false)
		} catch (exception: ApiClientException) {
			Timber.e(exception, "Error retrieving trailers for playback")
			Toast.makeText(
				requireContext(),
				getString(R.string.msg_video_playback_error),
				Toast.LENGTH_LONG
			).show()
		}
	}
}

fun FullDetailsFragment.getItem(id: UUID, callback: (item: BaseItemDto?) -> Unit) {
	val api by inject<ApiClient>()

	lifecycleScope.launch {
		val response = try {
			withContext(Dispatchers.IO) {
				api.userLibraryApi.getItem(id).content
			}
		} catch (err: ApiClientException) {
			Timber.w(err, "Failed to get item $id")
			null
		}

		callback(response)
	}
}

fun FullDetailsFragment.populatePreviousButton() {
	if (mBaseItem.type != BaseItemKind.EPISODE) return

	val api by inject<ApiClient>()

	lifecycleScope.launch {
		val siblings = withContext(Dispatchers.IO) {
			api.tvShowsApi.getEpisodes(
				seriesId = requireNotNull(mBaseItem.seriesId),
				adjacentTo = mBaseItem.id,
			).content
		}

		val previousItem = siblings.items
			.filterNot { it.id == mBaseItem.id }
			.firstOrNull()
			?.id

		mPrevItemId = previousItem
		mPrevButton.isVisible = previousItem != null

		showMoreButtonIfNeeded()
	}
}

fun FullDetailsFragment.getNextUpEpisode(callback: (BaseItemDto?) -> Unit) {
	lifecycleScope.launch {
		val nextUpEpisode = getNextUpEpisode()
		callback(nextUpEpisode)
	}
}

suspend fun FullDetailsFragment.getNextUpEpisode(): BaseItemDto? = try {
	findNextUpEpisode()
} catch (err: ApiClientException) {
	Timber.w(err, "Failed to get next up items")
	null
}

private suspend fun FullDetailsFragment.findNextUpEpisode(): BaseItemDto? {
	val api by inject<ApiClient>()
	val seriesId = mBaseItem.seriesId ?: mBaseItem.id

	val episodes = withContext(Dispatchers.IO) {
		api.tvShowsApi.getNextUp(
			seriesId = seriesId,
			fields = ItemRepository.itemFields,
			limit = 1,
		).content
	}
	episodes.items.firstOrNull()?.let { return it }

	// Next Up continues after the highest played episode, so it is empty once the finale is
	// played even when the user is rewatching earlier episodes
	val fallback = withContext(Dispatchers.IO) {
		suspend fun latest(filter: ItemFilter) = api.itemsApi.getItems(
			parentId = seriesId,
			recursive = true,
			includeItemTypes = setOf(BaseItemKind.EPISODE),
			filters = setOf(filter),
			sortBy = setOf(ItemSortBy.DATE_PLAYED),
			sortOrder = setOf(SortOrder.DESCENDING),
			fields = ItemRepository.itemFields,
			limit = 1,
		).content.items.firstOrNull()

		suspend fun inOrder(filter: ItemFilter?) = api.itemsApi.getItems(
			parentId = seriesId,
			recursive = true,
			includeItemTypes = setOf(BaseItemKind.EPISODE),
			filters = listOfNotNull(filter),
			isMissing = false,
			sortBy = setOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER),
			sortOrder = setOf(SortOrder.ASCENDING),
			fields = ItemRepository.itemFields,
			limit = 50,
		).content.items

		val resumable = latest(ItemFilter.IS_RESUMABLE)
		val lastPlayed = latest(ItemFilter.IS_PLAYED)

		if (resumable != null && !isOlderThan(resumable, lastPlayed)) return@withContext resumable

		val afterLastPlayed = lastPlayed?.let {
			api.tvShowsApi.getEpisodes(
				seriesId = seriesId,
				startItemId = it.id,
				isMissing = false,
				fields = ItemRepository.itemFields,
				limit = 10,
			).content.items
		}.orEmpty()

		pickEpisodeAfter(afterLastPlayed, lastPlayed?.id)
			?: resumable
			?: pickFirstEpisode(inOrder(ItemFilter.IS_UNPLAYED))
			?: pickFirstEpisode(inOrder(null))
	}
	Timber.i("Next up empty for series $seriesId, falling back to ${fallback?.id}")
	return fallback
}

internal fun isOlderThan(item: BaseItemDto, other: BaseItemDto?): Boolean {
	val itemDate = item.userData?.lastPlayedDate ?: return other != null
	val otherDate = other?.userData?.lastPlayedDate ?: return false
	return itemDate < otherDate
}

/** The regular episode following [startId] in a list that starts with it */
internal fun pickEpisodeAfter(episodes: List<BaseItemDto>, startId: UUID?): BaseItemDto? =
	episodes
		.dropWhile { it.id != startId }
		.drop(1)
		.firstOrNull { it.parentIndexNumber != 0 }

/** First regular episode in season order, specials (season 0) only when there is nothing else */
internal fun pickFirstEpisode(episodes: List<BaseItemDto>): BaseItemDto? =
	episodes.firstOrNull { it.parentIndexNumber != 0 } ?: episodes.firstOrNull()

fun FullDetailsFragment.resumePlayback(v: View) {
	if (mBaseItem.type != BaseItemKind.SERIES) {
		val pos = (mBaseItem.userData?.playbackPositionTicks?.ticks
			?: Duration.ZERO) - resumePreroll.milliseconds
		play(mBaseItem, pos.inWholeMilliseconds.toInt(), false)
		return
	}

	lifecycleScope.launch {
		val nextUpEpisode = try {
			findNextUpEpisode()
		} catch (err: ApiClientException) {
			Timber.w(err, "Failed to get next up items")
			Toast.makeText(
				requireContext(),
				getString(R.string.msg_video_playback_error),
				Toast.LENGTH_LONG
			).show()
			return@launch
		}

		if (nextUpEpisode == null) {
			Toast.makeText(
				requireContext(),
				getString(R.string.msg_series_no_episodes),
				Toast.LENGTH_LONG
			).show()
		} else if (nextUpEpisode.userData?.playbackPositionTicks == 0L) {
			play(nextUpEpisode, 0, false)
		} else {
			showResumeMenu(v, nextUpEpisode)
		}
	}
}

fun FullDetailsFragment.showResumeMenu(
	view: View,
	nextUpEpisode: BaseItemDto
) = popupMenu(requireContext(), view) {
	val pos = (nextUpEpisode.userData?.playbackPositionTicks?.ticks
		?: Duration.ZERO) - resumePreroll.milliseconds
	item(
		getString(
			R.string.lbl_resume_from,
			TimeUtils.formatMillis(pos.inWholeMilliseconds)
		)
	) {
		play(nextUpEpisode, pos.inWholeMilliseconds.toInt(), false)
	}
	item(getString(R.string.lbl_from_beginning)) {
		play(nextUpEpisode, 0, false)
	}
}.showIfNotEmpty()

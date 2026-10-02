package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

interface UserViewsRepository {
	val views: Flow<Collection<BaseItemDto>>

	fun isSupported(collectionType: CollectionType?): Boolean
	fun allowViewSelection(collectionType: CollectionType?): Boolean
	fun allowGridView(collectionType: CollectionType?): Boolean
}

class UserViewsRepositoryImpl(
	private val api: ApiClient,
) : UserViewsRepository {
	// A cold start asks for the libraries from three places within a few seconds: the home
	// sections, the library row and the launcher channels. One answer serves all of them while it
	// is fresh, and only for the session that fetched it.
	private val cacheLock = Mutex()
	private var cached: CachedViews? = null

	private class CachedViews(
		val accessToken: String?,
		val fetchedAt: TimeMark,
		val views: List<BaseItemDto>,
	)

	override val views = flow {
		emit(getViews())
	}.flowOn(Dispatchers.IO)

	private suspend fun getViews(): List<BaseItemDto> = cacheLock.withLock {
		cached
			?.takeIf { it.accessToken == api.accessToken && it.fetchedAt.elapsedNow() < CACHE_DURATION }
			?.let { return@withLock it.views }

		val fetchedAt = TimeSource.Monotonic.markNow()
		val views by api.userViewsApi.getUserViews()
		val filteredViews = views.items
			.filter { isSupported(it.collectionType) }
		cached = CachedViews(api.accessToken, fetchedAt, filteredViews)
		filteredViews
	}

	override fun isSupported(collectionType: CollectionType?) = collectionType !in unsupportedCollectionTypes
	override fun allowViewSelection(collectionType: CollectionType?) = collectionType !in disallowViewSelectionCollectionTypes
	override fun allowGridView(collectionType: CollectionType?) = collectionType !in disallowGridViewCollectionTypes

	private companion object {
		private val CACHE_DURATION = 10.seconds

		private val unsupportedCollectionTypes = arrayOf(
			CollectionType.BOOKS,
			CollectionType.FOLDERS,
			// Live TV was removed; without this its library opens a browse screen with no rows.
			CollectionType.LIVETV
		)

		private val disallowViewSelectionCollectionTypes = arrayOf(
			CollectionType.LIVETV,
			CollectionType.MUSIC,
			CollectionType.PHOTOS,
		)

		private val disallowGridViewCollectionTypes = arrayOf(
			CollectionType.LIVETV,
			CollectionType.MUSIC
		)
	}
}

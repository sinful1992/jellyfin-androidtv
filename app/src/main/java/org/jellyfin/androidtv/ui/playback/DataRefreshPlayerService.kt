package org.jellyfin.androidtv.ui.playback

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Instant

/**
 * Tell the rest of the app that playback changed what the server knows about an item.
 *
 * Every list that shows progress or a watched state is cached and only re-fetched when
 * [DataRefreshService] says something happened: the home rows compare their last retrieve against
 * [DataRefreshService.lastTvPlayback] and [DataRefreshService.lastMoviePlayback], and the item
 * detail screen re-reads itself — or loads the episode that actually played last — off
 * [DataRefreshService.lastPlayback] and [DataRefreshService.lastPlayedItem].
 *
 * The legacy player wrote all four, from PlaybackController and ReportingHelper, and both went with
 * it. Nothing has written them since for playback inside the app, so Continue Watching kept showing
 * an episode that had just been watched to the end until the app was restarted, and backing out of
 * the player landed on the stale detail screen of whichever item opened it rather than the one the
 * queue had moved on to.
 */
class DataRefreshPlayerService(
	private val dataRefreshService: DataRefreshService,
) : PlayerService() {
	/**
	 * The item currently playing, kept because the boundary is where it stops being current and the
	 * queue by then names the item taking its place.
	 */
	private var playing: BaseItemDto? = null

	override suspend fun onInitialize() {
		manager.queue.entry
			.map { entry -> entry?.baseItem }
			.distinctUntilChanged { old, new -> old?.id == new?.id }
			.onEach { item ->
				// The item being left is the one whose watched state and resume position the server
				// has just been told about, so it decides which lists are stale — not the one
				// starting.
				playing?.let(::invalidateFor)
				playing = item

				if (item != null) dataRefreshService.lastPlayedItem = item
			}
			.launchIn(coroutineScope)
	}

	private fun invalidateFor(item: BaseItemDto) {
		val now = Instant.now()
		dataRefreshService.lastPlayback = now

		// Audio runs through the same queue, and a song is not a reason to re-fetch the video rows.
		when (item.type) {
			BaseItemKind.MOVIE -> dataRefreshService.lastMoviePlayback = now
			BaseItemKind.EPISODE -> dataRefreshService.lastTvPlayback = now
			else -> Unit
		}
	}
}

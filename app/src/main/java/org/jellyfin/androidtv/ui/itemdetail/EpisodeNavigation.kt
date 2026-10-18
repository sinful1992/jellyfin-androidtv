package org.jellyfin.androidtv.ui.itemdetail

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** Moving between episodes of one series replaces the details page, so Back skips them */
internal fun replacesDetails(current: BaseItemDto, target: BaseItemDto): Boolean =
	current.type == BaseItemKind.EPISODE &&
		target.type == BaseItemKind.EPISODE &&
		current.seriesId != null &&
		current.seriesId == target.seriesId

/** Where a season's episode row starts: the opened episode, else resumable, else first unplayed, else the first */
internal fun initialEpisodeIndex(episodes: List<BaseItemDto?>, openedId: UUID?): Int {
	if (openedId != null) {
		val opened = episodes.indexOfFirst { it?.id == openedId }
		if (opened >= 0) return opened
	}

	val resumable = episodes.indexOfFirst { it?.userData?.played == false && (it.userData?.playbackPositionTicks ?: 0) > 0 }
	if (resumable >= 0) return resumable

	val unplayed = episodes.indexOfFirst { it?.userData?.played == false }
	return if (unplayed >= 0) unplayed else 0
}

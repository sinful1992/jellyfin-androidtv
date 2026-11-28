package org.jellyfin.androidtv.ui.browsing

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.composable.item.CardOverlay
import org.jellyfin.androidtv.ui.composable.item.cardOverlay
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.ui.presentation.isLeanGridItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

/**
 * Task 201: a lean grid poster paints the same badges and bar the full card's overlay
 * (ItemCardBaseItemOverlay) shows, decided from plain values; and only title-less posters go lean.
 */
class LibraryGridCardTests : FunSpec({
	fun item(
		type: BaseItemKind = BaseItemKind.MOVIE,
		mediaType: MediaType = MediaType.VIDEO,
		played: Boolean = false,
		favorite: Boolean = false,
		unplayed: Int? = null,
		percent: Double? = null,
		timerId: String? = null,
		seriesTimerId: String? = null,
	): BaseItemDto {
		val id = UUID.randomUUID()
		return BaseItemDto(
			id = id,
			type = type,
			mediaType = mediaType,
			timerId = timerId,
			seriesTimerId = seriesTimerId,
			userData = UserItemDataDto(
				playbackPositionTicks = 0,
				playCount = if (played) 1 else 0,
				isFavorite = favorite,
				played = played,
				key = id.toString(),
				itemId = id,
				unplayedItemCount = unplayed,
				playedPercentage = percent,
			),
		)
	}

	val always = WatchedIndicatorBehavior.ALWAYS

	test("a plain unwatched film shows nothing") {
		cardOverlay(item(), always) shouldBe CardOverlay()
	}

	test("watched: the tick, under every setting but NEVER; EPISODES_ONLY only for episodes") {
		cardOverlay(item(played = true), always).watched shouldBe true
		cardOverlay(item(played = true), WatchedIndicatorBehavior.HIDE_UNWATCHED).watched shouldBe true
		cardOverlay(item(played = true), WatchedIndicatorBehavior.NEVER).watched shouldBe false
		cardOverlay(item(played = true), WatchedIndicatorBehavior.EPISODES_ONLY).watched shouldBe false
		cardOverlay(item(type = BaseItemKind.EPISODE, played = true), WatchedIndicatorBehavior.EPISODES_ONLY).watched shouldBe true
	}

	test("unplayed count: shown unless played, zero, NEVER, HIDE_UNWATCHED or EPISODES_ONLY on a non-episode") {
		cardOverlay(item(type = BaseItemKind.SERIES, unplayed = 4), always).unplayedCount shouldBe 4
		cardOverlay(item(type = BaseItemKind.SERIES, unplayed = 0), always).unplayedCount shouldBe null
		cardOverlay(item(type = BaseItemKind.SERIES, unplayed = 4, played = true), always) shouldBe
			CardOverlay(watched = true)
		cardOverlay(item(type = BaseItemKind.SERIES, unplayed = 4), WatchedIndicatorBehavior.NEVER).unplayedCount shouldBe null
		cardOverlay(item(type = BaseItemKind.SERIES, unplayed = 4), WatchedIndicatorBehavior.HIDE_UNWATCHED).unplayedCount shouldBe null
		cardOverlay(item(type = BaseItemKind.SERIES, unplayed = 4), WatchedIndicatorBehavior.EPISODES_ONLY).unplayedCount shouldBe null
	}

	test("favourite heart") {
		cardOverlay(item(favorite = true), always).favorite shouldBe true
	}

	test("recording: only a programme with a timer; active only with a series timer too") {
		cardOverlay(item(timerId = "t"), always).recording shouldBe false
		cardOverlay(item(type = BaseItemKind.PROGRAM, timerId = "t"), always) shouldBe CardOverlay(recording = true)
		cardOverlay(item(type = BaseItemKind.LIVE_TV_PROGRAM, timerId = "t", seriesTimerId = "s"), always) shouldBe
			CardOverlay(recording = true, recordingActive = true)
		cardOverlay(item(type = BaseItemKind.PROGRAM, seriesTimerId = "s"), always) shouldBe CardOverlay()
	}

	test("progress: the saved percentage for video and books, clamped to 0..1, else none") {
		cardOverlay(item(percent = 37.5), always).progress shouldBe 0.375f
		cardOverlay(item(percent = 0.0), always).progress shouldBe 0f
		cardOverlay(item(percent = 140.0), always).progress shouldBe 1f
		cardOverlay(item(percent = -5.0), always).progress shouldBe 0f
		cardOverlay(item(), always).progress shouldBe null
		cardOverlay(item(mediaType = MediaType.BOOK, percent = 50.0), always).progress shouldBe 0.5f
		cardOverlay(item(mediaType = MediaType.AUDIO, percent = 50.0), always).progress shouldBe null
	}

	test("lean route: films, sets and series; not folders, people, videos or grid buttons") {
		fun row(type: BaseItemKind) = BaseItemDtoBaseRowItem(item(type = type))
		isLeanGridItem(row(BaseItemKind.MOVIE)) shouldBe true
		isLeanGridItem(row(BaseItemKind.BOX_SET)) shouldBe true
		isLeanGridItem(row(BaseItemKind.SERIES)) shouldBe true
		isLeanGridItem(row(BaseItemKind.FOLDER)) shouldBe false
		isLeanGridItem(row(BaseItemKind.VIDEO)) shouldBe false
		isLeanGridItem(row(BaseItemKind.PERSON)) shouldBe false
		isLeanGridItem(GridButtonBaseRowItem(GridButton(1, "one"))) shouldBe false
	}
})

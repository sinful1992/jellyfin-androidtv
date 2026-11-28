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
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.presentation.gridPreviewUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.Api
import org.jellyfin.sdk.model.api.ImageType as SdkImageType
import androidx.compose.ui.unit.Density
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlin.reflect.KClass

/** An ApiClient whose URLs are "<path>?<query>", with the query in a stable order (as JellyfinImageTests). */
private fun fakeApi(): ApiClient = mockk<ApiClient>().also { api ->
	every { api.getOrCreateApi(any<KClass<Api>>(), any()) } answers {
		@Suppress("UNCHECKED_CAST")
		(secondArg<(ApiClient) -> Api>()).invoke(api)
	}
	every { api.createUrl(any(), any(), any(), any()) } answers {
		val query = thirdArg<Map<String, Any?>>()
			.filterValues { it != null }
			.toSortedMap()
			.entries
			.joinToString("&") { (key, value) -> "$key=$value" }
		"${firstArg<String>()}?$query"
	}
}

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

	test("ahead-built url: a lean poster's preview url at its card size; none for a full card") {
		val api = fakeApi()
		val film = BaseItemDtoBaseRowItem(
			item(type = BaseItemKind.MOVIE).copy(imageTags = mapOf(SdkImageType.PRIMARY to "t"), primaryImageAspectRatio = 2.0 / 3.0),
			staticHeight = true,
		)
		val url = gridPreviewUrl(film, ImageType.POSTER, staticHeight = 200, density = Density(1.5f), api = api)!!
		// The card's size maths: 200 dp high at 2:3 on a 1.5x screen = 200 x 300 px, preview quality.
		url shouldBe "/Items/{itemId}/Images/{imageType}?maxHeight=300&maxWidth=200&quality=60&tag=t"
		val again = gridPreviewUrl(film, ImageType.POSTER, staticHeight = 200, density = Density(1.5f), api = api)
		again shouldBe url
		(gridPreviewUrl(film, ImageType.POSTER, staticHeight = 300, density = Density(1.5f), api = api) == url) shouldBe false
		gridPreviewUrl(BaseItemDtoBaseRowItem(item(type = BaseItemKind.FOLDER)), ImageType.POSTER, 200, Density(1.5f), api) shouldBe null
		gridPreviewUrl(GridButtonBaseRowItem(GridButton(1, "one")), ImageType.POSTER, 200, Density(1.5f), api) shouldBe null
	}
})

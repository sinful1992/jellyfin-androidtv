package org.jellyfin.androidtv.ui.itemdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

private fun item(
	type: BaseItemKind,
	seriesId: UUID? = null,
	index: Int? = null,
	played: Boolean = false,
	positionTicks: Long = 0,
): BaseItemDto {
	val id = UUID.randomUUID()
	return BaseItemDto(
		id = id,
		type = type,
		seriesId = seriesId,
		indexNumber = index,
		userData = UserItemDataDto(
			playbackPositionTicks = positionTicks,
			playCount = if (played) 1 else 0,
			isFavorite = false,
			played = played,
			key = id.toString(),
			itemId = id,
		),
	)
}

class EpisodeNavigationTests : FunSpec({
	val series = UUID.randomUUID()

	test("episode to episode of the same series replaces") {
		replacesDetails(item(BaseItemKind.EPISODE, series), item(BaseItemKind.EPISODE, series)) shouldBe true
	}

	test("episode to episode of another series pushes") {
		replacesDetails(item(BaseItemKind.EPISODE, series), item(BaseItemKind.EPISODE, UUID.randomUUID())) shouldBe false
	}

	test("episodes without a series push") {
		replacesDetails(item(BaseItemKind.EPISODE), item(BaseItemKind.EPISODE)) shouldBe false
	}

	test("series page to episode pushes") {
		replacesDetails(item(BaseItemKind.SERIES, series), item(BaseItemKind.EPISODE, series)) shouldBe false
	}

	test("episode to person or movie pushes") {
		replacesDetails(item(BaseItemKind.EPISODE, series), item(BaseItemKind.PERSON)) shouldBe false
		replacesDetails(item(BaseItemKind.EPISODE, series), item(BaseItemKind.MOVIE)) shouldBe false
	}

	test("season of the same series on an episode page swaps the row") {
		switchesSeason(item(BaseItemKind.EPISODE, series), item(BaseItemKind.SEASON, series)) shouldBe true
	}

	test("season on the series page, or of another series, navigates") {
		switchesSeason(item(BaseItemKind.SERIES, series), item(BaseItemKind.SEASON, series)) shouldBe false
		switchesSeason(item(BaseItemKind.EPISODE, series), item(BaseItemKind.SEASON, UUID.randomUUID())) shouldBe false
		switchesSeason(item(BaseItemKind.EPISODE), item(BaseItemKind.SEASON)) shouldBe false
	}

	test("opened episode is found by id, not by episode number") {
		// Multi-episode files: "E14-15" sits at list position 12 (server.md, The Creature Cases S1)
		val episodes = (1..17).map { item(BaseItemKind.EPISODE, series, index = if (it <= 11) it else 12 + (it - 12) * 2, played = true) }
		initialEpisodeIndex(episodes, episodes[12].id) shouldBe 12
	}

	test("opened episode wins over resumable and unplayed ones") {
		val episodes = listOf(item(BaseItemKind.EPISODE, played = false, positionTicks = 10), item(BaseItemKind.EPISODE, played = true))
		initialEpisodeIndex(episodes, episodes[1].id) shouldBe 1
	}

	test("another season starts on its resumable episode") {
		val episodes = listOf(
			item(BaseItemKind.EPISODE, played = true),
			item(BaseItemKind.EPISODE, played = false),
			item(BaseItemKind.EPISODE, played = false, positionTicks = 10),
		)
		initialEpisodeIndex(episodes, null) shouldBe 2
	}

	test("another season starts on its first unplayed episode") {
		val episodes = listOf(item(BaseItemKind.EPISODE, played = true), item(BaseItemKind.EPISODE, played = false))
		initialEpisodeIndex(episodes, UUID.randomUUID()) shouldBe 1
	}

	test("fully played season starts on the first episode") {
		initialEpisodeIndex(listOf(item(BaseItemKind.EPISODE, played = true), item(BaseItemKind.EPISODE, played = true)), null) shouldBe 0
	}

	test("empty or unloaded rows start at 0") {
		initialEpisodeIndex(emptyList(), UUID.randomUUID()) shouldBe 0
		initialEpisodeIndex(listOf(null, null), UUID.randomUUID()) shouldBe 0
	}
})

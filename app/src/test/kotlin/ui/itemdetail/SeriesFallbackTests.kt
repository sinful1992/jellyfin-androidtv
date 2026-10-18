package org.jellyfin.androidtv.ui.itemdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDateTime
import java.util.UUID

private fun episode(season: Int, index: Int, lastPlayed: LocalDateTime? = null): BaseItemDto {
	val id = UUID.randomUUID()
	return BaseItemDto(
		id = id,
		type = BaseItemKind.EPISODE,
		parentIndexNumber = season,
		indexNumber = index,
		userData = UserItemDataDto(
			playbackPositionTicks = 0,
			playCount = 0,
			isFavorite = false,
			lastPlayedDate = lastPlayed,
			played = lastPlayed != null,
			key = id.toString(),
			itemId = id,
		),
	)
}

class SeriesFallbackTests : FunSpec({
	val july = LocalDateTime.of(2026, 7, 15, 21, 0)
	val october = LocalDateTime.of(2026, 10, 16, 23, 0)

	test("resumable episode newer than the last played one wins") {
		isOlderThan(episode(1, 13, october), episode(1, 12, july)) shouldBe false
	}

	test("resumable episode older than the last played one loses") {
		isOlderThan(episode(1, 5, july), episode(3, 2, october)) shouldBe true
	}

	test("resumable episode without a date only wins when nothing was played") {
		isOlderThan(episode(1, 5), null) shouldBe false
		isOlderThan(episode(1, 5), episode(1, 4, july)) shouldBe true
	}

	test("episode after the last played one is picked even when it was played before (rewatch)") {
		val s14e15 = episode(14, 15, october)
		val s14e16 = episode(14, 16, july)
		pickEpisodeAfter(listOf(s14e15, s14e16, episode(14, 17, july)), s14e15.id) shouldBe s14e16
	}

	test("specials after the last played episode are skipped") {
		val start = episode(2, 10, october)
		val next = episode(3, 1)
		pickEpisodeAfter(listOf(start, episode(0, 4), next), start.id) shouldBe next
	}

	test("nothing after the finale") {
		val finale = episode(15, 20, october)
		pickEpisodeAfter(listOf(finale), finale.id) shouldBe null
		pickEpisodeAfter(emptyList(), null) shouldBe null
	}

	test("first episode prefers regular seasons over specials") {
		val s1e1 = episode(1, 1)
		pickFirstEpisode(listOf(episode(0, 1), s1e1)) shouldBe s1e1
	}

	test("first episode falls back to a special when that is all there is") {
		val special = episode(0, 1)
		pickFirstEpisode(listOf(special)) shouldBe special
		pickFirstEpisode(emptyList()) shouldBe null
	}

	// A fully watched series: S1E1-S1E10, all played in July, then rewatched up to S1E5 in October
	fun watchedSeries(rewatchedUpTo: Int?) = (1..10).map { index ->
		episode(1, index, if (rewatchedUpTo != null && index <= rewatchedUpTo) october.plusMinutes(index.toLong()) else july)
	}

	suspend fun choose(series: List<BaseItemDto>, resumable: BaseItemDto? = null, allowRestart: Boolean = true) =
		chooseSeriesFallback(
			resumable = resumable,
			lastPlayed = series.filter { it.userData?.played == true }.maxByOrNull { it.userData!!.lastPlayedDate!! },
			episodesFrom = { startId -> series.dropWhile { it.id != startId } },
			episodesInOrder = { filter ->
				if (filter == ItemFilter.IS_UNPLAYED) series.filter { it.userData?.played != true } else series
			},
			allowRestart = allowRestart,
		)

	test("all played, rewatched up to S1E5: continues with the played S1E6") {
		val series = watchedSeries(rewatchedUpTo = 5)
		choose(series) shouldBe series[5]
		choose(series, allowRestart = false) shouldBe series[5]
	}

	test("all played, last played by date is the finale: restarts at S1E1") {
		val series = watchedSeries(rewatchedUpTo = null).toMutableList()
		series[9] = episode(1, 10, october)
		choose(series) shouldBe series[0]
	}

	test("all played and nothing to continue: no restart for the Resume button") {
		val series = watchedSeries(rewatchedUpTo = null).toMutableList()
		series[9] = episode(1, 10, october)
		choose(series, allowRestart = false) shouldBe null
	}

	test("a newer half watched episode wins over the next one") {
		val series = watchedSeries(rewatchedUpTo = 5)
		val halfWatched = episode(1, 6, october.plusHours(1))
		choose(series, resumable = halfWatched) shouldBe halfWatched
	}

	test("an older half watched episode loses to the rewatch") {
		val series = watchedSeries(rewatchedUpTo = 5)
		choose(series, resumable = episode(1, 2, july)) shouldBe series[5]
	}

	test("finale played, earlier episodes unplayed: first unplayed") {
		val series = (1..10).map { episode(1, it, if (it >= 8) july.plusMinutes(it.toLong()) else null) }
		choose(series) shouldBe series[0]
	}
})

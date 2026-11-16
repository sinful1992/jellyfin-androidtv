package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

private const val TICKS_PER_MINUTE = 600_000_000L

private fun item(
	type: BaseItemKind = BaseItemKind.MOVIE,
	name: String? = "Aladdin",
	year: Int? = 1992,
	ticks: Long? = 90 * TICKS_PER_MINUTE,
	rating: String? = "U",
	stars: Float? = 7.4f,
	backdrop: Boolean = true,
	parentBackdrop: Boolean = false,
	logo: Boolean = false,
	parentLogo: Boolean = false,
	season: Int? = null,
	episode: Int? = null,
	seriesName: String? = null,
	overview: String? = null,
) = BaseItemDto(
	id = UUID.randomUUID(),
	type = type,
	name = name,
	productionYear = year,
	runTimeTicks = ticks,
	officialRating = rating,
	communityRating = stars,
	backdropImageTags = if (backdrop) listOf("bd") else emptyList(),
	parentBackdropItemId = if (parentBackdrop) UUID.randomUUID() else null,
	parentBackdropImageTags = if (parentBackdrop) listOf("pbd") else null,
	imageTags = if (logo) mapOf(ImageType.LOGO to "logo") else null,
	parentLogoItemId = if (parentLogo) UUID.randomUUID() else null,
	parentLogoImageTag = if (parentLogo) "plogo" else null,
	parentIndexNumber = season,
	indexNumber = episode,
	seriesName = seriesName,
	overview = overview,
)

class HomeHeroInfoTests : FunSpec({
	context("meta line") {
		test("a film gives year, runtime, rating and stars") {
			homeHeroMetaOf(item()) shouldBe HomeHeroMeta(
				episode = null,
				year = 1992,
				runtimeMinutes = 90,
				rating = "U",
				stars = 7.4f,
			)
		}

		test("runtime rounds up to the next whole minute") {
			homeHeroMetaOf(item(ticks = 90 * TICKS_PER_MINUTE + 1)).runtimeMinutes shouldBe 91
		}

		test("a series reports zero runtime, which is left out rather than shown as 0m") {
			homeHeroMetaOf(item(type = BaseItemKind.SERIES, ticks = 0)).runtimeMinutes.shouldBeNull()
		}

		test("missing and empty parts are left out") {
			homeHeroMetaOf(item(year = null, ticks = null, rating = "  ", stars = 0f)) shouldBe HomeHeroMeta()
		}

		test("year zero from a bad scrape is left out") {
			homeHeroMetaOf(item(year = 0)).year.shouldBeNull()
		}

		test("an episode gets its season and number") {
			homeHeroMetaOf(item(type = BaseItemKind.EPISODE, season = 2, episode = 5)).episode shouldBe "S2:E5"
		}

		test("an episode with no season still gets its number") {
			homeHeroMetaOf(item(type = BaseItemKind.EPISODE, episode = 3)).episode shouldBe "E3"
		}

		test("an episode with no numbers gets no label") {
			homeHeroMetaOf(item(type = BaseItemKind.EPISODE)).episode.shouldBeNull()
		}

		test("a film with index numbers is not labelled as an episode") {
			homeHeroMetaOf(item(season = 1, episode = 2)).episode.shouldBeNull()
		}
	}

	context("runtime text") {
		test("under an hour") { formatHeroRuntime(48) shouldBe "48m" }
		test("over an hour") { formatHeroRuntime(112) shouldBe "1h 52m" }
		test("exactly an hour") { formatHeroRuntime(60) shouldBe "1h 0m" }
		test("zero") { formatHeroRuntime(0) shouldBe "0m" }
	}

	context("title") {
		test("a film uses its own name") { homeHeroTitleOf(item(name = "Up")) shouldBe "Up" }

		test("an episode uses its series' name") {
			homeHeroTitleOf(item(type = BaseItemKind.EPISODE, name = "Pilot", seriesName = "Firefly")) shouldBe "Firefly"
		}

		test("an episode with a blank series name falls back to its own") {
			homeHeroTitleOf(item(type = BaseItemKind.EPISODE, name = "Pilot", seriesName = " ")) shouldBe "Pilot"
		}

		test("no name at all gives an empty title, not a crash") {
			homeHeroTitleOf(item(name = null)) shouldBe ""
		}
	}

	context("art and logo") {
		test("own backdrop first") {
			homeHeroBackdropOf(item(backdrop = true, parentBackdrop = true))?.tag shouldBe "bd"
		}

		test("parent backdrop when the item has none, as an episode does") {
			homeHeroBackdropOf(item(backdrop = false, parentBackdrop = true))?.tag shouldBe "pbd"
		}

		test("no backdrop anywhere") {
			homeHeroBackdropOf(item(backdrop = false)).shouldBeNull()
		}

		test("own logo first, then the series' logo") {
			homeHeroLogoOf(item(logo = true, parentLogo = true))?.tag shouldBe "logo"
			homeHeroLogoOf(item(parentLogo = true))?.tag shouldBe "plogo"
			homeHeroLogoOf(item()).shouldBeNull()
		}
	}

	context("what the top of the screen shows next") {
		val previous = item(name = "Previous")

		test("a focused card with art replaces what is shown") {
			val next = item(name = "Next")
			homeHeroNextShown(next, previous) shouldBe next
		}

		test("a focused card without art keeps the previous title, so the screen does not flash black") {
			homeHeroNextShown(item(type = BaseItemKind.COLLECTION_FOLDER, backdrop = false), previous) shouldBe previous
		}

		test("nothing focused keeps the previous title") {
			homeHeroNextShown(null, previous) shouldBe previous
		}

		test("nothing shown yet and nothing with art stays empty") {
			homeHeroNextShown(item(backdrop = false), null).shouldBeNull()
		}

		test("an episode with only its series' art is shown") {
			val episode = item(type = BaseItemKind.EPISODE, backdrop = false, parentBackdrop = true)
			homeHeroNextShown(episode, previous) shouldBe episode
		}
	}

	context("overview trimmed at a whole word") {
		val overview = "A street urchin finds a magic lamp and a genie who grants him three wishes"

		test("cut before the word the line ran out in") {
			val cut = trimOverviewAt(overview, overview.indexOf("lamp") + 2).shouldNotBeNull()
			cut shouldBe "A street urchin finds a magic…"
		}

		test("trailing punctuation goes before the ellipsis") {
			trimOverviewAt("One, two, three", 9) shouldBe "One…"
		}

		test("one long word has nowhere to cut") {
			trimOverviewAt("Supercalifragilistic", 10).shouldBeNull()
		}

		test("empty text") {
			trimOverviewAt("", 0).shouldBeNull()
		}

		test("an end past the text is held to the text") {
			trimOverviewAt(overview, 10_000).shouldNotBeNull() shouldEndWith "three…"
		}

		test("an end of zero does not throw") {
			trimOverviewAt(overview, 0).shouldBeNull()
		}

		test("a cut never leaves half a word") {
			for (end in 1..overview.length) {
				val cut = trimOverviewAt(overview, end) ?: continue
				val kept = cut.removeSuffix("…")
				overview.startsWith(kept) shouldBe true
				(overview.length == kept.length || overview[kept.length] == ' ') shouldBe true
				cut shouldNotContain "……"
			}
		}
	}
})

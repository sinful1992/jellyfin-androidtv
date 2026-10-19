package org.jellyfin.androidtv.ui.itemhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDateTime
import java.util.UUID

private fun movie(
	name: String,
	year: Int?,
	type: BaseItemKind = BaseItemKind.MOVIE,
	id: UUID = UUID.randomUUID(),
) = BaseItemDto(
	id = id,
	type = type,
	name = name,
	sortName = name.lowercase(),
	premiereDate = year?.let { LocalDateTime.of(it, 6, 1, 0, 0) },
	productionYear = year,
)

private val List<BaseItemDto>.names get() = map { it.name }

class CollectionMoviesTests : FunSpec({
	test("each set's movies come out in release order, sets in the order given") {
		val potter = listOf(
			movie("Harry Potter and the Goblet of Fire", 2005),
			movie("Harry Potter and the Philosopher's Stone", 2001),
			movie("Harry Potter and the Prisoner of Azkaban", 2004),
			movie("Harry Potter and the Chamber of Secrets", 2002),
		)
		val mib = listOf(
			movie("Men in Black 3", 2012),
			movie("Men in Black", 1997),
			movie("Men in Black II", 2002),
		)

		flattenCollectionMovies(listOf(potter, mib)).names shouldBe listOf(
			"Harry Potter and the Philosopher's Stone",
			"Harry Potter and the Chamber of Secrets",
			"Harry Potter and the Prisoner of Azkaban",
			"Harry Potter and the Goblet of Fire",
			"Men in Black",
			"Men in Black II",
			"Men in Black 3",
		)
	}

	test("a movie in two sets is shown once, with the first set") {
		val shared = UUID.randomUUID()
		val first = listOf(movie("A1", 2001), movie("Shared", 2003, id = shared))
		val second = listOf(movie("Shared", 2003, id = shared), movie("B2", 2005))

		flattenCollectionMovies(listOf(first, second)).names shouldBe listOf("A1", "Shared", "B2")
	}

	test("a movie with no date goes to the end of its own set") {
		val set = listOf(movie("Undated", null), movie("Second", 2010), movie("First", 2000))
		val next = listOf(movie("Next set", 1990))

		flattenCollectionMovies(listOf(set, next)).names shouldBe listOf("First", "Second", "Undated", "Next set")
	}

	test("anything that is not a movie is left out, and empty sets add nothing") {
		val set = listOf(movie("Show", 2000, type = BaseItemKind.SERIES), movie("Film", 2001))

		flattenCollectionMovies(listOf(emptyList(), set, emptyList())).names shouldBe listOf("Film")
	}
})

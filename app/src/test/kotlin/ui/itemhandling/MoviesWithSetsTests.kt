package org.jellyfin.androidtv.ui.itemhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

private fun movie(name: String, year: Int?, type: BaseItemKind = BaseItemKind.MOVIE) = BaseItemDto(
	id = UUID.nameUUIDFromBytes(name.toByteArray()),
	type = type,
	name = name,
	sortName = name.lowercase().removePrefix("the "),
	premiereDate = year?.let { LocalDateTime.of(it, 6, 1, 0, 0) },
	productionYear = year,
)

private val List<BaseItemDto>.names get() = map { it.name }

private fun sets(vararg sets: List<BaseItemDto>) = sets.withIndex().associate { (index, members) ->
	UUID(0, index.toLong()) to members
}

private fun uuid(hex: String) = UUID.fromString(
	"${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
)

private val UUID.hex get() = toString().replace("-", "")

class MoviesWithSetsTests : FunSpec({
	val philosopher = movie("Harry Potter and the Philosopher's Stone", 2001)
	val chamber = movie("Harry Potter and the Chamber of Secrets", 2002)
	val prisoner = movie("Harry Potter and the Prisoner of Azkaban", 2004)
	val potter = listOf(prisoner, chamber, philosopher)
	val gladiator = movie("Gladiator", 2000)
	val hocusPocus = movie("Hocus Pocus", 1993)
	val iceAge = movie("Ice Age", 2002)
	val meltdown = movie("Ice Age: The Meltdown", 2006)
	val buckWild = movie("The Ice Age Adventures of Buck Wild", 2022)
	val iceAges = listOf(buckWild, meltdown, iceAge)
	val library = listOf(gladiator, chamber, philosopher, prisoner, hocusPocus, iceAge, buckWild, meltdown)

	test("a set's movies sit together in release order where its first film would be") {
		orderMoviesWithSets(library, sets(potter, iceAges), descending = false).names shouldBe listOf(
			"Gladiator",
			"Harry Potter and the Philosopher's Stone",
			"Harry Potter and the Chamber of Secrets",
			"Harry Potter and the Prisoner of Azkaban",
			"Hocus Pocus",
			"Ice Age",
			"Ice Age: The Meltdown",
			"The Ice Age Adventures of Buck Wild",
		)
	}

	test("a hidden first film still decides where its set goes") {
		val philosopherFirst = movie("A Philosopher's Stone", 2001)
		val shown = listOf(gladiator, chamber, hocusPocus)

		orderMoviesWithSets(shown, sets(listOf(chamber, philosopherFirst)), descending = false).names shouldBe listOf(
			"Harry Potter and the Chamber of Secrets",
			"Gladiator",
			"Hocus Pocus",
		)
	}

	test("a set with nothing shown is left out, and set movies the grid does not show do not appear") {
		orderMoviesWithSets(listOf(gladiator, chamber), sets(potter, iceAges), descending = false).names shouldBe listOf(
			"Gladiator",
			"Harry Potter and the Chamber of Secrets",
		)
	}

	test("descending reverses the sets and movies, not the release order inside a set") {
		orderMoviesWithSets(library, sets(potter, iceAges), descending = true).names shouldBe listOf(
			"Ice Age",
			"Ice Age: The Meltdown",
			"The Ice Age Adventures of Buck Wild",
			"Hocus Pocus",
			"Harry Potter and the Philosopher's Stone",
			"Harry Potter and the Chamber of Secrets",
			"Harry Potter and the Prisoner of Azkaban",
			"Gladiator",
		)
	}

	test("a movie in two sets goes with the first, and the second set is ordered without it") {
		val first = listOf(chamber, philosopher)
		val second = listOf(philosopher, prisoner)

		orderMoviesWithSets(listOf(prisoner, chamber, philosopher), sets(first, second), descending = false).names shouldBe listOf(
			"Harry Potter and the Philosopher's Stone",
			"Harry Potter and the Chamber of Secrets",
			"Harry Potter and the Prisoner of Azkaban",
		)
		orderMoviesWithSets(listOf(prisoner, chamber, philosopher), sets(second, first), descending = false).names shouldBe listOf(
			"Harry Potter and the Chamber of Secrets",
			"Harry Potter and the Philosopher's Stone",
			"Harry Potter and the Prisoner of Azkaban",
		)
	}

	test("set children that are not movies are ignored and no sets means plain name order") {
		val extra = movie("Harry Potter Behind the Scenes", 1990, type = BaseItemKind.VIDEO)
		orderMoviesWithSets(library, sets(potter + extra), descending = false).names.first { it.orEmpty().startsWith("Harry") } shouldBe
			"Harry Potter and the Philosopher's Stone"
		orderMoviesWithSets(listOf(hocusPocus, gladiator), emptyMap(), descending = false).names shouldBe
			listOf("Gladiator", "Hocus Pocus")
	}

	// A real library is private, so it is not in this repository: point JELLYFIN_ORDER_FIXTURE at an
	// expected-order.json made from one, or the test is skipped.
	val fixtureFile = System.getenv("JELLYFIN_ORDER_FIXTURE")?.let(::File)?.takeIf { it.isFile }

	test("a real library comes out as in its expected-order.json").config(enabled = fixtureFile != null) {
		val fixture = Json.parseToJsonElement(requireNotNull(fixtureFile).readText()).jsonObject
		val input = fixture.getValue("input").jsonObject

		fun JsonObject.toMovie() = BaseItemDto(
			id = uuid(getValue("id").jsonPrimitive.content),
			type = BaseItemKind.MOVIE,
			name = getValue("name").jsonPrimitive.content,
			sortName = getValue("sortName").jsonPrimitive.content,
			premiereDate = this["premiereDate"]?.jsonPrimitive?.contentOrNull?.let { LocalDateTime.parse(it.removeSuffix("Z")) },
			productionYear = this["productionYear"]?.jsonPrimitive?.intOrNull,
		)

		val movieJson = input.getValue("movies").jsonArray.map { it.jsonObject }
		val movies = movieJson.map { it.toMovie() }
		val unwatched = movieJson.filterNot { it.getValue("played").jsonPrimitive.boolean }.map { it.toMovie() }
		val byId = (movies + input.getValue("setOnlyMovies").jsonArray.map { it.jsonObject.toMovie() }).associateBy { it.id }
		val setMembers = input.getValue("sets").jsonArray.map { it.jsonObject }.associate { set ->
			uuid(set.getValue("id").jsonPrimitive.content) to
				set.getValue("members").jsonArray.map { byId.getValue(uuid(it.jsonPrimitive.content)) }
		}
		val expected = fixture.getValue("expected").jsonObject
		fun expectedIds(name: String) = expected.getValue(name).jsonArray.map { it.jsonPrimitive.content }

		orderMoviesWithSets(movies, setMembers, descending = false).map { it.id.hex } shouldBe expectedIds("all")
		orderMoviesWithSets(unwatched, setMembers, descending = false).map { it.id.hex } shouldBe expectedIds("unwatchedOnly")
		orderMoviesWithSets(movies, setMembers, descending = true).map { it.id.hex } shouldBe expectedIds("descending")
	}
})

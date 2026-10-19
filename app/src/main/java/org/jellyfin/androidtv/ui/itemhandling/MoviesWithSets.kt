package org.jellyfin.androidtv.ui.itemhandling

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

private val releaseOrder = compareBy<BaseItemDto, java.time.LocalDateTime?>(nullsLast()) { it.premiereDate }
	.thenBy(nullsLast()) { it.productionYear }
	.thenBy(nullsLast()) { it.sortName }

private val BaseItemDto.sortKey get() = sortName ?: name.orEmpty()

private class MovieGroup(val key: String, val tieId: String, val members: MutableList<BaseItemDto>)

/**
 * A movie library in name order, except that the movies of a box set stay together in release
 * order, where the set's first film would be in name order. Harry Potter 1–8 sit back to back
 * under "Harry Potter and the Philosopher's Stone" rather than spread from Chamber to Prisoner.
 *
 * [movies] are the ones the grid shows, already filtered. [setMembers] are every set's movies,
 * unfiltered, with the sets in name order: a movie in more than one set goes with the first, and a
 * set's place comes from its first film whether that film is shown or not, so marking it watched
 * does not move the set. A set with none of its movies shown is left out. [descending] reverses
 * the order of the movies and sets, not the release order inside a set.
 */
internal fun orderMoviesWithSets(
	movies: List<BaseItemDto>,
	setMembers: Map<UUID, List<BaseItemDto>>,
	descending: Boolean,
): List<BaseItemDto> {
	val setOf = HashMap<UUID, MovieGroup>()
	val positionInSet = HashMap<UUID, Int>()
	for (members in setMembers.values) {
		val full = members
			.filter { it.type == BaseItemKind.MOVIE && it.id !in setOf }
			.sortedWith(releaseOrder)
		val first = full.firstOrNull() ?: continue
		val group = MovieGroup(first.sortKey, first.id.toString(), mutableListOf())
		full.forEachIndexed { index, movie ->
			setOf[movie.id] = group
			positionInSet[movie.id] = index
		}
	}

	val groups = LinkedHashSet<MovieGroup>()
	for (movie in movies) {
		val group = setOf[movie.id] ?: MovieGroup(movie.sortKey, movie.id.toString(), mutableListOf())
		group.members += movie
		groups += group
	}

	val byKey = compareBy<MovieGroup> { it.key }.thenBy { it.tieId }
	return groups
		.sortedWith(if (descending) byKey.reversed() else byKey)
		.flatMap { group -> group.members.sortedBy { positionInSet[it.id] ?: 0 } }
}

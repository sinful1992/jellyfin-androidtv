package org.jellyfin.androidtv.ui.home

import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import kotlin.math.ceil

/** One server tick is 100 ns. */
private const val TICKS_PER_SECOND = 10_000_000L

/**
 * The single line of facts under the title at the top of the home screen.
 *
 * Each part is null when the item does not have it, and a null part is not drawn.
 */
data class HomeHeroMeta(
	/** "S2:E5", for an episode only. */
	val episode: String? = null,
	val year: Int? = null,
	/** Whole minutes, rounded up. Null for zero or no runtime (a series reports zero). */
	val runtimeMinutes: Int? = null,
	/** The parental rating, such as "PG-13". */
	val rating: String? = null,
	/** The community rating out of ten, such as 7.4. */
	val stars: Float? = null,
)

fun homeHeroMetaOf(item: BaseItemDto): HomeHeroMeta = HomeHeroMeta(
	episode = if (item.type == BaseItemKind.EPISODE) episodeLabel(item.parentIndexNumber, item.indexNumber) else null,
	year = item.productionYear?.takeIf { it > 0 },
	runtimeMinutes = item.runTimeTicks
		?.takeIf { it > 0 }
		?.let { ceil(it.toDouble() / TICKS_PER_SECOND / 60).toInt() },
	rating = item.officialRating?.trim()?.takeIf { it.isNotEmpty() },
	stars = item.communityRating?.takeIf { it > 0f },
)

private fun episodeLabel(season: Int?, episode: Int?): String? = when {
	season != null && episode != null -> "S$season:E$episode"
	episode != null -> "E$episode"
	else -> null
}

/** "1h 52m" or "48m": the same shape as the rest of the app's runtimes. */
fun formatHeroRuntime(minutes: Int): String {
	val hours = minutes / 60
	val rest = minutes % 60
	return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
}

/** The art the top of the home screen shows for [item]: its own backdrop, else its series' or film's. */
fun homeHeroBackdropOf(item: BaseItemDto): JellyfinImage? =
	item.itemBackdropImages.firstOrNull() ?: item.parentBackdropImages.firstOrNull()

/** The title's lettering: its own logo, else its series' logo. */
fun homeHeroLogoOf(item: BaseItemDto): JellyfinImage? =
	item.itemImages[ImageType.LOGO] ?: item.parentImages[ImageType.LOGO]

/** The name set in type when there is no logo. An episode is shown under its series' name. */
fun homeHeroTitleOf(item: BaseItemDto): String =
	(if (item.type == BaseItemKind.EPISODE) item.seriesName else null)
		?.takeIf { it.isNotBlank() }
		?: item.name.orEmpty()

/**
 * What the fixed info block describes next: always the focused title, even when it has no art and
 * the picture behind stays on the previous one, so the words never name a title you are not on.
 */
fun homeHeroNextInfo(target: BaseItemDto?, previous: BaseItemDto?): BaseItemDto? = target ?: previous

/**
 * What the top of the home screen shows next.
 *
 * The focused item when it has art to show. A library tile, a folder or an audio track has none, and
 * putting black behind the toolbar for those would flash the screen on every step along a row, so the
 * previous title stays up instead.
 */
fun homeHeroNextShown(target: BaseItemDto?, previous: BaseItemDto?): BaseItemDto? = when {
	target == null -> previous
	homeHeroBackdropOf(target) != null -> target
	else -> previous
}

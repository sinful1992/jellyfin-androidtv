package org.jellyfin.androidtv.util.apiclient

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID
import kotlin.math.ceil

/**
 * Utility class used to collect information about images in Jellyfin API responses to easily pass around the app.
 */
data class JellyfinImage(
	val item: UUID,
	val source: JellyfinImageSource,
	val type: ImageType,
	val tag: String,
	val blurHash: String?,
	val aspectRatio: Float?,
	val index: Int?,
)

/**
 * JPEG quality asked of the server. Every item image asks for one: left out, the server answers a
 * resize at its own default, which for a 200x300 poster is 5-6x the bytes of [PREVIEW].
 */
object ImageQuality {
	/** Everything not being looked at: rows, grids and unfocused cards. */
	const val PREVIEW = 60

	/** What the eye is on: the focused card, the home hero, details and full-screen images. */
	const val FULL = 85
}

/**
 * The heights the app asks for. The server keeps one resized file per exact size it is asked
 * for, and so does the image disk cache here, so asking for a handful of heights instead of each
 * card's own makes the same poster one file in both, warm for the next screen that shows it.
 * The card scales the result down by a few percent.
 */
internal val imageHeightBuckets = intArrayOf(200, 300, 450, 1080)

/**
 * [maxHeight] rounded up to its bucket, and [maxWidth] scaled with it so the shape is kept.
 * Heights past the largest bucket are left as asked.
 */
internal fun bucketImageSize(maxWidth: Int?, maxHeight: Int?): Pair<Int?, Int?> {
	if (maxHeight == null || maxHeight <= 0) return maxWidth to maxHeight
	val bucket = imageHeightBuckets.firstOrNull { it >= maxHeight } ?: return maxWidth to maxHeight
	val width = maxWidth?.let { ceil(it.toDouble() * bucket / maxHeight).toInt() }
	return width to bucket
}

fun JellyfinImage.getUrl(
	api: ApiClient,
	maxWidth: Int? = null,
	maxHeight: Int? = null,
	fillWidth: Int? = null,
	fillHeight: Int? = null,
	quality: Int = ImageQuality.FULL,
): String = when (source) {
	JellyfinImageSource.USER -> api.imageApi.getUserImageUrl(
		userId = item,
		tag = tag,
	)

	else -> {
		val (width, height) = bucketImageSize(maxWidth, maxHeight)
		api.imageApi.getItemImageUrl(
			itemId = item,
			imageType = type,
			tag = tag,
			imageIndex = index,
			maxWidth = width,
			maxHeight = height,
			fillWidth = fillWidth,
			fillHeight = fillHeight,
			quality = quality,
		)
	}
}

enum class JellyfinImageSource {
	ITEM,
	PARENT,
	ALBUM,
	SERIES,
	CHANNEL,
	USER,
	CHAPTER,
}

// UserDto
val UserDto.primaryImage
	get() = primaryImageTag?.let { primaryImageTag ->
		JellyfinImage(
			item = id,
			source = JellyfinImageSource.USER,
			type = ImageType.PRIMARY,
			tag = primaryImageTag,
			blurHash = null,
			aspectRatio = primaryImageAspectRatio?.toFloat(),
			index = null,
		)
	}

// BaseItemDto

val BaseItemDto.itemImages
	get() = imageTags?.mapValues { (type, tag) ->
		JellyfinImage(
			item = id,
			source = JellyfinImageSource.ITEM,
			type = type,
			tag = tag,
			blurHash = imageBlurHashes?.get(type)?.get(tag),
			aspectRatio = if (type == ImageType.PRIMARY) primaryImageAspectRatio?.toFloat() else null,
			index = null,
		)
	}.orEmpty()

val BaseItemDto.itemBackdropImages
	get() = backdropImageTags?.mapIndexed { index, tag ->
		JellyfinImage(
			item = id,
			source = JellyfinImageSource.ITEM,
			type = ImageType.BACKDROP,
			tag = tag,
			blurHash = imageBlurHashes?.get(ImageType.BACKDROP)?.get(tag),
			aspectRatio = null,
			index = index,
		)
	}.orEmpty()

val BaseItemDto.parentImages
	get() = mapOf(
		ImageType.PRIMARY to (parentPrimaryImageItemId to parentPrimaryImageTag),
		ImageType.LOGO to (parentLogoItemId to parentLogoImageTag),
		ImageType.ART to (parentArtItemId to parentArtImageTag),
		ImageType.THUMB to (parentThumbItemId to parentThumbImageTag),
	).mapNotNull { (type, itemAndTag) ->
		itemAndTag.first?.let { item ->
			itemAndTag.second?.let { tag ->
				JellyfinImage(
					item = item,
					source = JellyfinImageSource.PARENT,
					type = type,
					tag = tag,
					blurHash = imageBlurHashes?.get(type)?.get(tag),
					aspectRatio = null,
					index = null,
				)
			}
		}
	}.associateBy { it.type }

val BaseItemDto.parentBackdropImages
	get() = parentBackdropItemId?.let { parentBackdropItemId ->
		parentBackdropImageTags?.mapIndexed { index, tag ->
			JellyfinImage(
				item = parentBackdropItemId,
				source = JellyfinImageSource.PARENT,
				type = ImageType.BACKDROP,
				tag = tag,
				blurHash = imageBlurHashes?.get(ImageType.BACKDROP)?.get(tag),
				aspectRatio = null,
				index = index,
			)
		}
	}.orEmpty()

val BaseItemDto.albumPrimaryImage
	get() = albumPrimaryImageTag?.let { albumPrimaryImageTag ->
		albumId?.let { albumId ->
			JellyfinImage(
				item = albumId,
				source = JellyfinImageSource.ALBUM,
				type = ImageType.PRIMARY,
				tag = albumPrimaryImageTag,
				blurHash = imageBlurHashes?.get(ImageType.PRIMARY)?.get(albumPrimaryImageTag),
				aspectRatio = null,
				index = null,
			)
		}
	}

val BaseItemDto.channelPrimaryImage
	get() = channelPrimaryImageTag?.let { channelPrimaryImageTag ->
		channelId?.let { channelId ->
			JellyfinImage(
				item = channelId,
				source = JellyfinImageSource.CHANNEL,
				type = ImageType.PRIMARY,
				tag = channelPrimaryImageTag,
				blurHash = imageBlurHashes?.get(ImageType.PRIMARY)?.get(channelPrimaryImageTag),
				aspectRatio = null,
				index = null,
			)
		}
	}

val BaseItemDto.seriesPrimaryImage
	get() = seriesPrimaryImageTag?.let { seriesPrimaryImageTag ->
		seriesId?.let { seriesId ->
			JellyfinImage(
				item = seriesId,
				source = JellyfinImageSource.SERIES,
				type = ImageType.PRIMARY,
				tag = seriesPrimaryImageTag,
				blurHash = imageBlurHashes?.get(ImageType.PRIMARY)?.get(seriesPrimaryImageTag),
				aspectRatio = null,
				index = null,
			)
		}
	}

val BaseItemDto.seriesThumbImage
	get() = seriesThumbImageTag?.let { seriesThumbImageTag ->
		seriesId?.let { seriesId ->
			JellyfinImage(
				item = seriesId,
				source = JellyfinImageSource.SERIES,
				type = ImageType.THUMB,
				tag = seriesThumbImageTag,
				blurHash = imageBlurHashes?.get(ImageType.PRIMARY)?.get(seriesThumbImageTag),
				aspectRatio = null,
				index = null,
			)
		}
	}

val BaseItemDto.chapterImages
	get() = chapters?.mapIndexed { index, chapter ->
		JellyfinImage(
			item = id,
			source = JellyfinImageSource.CHAPTER,
			type = ImageType.CHAPTER,
			tag = chapter.imageTag.orEmpty(),
			blurHash = null,
			aspectRatio = null,
			index = index,
		)
	}.orEmpty()

val BaseItemDto.images
	get() = listOfNotNull(
		itemImages.values,
		itemBackdropImages,
		parentImages.values,
		parentBackdropImages,
		listOfNotNull(albumPrimaryImage),
		listOfNotNull(channelPrimaryImage),
		listOfNotNull(seriesPrimaryImage),
		listOfNotNull(seriesThumbImage),
		chapterImages,
	).flatten()

// BaseItemPerson

val BaseItemPerson.primaryImage
	get() = primaryImageTag?.let { primaryImageTag ->
		JellyfinImage(
			item = id,
			source = JellyfinImageSource.ITEM,
			type = ImageType.PRIMARY,
			tag = primaryImageTag,
			blurHash = imageBlurHashes?.get(ImageType.PRIMARY)?.get(primaryImageTag),
			aspectRatio = null,
			index = null,
		)
	}

val BaseItemPerson.images
	get() = listOfNotNull(primaryImage)

// TODO Add SeriesTimerInfoDto once API types are fixed (correct nullability, UUID types) and blurhashes are added

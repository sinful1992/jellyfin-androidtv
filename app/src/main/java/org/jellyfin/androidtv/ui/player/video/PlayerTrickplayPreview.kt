package org.jellyfin.androidtv.ui.player.video

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.request.transformations
import coil3.size.Dimension
import coil3.size.Size
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.composable.rememberQueueEntry
import org.jellyfin.androidtv.util.coil.SubsetTransformation
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickplayApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.TrickplayInfo
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.compose.koinInject
import java.util.UUID
import kotlin.time.Duration

private val PreviewHeight = 100.dp
private val PreviewOffset = 16.dp

/**
 * The tile sheets the server generated for one media source, at a single resolution.
 */
private data class TrickplaySheets(
	val mediaSourceId: UUID,
	val info: TrickplayInfo,
)

/**
 * One thumbnail: the sheet holding it and the region of that sheet to cut out.
 */
private data class TrickplayTile(
	val url: String,
	val x: Int,
	val y: Int,
	val width: Int,
	val height: Int,
)

/**
 * The thumbnail for [position], drawn above the seek bar and following the position along it.
 *
 * Renders nothing when the server has no trickplay images for the item, which is also the case
 * when the item was fetched without the trickplay field.
 */
@Composable
fun PlayerTrickplayPreview(
	position: Duration,
	positionFraction: Float,
	railWidth: Dp,
	modifier: Modifier = Modifier,
	playbackManager: PlaybackManager = koinInject(),
	api: ApiClient = koinInject(),
	imageLoader: ImageLoader = koinInject(),
) {
	val entry by rememberQueueEntry(playbackManager)
	val item = entry?.baseItem ?: return
	val mediaSourceId = entry?.mediaSourceId

	val sheets = remember(item, mediaSourceId) { item.getTrickplaySheets(mediaSourceId) } ?: return
	val tile = remember(sheets, position) { sheets.getTile(api, item.id, position) } ?: return

	val context = LocalContext.current
	val request = remember(tile) {
		ImageRequest.Builder(context).apply {
			data(tile.url)
			size(Size.ORIGINAL)
			maxBitmapSize(Size(Dimension.Undefined, Dimension.Undefined))
			httpHeaders(
				NetworkHeaders.Builder().apply {
					set("Authorization", api.authorizationHeader())
				}.build()
			)
			transformations(SubsetTransformation(tile.x, tile.y, tile.width, tile.height))
		}.build()
	}

	val painter = rememberAsyncImagePainter(model = request, imageLoader = imageLoader)
	val painterState by painter.state.collectAsState()

	val aspectRatio = tile.width.toFloat() / tile.height.toFloat()
	val previewWidth = PreviewHeight * aspectRatio
	// Centre the thumbnail on the position, without letting it run off either end of the rail.
	val offsetX = (railWidth * positionFraction - previewWidth / 2)
		.coerceIn(0.dp, (railWidth - previewWidth).coerceAtLeast(0.dp))

	Box(
		modifier = modifier
			.offset(x = offsetX, y = -(PreviewHeight + PreviewOffset))
			.height(PreviewHeight)
			.aspectRatio(aspectRatio)
			.clip(JellyfinTheme.shapes.extraSmall)
			.background(Color.Black)
	) {
		if (painterState is AsyncImagePainter.State.Success) {
			Image(
				painter = painter,
				contentDescription = null,
				contentScale = ContentScale.Fit,
				modifier = Modifier.aspectRatio(aspectRatio)
			)
		}
	}
}

/**
 * The trickplay images for the media source being played, or the first the item has when that
 * source has none. Only the first resolution the server generated is used, matching the legacy
 * player.
 */
private fun BaseItemDto.getTrickplaySheets(mediaSourceId: String?): TrickplaySheets? {
	val trickplay = trickplay.orEmpty()
	val sourceId = mediaSourceId?.takeIf { trickplay.containsKey(it) } ?: trickplay.keys.firstOrNull()
	val info = trickplay[sourceId]?.values?.firstOrNull() ?: return null

	return TrickplaySheets(
		mediaSourceId = sourceId?.toUUIDOrNull() ?: return null,
		info = info,
	)
}

/**
 * The thumbnail covering [position]. Thumbnails are packed into sheets of
 * [TrickplayInfo.tileWidth] by [TrickplayInfo.tileHeight] images, so the position first picks a
 * thumbnail and that then picks the sheet and the region within it.
 */
private fun TrickplaySheets.getTile(api: ApiClient, itemId: UUID, position: Duration): TrickplayTile? {
	if (info.interval <= 0 || info.tileWidth <= 0 || info.tileHeight <= 0) return null

	val thumbnail = (position.inWholeMilliseconds / info.interval).toInt().coerceAtLeast(0)
	val thumbnailsPerSheet = info.tileWidth * info.tileHeight
	val indexInSheet = thumbnail % thumbnailsPerSheet

	return TrickplayTile(
		url = api.trickplayApi.getTrickplayTileImageUrl(
			itemId = itemId,
			width = info.width,
			index = thumbnail / thumbnailsPerSheet,
			mediaSourceId = mediaSourceId,
		),
		x = (indexInSheet % info.tileWidth) * info.width,
		y = (indexInSheet / info.tileWidth) * info.height,
		width = info.width,
		height = info.height,
	)
}

private fun ApiClient.authorizationHeader() = AuthorizationHeaderBuilder.buildHeader(
	clientInfo.name,
	clientInfo.version,
	deviceInfo.id,
	deviceInfo.name,
	accessToken,
)

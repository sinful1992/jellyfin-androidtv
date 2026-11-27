package org.jellyfin.androidtv.ui.composable

import android.app.ActivityManager
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.content.getSystemService
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.asPainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.util.BlurHashDecoder
import org.koin.compose.koinInject
import kotlin.math.round

/** How long a newly loaded image fades in over its blurhash or placeholder. */
private const val CrossfadeMillis = 100

/** The last image that loaded, so a quality upgrade of the same image can keep it up meanwhile. */
private class LoadedImage(val url: String, val painter: Painter)

/**
 * An image from the network, drawn by Compose itself.
 *
 * This used to host an [ImageView] through an AndroidView. Every layout of such a view makes its
 * AndroidComposeView fire the window's global layout callback, and every AndroidComposeView in the
 * window answers that by working out its position on screen again. A grid of cards, each its own
 * ComposeView with an image in it, then paid cards x cards position updates per frame while
 * scrolling: on the SK4 Pro the Movies grid spent 33 ms of each frame on it (task 198).
 *
 * Shows [blurHash] while [url] loads and [placeholder] when there is no url or it fails. A new
 * [url] that is the same image at another quality keeps the image on screen until the new one is in.
 */
@Composable
fun AsyncImage(
	modifier: Modifier = Modifier,
	url: String? = null,
	blurHash: String? = null,
	placeholder: Drawable? = null,
	aspectRatio: Float = 1f,
	blurHashResolution: Int = 32,
	scaleType: ImageView.ScaleType? = null,
	onLoaded: (() -> Unit)? = null,
) {
	val context = LocalContext.current
	val imageLoader = koinInject<ImageLoader>()
	val (contentScale, alignment) = remember(scaleType) { scaleType.toCompose() }
	val placeholderPainter = remember(placeholder) { placeholder?.asImage()?.asPainter(context) }

	if (url == null) {
		if (placeholderPainter != null) Image(
			painter = placeholderPainter,
			contentDescription = null,
			contentScale = contentScale,
			alignment = alignment,
			modifier = modifier,
		)
		return
	}

	var loaded by remember { mutableStateOf<LoadedImage?>(null) }
	// Only while the url is an upgrade of the loaded one; once the upgrade is in, this is null again.
	val kept = loaded?.takeIf { isSameImage(it.url, url) }?.painter

	val isLowRamDevice = remember(context) { context.getSystemService<ActivityManager>()?.isLowRamDevice == true }
	val blurHashPainter by produceState<Painter?>(null, url, blurHash, aspectRatio, blurHashResolution) {
		value = null
		if (kept != null || blurHash == null || isLowRamDevice || aspectRatio <= 0) return@produceState
		val size = blurHashSize(aspectRatio, blurHashResolution)
		value = withContext(Dispatchers.Default) {
			BlurHashDecoder.decode(blurHash, size.width, size.height)?.let { BitmapPainter(it.asImageBitmap()) }
		}
	}

	// Keyed on the url alone: the crossfade is decided once, when the url arrives.
	val request = remember(url) {
		ImageRequest.Builder(context)
			.data(url)
			// An upgrade swaps in place: fading the sharper copy over the same picture only flickers.
			.crossfade(if (kept != null) 0 else CrossfadeMillis)
			.build()
	}

	val painter = rememberAsyncImagePainter(
		model = request,
		imageLoader = imageLoader,
		placeholder = kept ?: blurHashPainter ?: placeholderPainter,
		error = kept ?: placeholderPainter,
		onSuccess = { state ->
			loaded = LoadedImage(url, state.painter)
			onLoaded?.invoke()
		},
	)

	Image(
		painter = painter,
		contentDescription = null,
		contentScale = contentScale,
		alignment = alignment,
		modifier = modifier,
	)
}

/** The blurhash bitmap size: [resolution] on the short side, the long side by [aspectRatio]. */
internal fun blurHashSize(aspectRatio: Float, resolution: Int) = IntSize(
	width = if (aspectRatio > 1) round(resolution * aspectRatio).toInt() else resolution,
	height = if (aspectRatio >= 1) resolution else round(resolution / aspectRatio).toInt(),
)

/** The Compose scale and alignment that draw like an [ImageView] with this scale type. */
internal fun ImageView.ScaleType?.toCompose(): Pair<ContentScale, Alignment> = when (this) {
	ImageView.ScaleType.CENTER_CROP -> ContentScale.Crop to Alignment.Center
	ImageView.ScaleType.CENTER_INSIDE -> ContentScale.Inside to Alignment.Center
	ImageView.ScaleType.CENTER -> ContentScale.None to Alignment.Center
	ImageView.ScaleType.FIT_XY -> ContentScale.FillBounds to Alignment.Center
	ImageView.ScaleType.FIT_START -> ContentScale.Fit to Alignment.TopStart
	ImageView.ScaleType.FIT_END -> ContentScale.Fit to Alignment.BottomEnd
	// FIT_CENTER, MATRIX (unused here) and no scale type: the ImageView default.
	else -> ContentScale.Fit to Alignment.Center
}

private val qualityParameter = Regex("""([?&])quality=\d+&?""")

/** Two image URLs that differ only in the quality asked for. */
internal fun isSameImage(a: String, b: String?): Boolean {
	if (b == null || a == b) return false
	fun strip(url: String) = url.replace(qualityParameter, "$1").trimEnd('&', '?')
	return strip(a) == strip(b)
}

@Composable
fun blurHashPainter(
	blurHash: String,
	size: IntSize,
	punch: Float = 1f,
): Painter = remember(blurHash, size, punch) {
	val bitmap = BlurHashDecoder.decode(
		blurHash = blurHash,
		width = size.width,
		height = size.height,
		punch = punch,
	)

	BitmapPainter(requireNotNull(bitmap).asImageBitmap())
}

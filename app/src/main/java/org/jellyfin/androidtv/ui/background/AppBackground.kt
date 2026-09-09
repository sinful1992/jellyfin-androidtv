package org.jellyfin.androidtv.ui.background

import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.service.BackgroundService
import org.koin.compose.koinInject

@Composable
private fun AppThemeBackground() {
	val context = LocalContext.current
	val themeBackground = remember(context.theme) {
		val attributes = context.theme.obtainStyledAttributes(intArrayOf(R.attr.defaultBackground))
		val drawable = attributes.getDrawable(0)
		attributes.recycle()

		if (drawable is ColorDrawable) drawable.toBitmap(1, 1).asImageBitmap()
		else drawable?.toBitmap()?.asImageBitmap()
	}

	if (themeBackground != null) {
		Image(
			bitmap = themeBackground,
			contentDescription = null,
			alignment = Alignment.Center,
			contentScale = ContentScale.Crop,
			modifier = Modifier.fillMaxSize()
		)
	} else {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(Color.Black)
		)
	}
}

/**
 * How dark the backdrop should end up once the filter is over it.
 *
 * Everything below exists to land every picture on roughly this, whatever it started at.
 */
private const val BACKDROP_TARGET_LUMINANCE = 0.12f

/**
 * The filter is never taken off entirely, however dark the picture already is: the screens that
 * carry a backdrop set their headings straight over the middle of it.
 */
private const val BACKDROP_FILTER_MIN_ALPHA = 0.40f

/** And never taken to the point where there is no artwork left to see. */
private const val BACKDROP_FILTER_MAX_ALPHA = 0.85f

/**
 * How heavy the filter over a backdrop has to be, given how bright that backdrop is.
 *
 * `background_filter` was a flat 58% for every picture, and a flat anything cannot serve both ends
 * of the range. A dark backdrop was taken most of the way to black, which throws away the artwork
 * for no gain, and a bright one — a cartoon on a bright teal sky, say — stayed bright enough that
 * the detail screen's grey lettering sat on it unreadably. The filter was tuned for a mid-brightness
 * picture and was wrong for everything else.
 *
 * The filter blends towards near-black, so what comes out is roughly `luminance * (1 - alpha)`, and
 * the alpha that lands a given picture on [BACKDROP_TARGET_LUMINANCE] falls out of that directly. A
 * picture already at the target is left alone; one twice as bright is taken down by half.
 *
 * The old 58% is what this returns for a luminance of 0.29, which is about where a photographic
 * backdrop sits — so the ordinary case is unchanged and it is the ends of the range that move.
 */
private fun backdropFilterAlpha(luminance: Float): Float =
	(1f - BACKDROP_TARGET_LUMINANCE / luminance.coerceAtLeast(BACKDROP_TARGET_LUMINANCE))
		.coerceIn(BACKDROP_FILTER_MIN_ALPHA, BACKDROP_FILTER_MAX_ALPHA)

@Composable
fun AppBackground() {
	val backgroundService = koinInject<BackgroundService>()
	val currentBackground by backgroundService.currentBackground.collectAsState()
	val blurBackground by backgroundService.blurBackground.collectAsState()
	val enabled by backgroundService.enabled.collectAsState()

	if (enabled) {
		Box(modifier = Modifier.fillMaxSize()) {
			AnimatedContent(
				targetState = currentBackground,
				transitionSpec = {
					val duration = (BackgroundService.TRANSITION_DURATION.inWholeMilliseconds / 2).toInt()
					fadeIn(tween(durationMillis = duration)) togetherWith fadeOut(snap(delayMillis = duration))
				},
				label = "BackgroundTransition",
			) { background ->
				if (background != null) {
					// The backdrop is scenery behind a screenful of text, and the weight of the
					// filter over it is chosen from how bright this particular picture is.
					val filter = colorResource(R.color.background_filter)
						.copy(alpha = backdropFilterAlpha(background.luminance))

					Image(
						bitmap = background.image,
						contentDescription = null,
						alignment = Alignment.Center,
						contentScale = ContentScale.Crop,
						colorFilter = ColorFilter.tint(filter, BlendMode.SrcAtop),
						modifier = Modifier
							.fillMaxSize()
							.then(if (blurBackground) Modifier.blur(10.dp) else Modifier)
					)
				} else {
					AppThemeBackground()
				}
			}
		}
	}
}

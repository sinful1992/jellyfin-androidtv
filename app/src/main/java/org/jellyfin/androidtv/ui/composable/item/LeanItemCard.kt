package org.jellyfin.androidtv.ui.composable.item

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType

/**
 * What a library grid poster shows over its picture (task 201): the same decisions as
 * [ItemCardBaseItemOverlay]'s StateIndicator, WatchIndicator and ProgressIndicator, as plain values.
 *
 * Progress is the saved one only: the library grid is the Movies screen, where nothing plays behind it,
 * so a grid poster does not follow live playback (a Music grid would need that back).
 */
@Immutable
data class CardOverlay(
	val favorite: Boolean = false,
	val recording: Boolean = false,
	val recordingActive: Boolean = false,
	val watched: Boolean = false,
	val unplayedCount: Int? = null,
	val progress: Float? = null,
)

private val progressMediaTypes = setOf(MediaType.VIDEO, MediaType.BOOK)

fun cardOverlay(item: BaseItemDto, watchedBehavior: WatchedIndicatorBehavior): CardOverlay {
	val recording = item.timerId?.takeIf { item.type == BaseItemKind.LIVE_TV_PROGRAM || item.type == BaseItemKind.PROGRAM } != null

	val watchShown = when (watchedBehavior) {
		WatchedIndicatorBehavior.NEVER -> false
		WatchedIndicatorBehavior.EPISODES_ONLY -> item.type == BaseItemKind.EPISODE
		else -> true
	}
	val played = item.userData?.played == true
	val unplayed = item.userData?.unplayedItemCount?.takeIf { it > 0 }

	return CardOverlay(
		favorite = item.userData?.isFavorite == true,
		recording = recording,
		recordingActive = item.seriesTimerId != null && recording,
		watched = watchShown && played,
		unplayedCount = unplayed.takeIf { watchShown && !played && watchedBehavior != WatchedIndicatorBehavior.HIDE_UNWATCHED },
		progress = if (item.mediaType in progressMediaTypes) {
			item.userData?.playedPercentage?.toFloat()?.div(100f)?.coerceIn(0f, 1f)
		} else null,
	)
}

/**
 * Everything a lean poster paints with, made once per grid instead of once per poster: icon painters,
 * the text measurer for the unplayed count, colours and the card shape.
 */
@Stable
class CardOverlayPaint internal constructor(
	internal val watch: Painter,
	internal val heart: Painter,
	internal val record: Painter,
	internal val textMeasurer: TextMeasurer,
	internal val badgeText: TextStyle,
	internal val badge: Color,
	internal val barBackground: Color,
	internal val barFill: Color,
	internal val scrim: Color,
	internal val ring: Color,
	internal val ringShade: Color,
	internal val surface: Color,
	internal val shape: Shape,
	internal val focusScale: Float,
) {
	internal val watchTint = ColorFilter.tint(badgeText.color)
	internal val heartTint = ColorFilter.tint(Tokens.Color.colorRed500)
	internal val recordTint = ColorFilter.tint(Tokens.Color.colorGrey100)
	internal val recordActiveTint = ColorFilter.tint(Tokens.Color.colorRed600)
}

@Composable
fun rememberCardOverlayPaint(): CardOverlayPaint {
	val watch = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_watch))
	val heart = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_heart))
	val record = rememberVectorPainter(ImageVector.vectorResource(R.drawable.ic_record_series))
	val textMeasurer = rememberTextMeasurer()
	val colors = JellyfinTheme.colorScheme
	val badgeText = JellyfinTheme.typography.badge.copy(color = colors.onBadge)
	val shape = JellyfinTheme.shapes.medium
	val focusScale = rememberCardFocusScale()

	return remember(watch, heart, record, textMeasurer, colors, badgeText, shape, focusScale) {
		CardOverlayPaint(
			watch = watch,
			heart = heart,
			record = record,
			textMeasurer = textMeasurer,
			badgeText = badgeText,
			badge = colors.badge,
			barBackground = colors.rangeControlBackground,
			barFill = colors.rangeControlFill,
			scrim = colors.cardUnfocusedScrim,
			ring = colors.cardFocusRing,
			ringShade = colors.cardFocusRingShade,
			surface = colors.surface,
			shape = shape,
			focusScale = focusScale,
		)
	}
}

/** The spring animateFloatAsState uses, which the focus scale, ring and scrim of [ItemCard] run on. */
private val FocusSpring = spring<Float>(visibilityThreshold = 0.01f)

/** One card's focus, 0 (resting) to 1 (focused). [settled] is a plain flag: it must not recompose. */
private class CardFocus(focused: Boolean) {
	val progress = Animatable(if (focused) 1f else 0f, visibilityThreshold = 0.01f)
	var settled = !focused
}

/**
 * [ItemCard] for library grid posters, with the same look in fewer pieces (task 201): the picture is
 * the one child, and the unfocused shade, badges, progress bar and focus ring are painted in one draw
 * step from [overlay], instead of a Box, Column, Badge, Icon, Text and Seekbar each. On the SK4 Pro,
 * building a new line of 11 posters took 15-19 ms of the main thread, most of it per-node work.
 *
 * Focus runs one animation, and only on a card that has or just lost the focus; it is read in the
 * layer and draw steps, so it redraws the card without recomposing it. A resting card runs nothing.
 */
@Composable
fun LeanItemCard(
	focused: Boolean,
	overlay: CardOverlay,
	paint: CardOverlayPaint,
	modifier: Modifier = Modifier,
	image: @Composable BoxScope.() -> Unit,
) {
	val focus = remember { CardFocus(focused) }
	if (focused || !focus.settled) {
		LaunchedEffect(focused) {
			focus.settled = false
			focus.progress.animateTo(if (focused) 1f else 0f, FocusSpring)
			if (!focused) focus.settled = true
		}
	}

	Box(
		modifier = Modifier
			.cardFocusGrowthRoom(paint.focusScale)
			.then(modifier)
			.graphicsLayer {
				val scale = 1f + (paint.focusScale - 1f) * focus.progress.value
				scaleX = scale
				scaleY = scale
				transformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)
				shape = paint.shape
				clip = true
			}
			.background(paint.surface, paint.shape)
			.drawWithCache {
				val ringPath = Path().apply {
					when (val outline = paint.shape.createOutline(size, layoutDirection, this@drawWithCache)) {
						is Outline.Rectangle -> addRect(outline.rect)
						is Outline.Rounded -> addRoundRect(outline.roundRect)
						is Outline.Generic -> addPath(outline.path)
					}
				}
				val count = overlay.unplayedCount?.let { paint.textMeasurer.measure(it.toString(), paint.badgeText) }

				onDrawWithContent {
					drawContent()
					val t = focus.progress.value
					drawCardOverlay(overlay, paint, t, count)
					if (t > 0f) drawFocusRing(ringPath, paint, t)
				}
			},
	) {
		image()
	}
}

private val Pad = Tokens.Space.spaceXs
private val IconSize = 24.dp
private val BadgeMin = 24.dp
private val WatchIconSize = 12.dp
private val BarHeight = 4.dp

private fun DrawScope.drawCardOverlay(
	overlay: CardOverlay,
	paint: CardOverlayPaint,
	focus: Float,
	count: TextLayoutResult?,
) {
	val pad = Pad.toPx()
	val rtl = layoutDirection == LayoutDirection.Rtl
	// x of a box of [w] at the start (or end) edge, inside the overlay's padding.
	fun startX(w: Float) = if (rtl) size.width - pad - w else pad
	fun endX(w: Float) = if (rtl) pad else size.width - pad - w

	// The unfocused shade, under the badges: they say things about the item, not about the focus.
	val dim = 1f - focus
	if (dim > 0f) drawRect(paint.scrim.copy(alpha = paint.scrim.alpha * dim))

	// Top start: recording, then favourite, a gap apart.
	val icon = IconSize.toPx()
	var y = pad
	if (overlay.recording) {
		drawIcon(paint.record, startX(icon), y, icon, if (overlay.recordingActive) paint.recordActiveTint else paint.recordTint)
		y += icon + pad
	}
	if (overlay.favorite) drawIcon(paint.heart, startX(icon), y, icon, paint.heartTint)

	// Top end: the watched tick, or the unplayed count.
	val badgeMin = BadgeMin.toPx()
	if (overlay.watched) {
		val x = endX(badgeMin)
		drawCircle(paint.badge, radius = badgeMin / 2f, center = Offset(x + badgeMin / 2f, pad + badgeMin / 2f))
		val watch = WatchIconSize.toPx()
		drawIcon(paint.watch, x + (badgeMin - watch) / 2f, pad + (badgeMin - watch) / 2f, watch, paint.watchTint)
	} else if (count != null) {
		val w = maxOf(badgeMin, count.size.width + 2 * pad)
		val h = maxOf(badgeMin, count.size.height + 2 * pad)
		val x = endX(w)
		drawRoundRect(paint.badge, topLeft = Offset(x, pad), size = Size(w, h), cornerRadius = CornerRadius(minOf(w, h) / 2f))
		drawText(count, topLeft = Offset(x + (w - count.size.width) / 2f, pad + (h - count.size.height) / 2f))
	}

	// Bottom: the saved progress, inset by the overlay's padding and the bar's own.
	val progress = overlay.progress
	if (progress != null) {
		val bar = BarHeight.toPx()
		val left = 2 * pad
		val width = size.width - 4 * pad
		val top = size.height - 2 * pad - bar
		val radius = CornerRadius(bar, bar)
		drawRoundRect(paint.barBackground, topLeft = Offset(left, top), size = Size(width, bar), cornerRadius = radius)
		// From the left in either direction, as Seekbar draws it.
		if (progress > 0f) drawRoundRect(paint.barFill, topLeft = Offset(left, top), size = Size(width * progress, bar), cornerRadius = radius)
	}
}

/** [painter] fitted and centred in a [box] square at ([left], [top]), as Icon's ContentScale.Fit does. */
private fun DrawScope.drawIcon(painter: Painter, left: Float, top: Float, box: Float, tint: ColorFilter) {
	val intrinsic = painter.intrinsicSize
	val scale = if (intrinsic == Size.Unspecified || intrinsic.width <= 0f || intrinsic.height <= 0f) 0f
	else minOf(box / intrinsic.width, box / intrinsic.height)
	val w = if (scale == 0f) box else intrinsic.width * scale
	val h = if (scale == 0f) box else intrinsic.height * scale
	translate(left + (box - w) / 2f, top + (box - h) / 2f) {
		with(painter) { draw(Size(w, h), colorFilter = tint) }
	}
}

private fun DrawScope.drawFocusRing(path: Path, paint: CardOverlayPaint, alpha: Float) {
	fun Color.fade() = copy(alpha = this.alpha * alpha)
	clipPath(path) {
		drawPath(path, color = paint.ringShade.fade(), style = Stroke((FocusRingWidth + FocusRingShadeWidth).toPx() * 2))
		drawPath(path, color = paint.ring.fade(), style = Stroke(FocusRingWidth.toPx() * 2))
	}
}

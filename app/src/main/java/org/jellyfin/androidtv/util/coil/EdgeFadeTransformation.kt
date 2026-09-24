package org.jellyfin.androidtv.util.coil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import coil3.size.Size
import coil3.size.pxOrElse
import coil3.transform.Transformation
import kotlin.math.roundToInt

/**
 * Crop a picture to the box it is shown in and fade its left, bottom and top edges into
 * transparency, once, when it is decoded.
 *
 * Baked into the pixels rather than drawn over the view: a gradient laid over the image would have
 * to match whatever is behind it, and masking at draw time needs an offscreen layer on every frame
 * the picture is on screen. This way the view draws one plain bitmap, and the fade meets any theme
 * background. The crop happens here too, because the fade is positioned against the box; fading
 * the uncropped picture and cropping it afterwards would move every edge.
 *
 * All positions are fractions of the box: the left fade runs from transparent at [leftClear] to
 * opaque at [leftSolid], the bottom fade from opaque at [bottomSolid] to transparent at the bottom
 * edge, and the top edge starts at [topAlpha] and is opaque from [topSolid] down.
 */
class EdgeFadeTransformation(
	private val leftClear: Float,
	private val leftSolid: Float,
	private val bottomSolid: Float,
	private val topAlpha: Float,
	private val topSolid: Float,
) : Transformation() {
	override val cacheKey: String = "EdgeFade($leftClear,$leftSolid,$bottomSolid,$topAlpha,$topSolid)"

	override suspend fun transform(input: Bitmap, size: Size): Bitmap {
		val width = size.width.pxOrElse { input.width }
		val height = size.height.pxOrElse { input.height }

		val output = createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(output)

		// Centre crop to the box's shape, worked out from what was actually decoded.
		val scale = maxOf(width.toFloat() / input.width, height.toFloat() / input.height)
		val cropWidth = (width / scale).roundToInt().coerceAtMost(input.width)
		val cropHeight = (height / scale).roundToInt().coerceAtMost(input.height)
		val cropLeft = (input.width - cropWidth) / 2
		val cropTop = (input.height - cropHeight) / 2
		canvas.drawBitmap(
			input,
			Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight),
			Rect(0, 0, width, height),
			Paint(Paint.FILTER_BITMAP_FLAG),
		)

		val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
		val w = width.toFloat()
		val h = height.toFloat()

		mask.shader = easedGradient(w * leftClear, 0f, w * leftSolid, 0f, 0f, 1f)
		canvas.drawRect(0f, 0f, w, h, mask)

		mask.shader = easedGradient(0f, h * bottomSolid, 0f, h, 1f, 0f)
		canvas.drawRect(0f, 0f, w, h, mask)

		mask.shader = easedGradient(0f, 0f, 0f, h * topSolid, topAlpha, 1f)
		canvas.drawRect(0f, 0f, w, h, mask)

		return output
	}

	/**
	 * A linear gradient in alpha only, eased with smoothstep so neither end of the fade shows as a
	 * line. Five stops are enough at these lengths to hide the facets.
	 */
	private fun easedGradient(x0: Float, y0: Float, x1: Float, y1: Float, from: Float, to: Float): LinearGradient {
		val positions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
		val colors = IntArray(positions.size) { index ->
			val t = positions[index]
			val eased = t * t * (3 - 2 * t)
			val alpha = from + (to - from) * eased
			((alpha * 255).roundToInt().coerceIn(0, 255)) shl 24
		}
		return LinearGradient(x0, y0, x1, y1, colors, positions, Shader.TileMode.CLAMP)
	}
}

package org.jellyfin.androidtv.ui.browsing.composable.inforow

import androidx.compose.ui.graphics.Color

/**
 * Colors used in the [BaseItemInfoRow].
 */
object InfoRowColors {
	val Transparent = Color.Transparent to Color.White
	val Default = Color(0xB3FFFFFF) to Color.Black

	/**
	 * For a chip that says what is *available* rather than what the file *is*.
	 *
	 * Subtitles are the case: worth knowing they are there, not worth reading before the picture
	 * and the sound. Every chip drawn at one strength is a row of five equals, which is a way of
	 * saying nothing about any of them. Same pill, half the voice.
	 */
	val Muted = Color(0x66FFFFFF) to Color(0xCC000000)

	val Green = Color(0xB3089562) to Color.White
	val Red = Color(0xB3F2364D) to Color.White
}

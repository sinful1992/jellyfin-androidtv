package org.jellyfin.androidtv.ui.base

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The sizes the app is allowed to set type at.
 *
 * There were thirteen — 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 28, 34 and 45 — each written at the
 * call site that wanted it, and the theme itself defined none of them: [TypographyDefaults.Default]
 * was `TextStyle.Default`, which carries no family, no size and no line height at all. Thirteen
 * sizes across seven steps of scale is not a scale, it is thirteen separate decisions that never
 * met each other, and half of them differ by a single point — a difference nobody sees and everyone
 * has to maintain.
 *
 * Seven steps, each a quarter larger than the one below it, which is close enough to the sizes the
 * app had arrived at that almost nothing changes on screen. What changes is that there is now
 * somewhere to change it.
 */
object TypographySizes {
	/** Dense badges and the smallest labels. Not for anything anybody has to read. */
	val Caption = 11.sp

	/** Titles under cards, buttons, the small print that still has to be legible from a sofa. */
	val Label = 13.sp

	/** Descriptions and anything set as a sentence. */
	val Body = 15.sp

	/** A heading inside a screen. */
	val Subtitle = 18.sp

	/** A heading over a screen. */
	val Title = 22.sp

	/** The name of the thing being played or offered. */
	val Headline = 28.sp

	/** One per screen at most, and most screens have none. */
	val Display = 34.sp
}

/**
 * The typeface the app is set in.
 *
 * Named here, once, rather than left unstated — which is what it was. With nothing set, every piece
 * of type in the app resolved to whatever the television's own default happened to be, so the same
 * build could be set in one face on the Chromecast and another on the Firestick, and neither was a
 * decision anybody made.
 *
 * This does not fix that on its own: [FontFamily.SansSerif] still resolves per device. It makes the
 * choice a single line instead of an absence, so bundling a face — the only thing that would make
 * two televisions agree — is a one-line change here rather than a hunt through 34 call sites. It is
 * left undone deliberately: a font file is a few hundred kilobytes added to a build whose whole
 * point at the moment is taking weight out, and that is a trade to make on purpose or not at all.
 */
val JellyfinFontFamily = FontFamily.SansSerif

object TypographyDefaults {
	val Default: TextStyle = TextStyle(
		fontFamily = JellyfinFontFamily,
		fontSize = TypographySizes.Body,
		lineHeight = 21.sp,
	)

	val Caption: TextStyle = Default.copy(
		fontSize = TypographySizes.Caption,
		lineHeight = 14.sp,
	)
	val Label: TextStyle = Default.copy(
		fontSize = TypographySizes.Label,
		lineHeight = 17.sp,
		fontWeight = FontWeight.W500,
	)
	val Body: TextStyle = Default
	val Subtitle: TextStyle = Default.copy(
		fontSize = TypographySizes.Subtitle,
		lineHeight = 23.sp,
		fontWeight = FontWeight.W600,
	)
	val Title: TextStyle = Default.copy(
		fontSize = TypographySizes.Title,
		lineHeight = 28.sp,
		fontWeight = FontWeight.W600,
	)
	val Headline: TextStyle = Default.copy(
		fontSize = TypographySizes.Headline,
		lineHeight = 34.sp,
		fontWeight = FontWeight.W700,
	)
	val Display: TextStyle = Default.copy(
		fontSize = TypographySizes.Display,
		lineHeight = 40.sp,
		fontWeight = FontWeight.W700,
	)

	// The list components had the only styles the theme did define, and they were a coherent set on
	// their own. Kept, re-expressed on the scale so they sit in it rather than beside it.
	val ListHeader: TextStyle = Body.copy(
		lineHeight = 20.sp,
		fontWeight = FontWeight.W700,
	)
	val ListOverline: TextStyle = Caption.copy(
		lineHeight = 12.sp,
		fontWeight = FontWeight.W600,
		letterSpacing = 0.65.sp,
	)
	val ListHeadline: TextStyle = Label.copy(
		lineHeight = 28.sp,
		fontWeight = FontWeight.W600,
	)
	val ListCaption: TextStyle = Caption.copy(
		fontWeight = FontWeight.W500,
		letterSpacing = 0.1.sp,
	)

	val Badge: TextStyle = Caption.copy(
		fontWeight = FontWeight.W700,
		textAlign = TextAlign.Center,
	)
}

@Immutable
data class Typography(
	val default: TextStyle = TypographyDefaults.Default,

	val caption: TextStyle = TypographyDefaults.Caption,
	val label: TextStyle = TypographyDefaults.Label,
	val body: TextStyle = TypographyDefaults.Body,
	val subtitle: TextStyle = TypographyDefaults.Subtitle,
	val title: TextStyle = TypographyDefaults.Title,
	val headline: TextStyle = TypographyDefaults.Headline,
	val display: TextStyle = TypographyDefaults.Display,

	val listHeader: TextStyle = TypographyDefaults.ListHeader,
	val listOverline: TextStyle = TypographyDefaults.ListOverline,
	val listHeadline: TextStyle = TypographyDefaults.ListHeadline,
	val listCaption: TextStyle = TypographyDefaults.ListCaption,
	val badge: TextStyle = TypographyDefaults.Badge
)

val LocalTypography = staticCompositionLocalOf { Typography() }

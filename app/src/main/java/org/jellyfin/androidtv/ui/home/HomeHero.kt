package org.jellyfin.androidtv.ui.home

import android.widget.ImageView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Icon
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject
import java.util.Locale

/** What the select key does while the hero holds the focus. */
enum class HomeHeroAction {
	Play,
	Details,
}

/** Which of the hero's controls the sideways and select keys belong to. */
enum class HomeHeroFocus {
	Dots,
	Play,
	Details,
}

/** Height of the fixed info block between the toolbar and the rows. The rows scroll under it. */
val HomeHeroPanelHeight = 250.dp

private val HeroPanelBottomPadding = 12.dp
private val HeroButtonsGap = 14.dp
private val HeroButtonHeight = 36.dp

/** The bottom of the info block that holds only the Play/Details buttons: empty while a card row is selected. */
val HomeHeroButtonStrip = HeroPanelBottomPadding + HeroButtonsGap + HeroButtonHeight

/**
 * How far the rows reach up into [HomeHeroButtonStrip]. A selected card row puts its title there,
 * so the focused card's name below it stays on screen; the hero is placed this much lower, so the
 * hero screen looks the same. Kept under the strip, so a row title never covers the info text.
 */
val HomeRowsOverlap = 56.dp

/** How long the hero's dots and buttons take to come back on the way up from the rows. */
private const val ControlsFadeInMillis = 250

/** How far the info block's text may run. Past this a line is scanned, not read. */
private val HeroMeasure = 410.dp

/** The share of the screen height the art covers, from the top. The rest is plain background. */
private const val BackdropHeightFraction = 0.815f

private const val BackdropFadeMillis = 180
private const val InfoFadeMillis = 150
private const val ButtonScaleMillis = 120

private val HeroLogoHeight = 72.dp
private val HeroLogoMaxWidth = 280.dp
private const val HeroOverviewLines = 2

private val HeroDotSize = 8.dp
private val HeroDotCurrentWidth = 22.dp

private val TextDim = Color(0xFFB8B8B8)
private val TextBody = Color(0xFFE8E8E8)
private val StarColor = Color(0xFFFFC107)
private val ButtonIdle = Color.White.copy(alpha = 0.16f)
private val ButtonFocusedContent = Color(0xFF111111)

private val MetaStyle = TextStyle(fontSize = 13.sp)

/**
 * The art behind the top of the home screen, toolbar included.
 *
 * Owned by the home screen and nothing else: it is the one place the app paints a backdrop, so no
 * other screen has to clear it. One request per title at one fixed size, so stepping back to a title
 * is a cache hit. No blur and no colour pass.
 */
@Composable
fun HomeBackdrop(
	item: BaseItemDto?,
	modifier: Modifier = Modifier,
	api: ApiClient = koinInject(),
) {
	val background = JellyfinTheme.colorScheme.background
	val image = remember(item) { item?.let(::homeHeroBackdropOf) }

	BoxWithConstraints(modifier = modifier.fillMaxSize()) {
		val width = constraints.maxWidth
		val artHeight = (constraints.maxHeight * BackdropHeightFraction).toInt()
		val artHeightDp = with(LocalDensity.current) { artHeight.toDp() }

		// Keyed on the image, not the item: a row refresh hands back a new item for the same title,
		// and that must not fade the same picture over itself.
		Crossfade(
			targetState = image,
			animationSpec = tween(BackdropFadeMillis),
			label = "home backdrop",
			modifier = Modifier
				.fillMaxWidth()
				.height(artHeightDp),
		) { shown ->
			if (shown != null) {
				AsyncImage(
					url = shown.getUrl(api, maxWidth = width, maxHeight = artHeight),
					scaleType = ImageView.ScaleType.CENTER_CROP,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}

		// The words sit on the left and the rows at the bottom, so the dark comes from those two sides.
		Box(
			modifier = Modifier
				.matchParentSize()
				.background(
					Brush.horizontalGradient(
						0.00f to background,
						0.22f to background.copy(alpha = 0.92f),
						0.45f to background.copy(alpha = 0.55f),
						0.70f to Color.Transparent,
					)
				)
		)
		Box(
			modifier = Modifier
				.matchParentSize()
				.background(
					Brush.verticalGradient(
						0.42f to Color.Transparent,
						0.66f to background.copy(alpha = 0.75f),
						0.82f to background,
					)
				)
		)
	}
}

/**
 * The fixed block at the top of the home screen: which title, what it is, and, while the hero row is
 * the one selected, what to do about it.
 *
 * It never changes height. The shortlist dots and the buttons fade instead of leaving, so nothing
 * below moves when the viewer goes down into the rows.
 */
@Composable
fun HomeHeroPanel(
	item: BaseItemDto?,
	selection: HomeHeroSelection?,
	heroActive: Boolean,
	focus: HomeHeroFocus,
	focused: Boolean,
	modifier: Modifier = Modifier,
) {
	// The rows scroll back down through the button strip on the way up to the hero, so the buttons
	// fade in behind them instead of appearing under a row title; on the way down they go at once.
	val controlsShown = heroActive && selection != null
	val controlsAlpha by animateFloatAsState(
		targetValue = if (controlsShown) 1f else 0f,
		animationSpec = if (controlsShown) tween(ControlsFadeInMillis) else snap(),
		label = "home hero controls",
	)

	Column(
		modifier = modifier
			.fillMaxWidth()
			.height(HomeHeroPanelHeight)
			.padding(horizontal = dimensionResource(androidx.leanback.R.dimen.lb_browse_padding_start))
			.padding(top = 4.dp, bottom = HeroPanelBottomPadding),
	) {
		Box(modifier = Modifier.height(11.dp).alpha(controlsAlpha)) {
			if (selection != null && selection.count > 1) {
				HeroDots(
					index = selection.index,
					count = selection.count,
					active = focused && focus == HomeHeroFocus.Dots,
				)
			}
		}

		Spacer(Modifier.height(10.dp))

		AnimatedContent(
			targetState = item,
			contentKey = { it?.id },
			transitionSpec = { fadeIn(tween(InfoFadeMillis)) togetherWith fadeOut(tween(InfoFadeMillis)) },
			label = "home hero info",
			modifier = Modifier.weight(1f),
		) { shown ->
			if (shown != null) HeroInfo(item = shown)
		}

		Spacer(Modifier.height(HeroButtonsGap))

		Row(
			horizontalArrangement = Arrangement.spacedBy(9.dp),
			modifier = Modifier.alpha(controlsAlpha),
		) {
			HeroButton(
				icon = ImageVector.vectorResource(R.drawable.ic_play),
				label = stringResource(R.string.lbl_play),
				selected = heroActive && focused && focus == HomeHeroFocus.Play,
			)
			HeroButton(
				icon = ImageVector.vectorResource(R.drawable.ic_info),
				label = stringResource(R.string.home_hero_details),
				selected = heroActive && focused && focus == HomeHeroFocus.Details,
			)
		}
	}
}

@Composable
private fun HeroInfo(item: BaseItemDto) {
	val meta = remember(item) { homeHeroMetaOf(item) }

	Column(
		verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
		modifier = Modifier.fillMaxHeight(),
	) {
		HeroTitle(item = item)
		HeroMetaLine(meta = meta, episodeName = item.name.takeIf { meta.episode != null })
		item.overview?.takeIf { it.isNotBlank() }?.let { overview ->
			HeroOverview(overview = overview, modifier = Modifier.widthIn(max = HeroMeasure))
		}
	}
}

@Composable
private fun HeroTitle(
	item: BaseItemDto,
	api: ApiClient = koinInject(),
) {
	val density = LocalDensity.current
	val logo = remember(item) { homeHeroLogoOf(item) }

	if (logo != null) {
		AsyncImage(
			url = logo.getUrl(api, maxHeight = with(density) { HeroLogoHeight.roundToPx() }),
			scaleType = ImageView.ScaleType.FIT_START,
			modifier = Modifier
				.height(HeroLogoHeight)
				.widthIn(max = HeroLogoMaxWidth)
				.fillMaxWidth(),
		)
	} else {
		Text(
			text = homeHeroTitleOf(item),
			maxLines = 2,
			overflow = TextOverflow.Ellipsis,
			color = Color.White,
			style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.W500, lineHeight = 34.sp),
			modifier = Modifier.widthIn(max = HeroMeasure),
		)
	}
}

/**
 * One line: episode, year, runtime, rating chip, stars, then [trailing] (the details page's "Ends
 * 8:45"). Missing parts are left out, not blanked. Shared with the details page.
 */
@Composable
internal fun HeroMetaLine(
	meta: HomeHeroMeta,
	episodeName: String?,
	trailing: String? = null,
	/**
	 * Format tags (Dolby Vision, TrueHD Atmos, 4K), drawn as outlined chips on a line of their own
	 * under the facts: on one line with an episode, rating and Ends they run past the text column.
	 */
	formats: List<String> = emptyList(),
) {
	if (formats.isEmpty()) HeroFactsLine(meta, episodeName, trailing)
	else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		HeroFactsLine(meta, episodeName, trailing)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			formats.forEach { format ->
				Text(
					text = format,
					color = Color(0xFFE6E6E6),
					style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.W500),
					maxLines = 1,
					modifier = Modifier
						.border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
						.padding(horizontal = 4.5.dp, vertical = 1.dp),
				)
			}
		}
	}
}

@Composable
private fun HeroFactsLine(meta: HomeHeroMeta, episodeName: String?, trailing: String?) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(11.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		meta.episode?.let { label ->
			Text(
				text = if (episodeName.isNullOrBlank()) label else "$label  $episodeName",
				color = Color.White,
				style = MetaStyle,
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
				modifier = Modifier.widthIn(max = 240.dp),
			)
		}
		meta.year?.let { Text(text = it.toString(), color = TextDim, style = MetaStyle) }
		meta.runtimeMinutes?.let { Text(text = formatHeroRuntime(it), color = TextDim, style = MetaStyle) }
		meta.rating?.let { rating ->
			Text(
				text = rating,
				color = Color(0xFFE6E6E6),
				style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.W500),
				modifier = Modifier
					.border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
					.padding(horizontal = 4.5.dp, vertical = 1.dp),
			)
		}
		meta.stars?.let { stars ->
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(
					imageVector = ImageVector.vectorResource(R.drawable.ic_star),
					contentDescription = null,
					tint = StarColor,
					modifier = Modifier.size(11.dp),
				)
				Spacer(Modifier.width(4.dp))
				Text(text = String.format(Locale.ROOT, "%.1f", stars), color = Color.White, style = MetaStyle)
			}
		}
		trailing?.let { Text(text = it, color = TextDim, style = MetaStyle, maxLines = 1) }
	}
}

/** Where the viewer is in the shortlist. The current one is drawn long so it reads across a room. */
@Composable
private fun HeroDots(
	index: Int,
	count: Int,
	active: Boolean,
) {
	Row(
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier.fillMaxHeight(),
	) {
		repeat(count) { position ->
			val current = position == index
			val color = when {
				current && active -> Color.White
				current -> Color.White.copy(alpha = 0.75f)
				active -> Color.White.copy(alpha = 0.5f)
				else -> Color.White.copy(alpha = 0.3f)
			}
			Box(
				modifier = Modifier
					.height(if (active) 10.dp else HeroDotSize)
					.width(if (current) HeroDotCurrentWidth else if (active) 10.dp else HeroDotSize)
					.background(color, CircleShape)
			)
		}
	}
}

/**
 * The opening of the description, cut at a whole word.
 *
 * Laid out once in full, then set again cut back to the last space before the end of the last line
 * that was actually laid out. The identity check stops the second layout starting a third.
 */
@Composable
private fun HeroOverview(
	overview: String,
	modifier: Modifier = Modifier,
) {
	var shown by remember(overview) { mutableStateOf(overview) }

	Text(
		text = shown,
		maxLines = HeroOverviewLines,
		overflow = TextOverflow.Ellipsis,
		color = TextBody,
		style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
		onTextLayout = { layout ->
			if (shown === overview && layout.hasVisualOverflow && layout.lineCount > 0) {
				val lastLine = (HeroOverviewLines - 1).coerceAtMost(layout.lineCount - 1)
				val end = layout.getLineEnd(lastLine, visibleEnd = true)
				val cut = trimOverviewAt(overview, end)
				if (cut != null) shown = cut
			}
		},
		modifier = modifier,
	)
}

/** [overview] cut back to the last whole word before [end], with an ellipsis; null if no space to cut at. */
internal fun trimOverviewAt(overview: String, end: Int): String? {
	if (overview.isEmpty()) return null
	val lastSpace = overview.lastIndexOf(' ', (end - 1).coerceIn(0, overview.length - 1))
	if (lastSpace <= 0) return null
	return overview.substring(0, lastSpace).trimEnd(' ', ',', ';', ':', '-', '—') + "…"
}

/**
 * A button told whether it is the selected one. The hero takes the focus as one view and moves
 * between its controls on the arrow keys, so these cannot read a focus state of their own.
 */
@Composable
private fun HeroButton(
	icon: ImageVector,
	label: String,
	selected: Boolean,
) {
	val scale by animateFloatAsState(
		targetValue = if (selected) 1.05f else 1f,
		animationSpec = tween(ButtonScaleMillis),
		label = "home hero button",
	)
	val container = if (selected) Color.White else ButtonIdle
	val content = if (selected) ButtonFocusedContent else Color.White

	Row(
		horizontalArrangement = Arrangement.spacedBy(7.dp),
		verticalAlignment = Alignment.CenterVertically,
		modifier = Modifier
			.graphicsLayer {
				scaleX = scale
				scaleY = scale
			}
			.height(HeroButtonHeight)
			.background(container, RoundedCornerShape(18.dp))
			.padding(PaddingValues(horizontal = 17.dp)),
	) {
		Icon(
			imageVector = icon,
			contentDescription = null,
			tint = content,
			modifier = Modifier.size(15.dp),
		)
		Text(
			text = label,
			color = content,
			style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W500),
		)
	}
}

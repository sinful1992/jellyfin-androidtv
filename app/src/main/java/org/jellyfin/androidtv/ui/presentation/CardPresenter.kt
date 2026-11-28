package org.jellyfin.androidtv.ui.presentation

import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.composable.AsyncImage
import org.jellyfin.androidtv.ui.composable.item.ItemCard
import org.jellyfin.androidtv.ui.composable.item.ItemCardBaseItemOverlay
import org.jellyfin.androidtv.ui.composable.item.ItemPreview
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.BaseRowType
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem
import org.jellyfin.androidtv.util.ImageHelper
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.ImageQuality
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentImages
import org.jellyfin.androidtv.util.apiclient.seriesThumbImage
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.design.Tokens
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType as SdkImageType
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.unit.Density
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior
import org.jellyfin.androidtv.ui.composable.item.CardOverlayPaint
import org.jellyfin.androidtv.ui.composable.item.LeanItemCard
import org.jellyfin.androidtv.ui.composable.item.cardOverlay
import org.jellyfin.androidtv.ui.composable.item.rememberCardOverlayPaint
import java.util.UUID

// JvmOverloads keeps the four-argument form the Java callers construct this with, now that there is
// a fifth parameter with a default behind it.
class CardPresenter @JvmOverloads constructor(
	val showInfo: Boolean,
	val imageType: ImageType,
	val staticHeight: Int,
	val uniformAspect: Boolean,
	/**
	 * One box for every card in the row, whatever artwork the items carry.
	 *
	 * A row that leaves this null sizes each card from its own artwork, so a series poster and an
	 * episode still end up different widths and different heights standing next to each other, and
	 * the focused one grows over whatever is beside it. Setting a shape here gives the row a single
	 * silhouette to read along, at the cost of cropping artwork that is not that shape.
	 */
	val fixedAspectRatio: Float? = null,
	/**
	 * A card that shows only its picture and progress: no watched, favourite or count badges, and
	 * its name only while it has the focus. For the home screen, where the art above already says
	 * what the focused card is and a row of badges is noise.
	 */
	val quiet: Boolean = false,
) : Presenter() {
	constructor(showInfo: Boolean, imageType: ImageType, staticHeight: Int) : this(showInfo, imageType, staticHeight, false)
	constructor(showInfo: Boolean, staticHeight: Int) : this(showInfo, ImageType.POSTER, staticHeight)
	constructor(showInfo: Boolean) : this(showInfo, 150)
	constructor() : this(true)

	override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
		val view = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
			setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
			setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
			isFocusable = true
			isFocusableInTouchMode = true

			setOnLongClickListener {
				context.getActivity()?.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU)) ?: false
			}
		}

		return CardViewHolder(view)
	}

	/**
	 * Lets a card that comes in from the pool compose its body (picture, labels, badges) 1-3 frames
	 * after its bind, keeping its last size meanwhile (see [BodyStagger]). For a grid that lays out
	 * a line beyond its edges (BrowseGridFragment), so the late bodies come in off screen; a row on
	 * Home would show them arriving at its edge, so it stays off there.
	 */
	var staggerBody: Boolean = false

	/** Counts staggered binds, so the cards of one entering line get different [BodyStagger] delays. */
	private var bindCount = 0

	override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
		if (viewHolder !is CardViewHolder) return
		if (item !is BaseRowItem) return

		viewHolder.bind(item)
	}

	override fun onUnbindViewHolder(viewHolder: ViewHolder) {
		if (viewHolder !is CardViewHolder) return

		viewHolder.unbind()
	}

	private inner class CardViewHolder(composeView: ComposeView) : ViewHolder(composeView) {
		private val _item = MutableStateFlow<BoundCard?>(null)
		private val _focused = MutableStateFlow(false)
		private val memo = CardSizeMemo()

		init {
			composeView.setContent {
				val bound by _item.collectAsState()
				val focused by _focused.collectAsState()

				CardViewHolderContent(
					bound = bound,
					focused = focused,
					showInfo = showInfo,
					imageType = imageType,
					staticHeight = staticHeight,
					uniformAspect = uniformAspect,
					fixedAspectRatio = fixedAspectRatio,
					quiet = quiet,
					memo = memo,
				)
			}

			_focused.value = view.isFocused
			composeView.onFocusChangeListener = { _, focused -> _focused.value = focused }
		}

		fun bind(item: BaseRowItem) {
			val hold = if (view.width > 0 && view.height > 0) IntSize(view.width, view.height) else null
			val delay = bodyDelayFrames(
				stagger = staggerBody,
				onScreen = view.isAttachedToWindow,
				previousId = _item.value?.item?.itemId,
				newId = item.itemId,
				hasSize = hold != null,
			) { bodyDelayFor(bindCount++) }
			_item.value = BoundCard(item = item, bodyDelayFrames = delay, holdSize = hold)
			_focused.value = view.isFocused
		}

		/**
		 * Keeps the last item: a card back in the pool stays composed and card-sized, so its next bind
		 * recomposes it with new values instead of building every node again. Clearing it emptied the
		 * card (0x0) and the next bind rebuilt it and grew it back, which is a layout the whole grid
		 * answers: 11 cards on the frame a new row slid in, 27-46 ms on the SK4 Pro (task 198).
		 */
		fun unbind() {
			_focused.value = false
		}
	}
}

/**
 * One bind of a card to [item]. Equal only to itself: [BaseRowItem.equals] compares item ids, which
 * chapters of one film share and grid buttons lack, so a StateFlow of items would drop the second of
 * two such binds, and a card kept across unbind (task 198) would go on showing the first.
 */
internal class BoundCard(
	val item: BaseRowItem,
	val bodyDelayFrames: Int = 0,
	/** The card's size in px from its last bind, kept while the body waits. */
	val holdSize: IntSize? = null,
) {
	override fun equals(other: Any?) = this === other
	override fun hashCode() = System.identityHashCode(this)
}

/** How many times a card was composed as a held, empty box, and the last size held (tests read them). */
@androidx.annotation.VisibleForTesting
internal var heldBodies = 0

@androidx.annotation.VisibleForTesting
internal var lastHeldSize: IntSize? = null

/** The card size ([DpSize] of the artwork box) the holder's last full composition used. */
internal class CardSizeMemo {
	var cardSize: DpSize? = null
}

/**
 * Frames each card waits before composing its body, in bind order. A line of cards comes in on one
 * scroll frame; composing all of them there took 35-58 ms of the SK4 Pro's main thread (task 199).
 * Spread over three frames, each frame gets about a third. Meanwhile the card keeps the exact pixel
 * size of its last bind, and only when its new artwork box is the same size as the last one, so the
 * grid never lays out again when the body arrives.
 */
internal val BodyStagger = intArrayOf(1, 2, 3)

internal fun bodyDelayFor(bindIndex: Int) = BodyStagger[Math.floorMod(bindIndex, BodyStagger.size)]

/**
 * How many frames a bind waits for its body. Only a card from the pool or cache in a staggered
 * presenter waits: one on screen (a refresh, DiffUtil change) or bound again to the same item (new
 * data after details or playback) keeps its picture up, and one that never had a size has nothing
 * to hold. [next] is asked only when the card waits, so refreshes don't move the bind order.
 */
internal inline fun bodyDelayFrames(
	stagger: Boolean,
	onScreen: Boolean,
	previousId: java.util.UUID?,
	newId: java.util.UUID?,
	hasSize: Boolean,
	next: () -> Int,
): Int {
	if (!stagger || onScreen || !hasSize) return 0
	if (previousId != null && previousId == newId) return 0
	return next()
}

/** True once [bound]'s card has waited its [BoundCard.bodyDelayFrames]; resets on each new bind. */
@Composable
internal fun rememberBodyReady(bound: BoundCard?): Boolean {
	var ready by remember(bound) { mutableStateOf(bound == null || bound.bodyDelayFrames <= 0) }
	LaunchedEffect(bound) {
		if (bound == null || ready) return@LaunchedEffect
		repeat(bound.bodyDelayFrames) { withFrameNanos { } }
		ready = true
	}
	return ready
}

private data class BaseRowItemDisplayConfig(
	val image: JellyfinImage?,
	val iconRes: Int,
	val aspectRatio: Float,
	val overrideShowInfo: Boolean? = null,
	val scaleType: ImageView.ScaleType? = null,
)

/** How long a focused title holds still before it starts scrolling. */
private const val TitleMarqueeDelayMillis = 1200

/**
 * Scroll the title of the focused card, once there has been a chance to read the start of it.
 *
 * [basicMarquee] only moves text that overflows, so this does nothing to the titles that fit. The
 * delay is the difference between a row that scrolls at you the instant focus lands on it and one
 * that shows you the beginning of the name first.
 *
 * One pass, then it stops. A marquee that keeps going redraws the screen on every frame for as
 * long as the card holds focus, which on a TV sitting on a page is indefinitely.
 */
private fun Modifier.titleMarquee() = basicMarquee(
	iterations = 1,
	initialDelayMillis = TitleMarqueeDelayMillis,
)

/**
 * The best wide image for this item, for rows that draw every card in the same landscape box.
 *
 * Episodes lead with their own primary image: it is a still from that episode and already wide, and
 * it says more than the series artwork every other episode in the row would also be showing. Unless
 * the user has asked for series thumbnails, which is a preference about exactly this and wins.
 * Everything else works down from a dedicated thumbnail through the backdrops. Falling off the end
 * of the chain leaves the poster, which is the point at which the box has to crop.
 */
private fun BaseRowItem.getLandscapeImage(): JellyfinImage? {
	val item = baseItem ?: return null

	return when (item.type) {
		BaseItemKind.EPISODE -> (if (preferParentThumb) item.parentImages[SdkImageType.THUMB] ?: item.seriesThumbImage else null)
			?: item.itemImages[SdkImageType.PRIMARY]
			?: item.parentImages[SdkImageType.THUMB]
			?: item.seriesThumbImage
			?: item.parentBackdropImages.firstOrNull()

		else -> item.itemImages[SdkImageType.THUMB]
			?: item.itemBackdropImages.firstOrNull()
			?: item.parentImages[SdkImageType.THUMB]
			?: item.parentBackdropImages.firstOrNull()
	}
}

private fun BaseRowItem.getDisplayConfig(
	imageType: ImageType,
	uniformAspect: Boolean,
	fixedAspectRatio: Float?,
): BaseRowItemDisplayConfig {
	val config = getArtworkDisplayConfig(imageType, uniformAspect)
	if (fixedAspectRatio == null) return config

	// The row has already decided what shape its cards are, so neither the artwork's own ratio nor
	// the uniformAspect rule that squares off music and people gets a say. Only the picture changes
	// with it: a wide box asks for wide artwork, and settling for the poster is the last resort.
	return config.copy(
		aspectRatio = fixedAspectRatio,
		image = when {
			fixedAspectRatio > 1f -> getLandscapeImage() ?: config.image
			else -> config.image
		},
	)
}

private fun BaseRowItem.getArtworkDisplayConfig(imageType: ImageType, uniformAspect: Boolean): BaseRowItemDisplayConfig = when (baseRowType) {
	BaseRowType.BaseItem -> {
		val preferSeriesPoster = this is BaseItemDtoBaseRowItem && preferSeriesPoster
		val primaryAspectRatio = baseItem?.primaryImageAspectRatio?.toFloat()
		val defaultAspectRatio = when {
			preferParentThumb && (baseItem?.parentThumbItemId != null || baseItem?.seriesThumbImageTag != null) -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			baseItem?.type == BaseItemKind.EPISODE && primaryAspectRatio != null -> primaryAspectRatio
			baseItem?.type == BaseItemKind.EPISODE && (baseItem.parentThumbItemId != null || baseItem.seriesThumbImageTag != null) -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			baseItem?.type == BaseItemKind.USER_VIEW -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
			else -> primaryAspectRatio ?: ImageHelper.ASPECT_RATIO_7_9.toFloat()
		}

		val base = BaseRowItemDisplayConfig(
			aspectRatio = when (imageType) {
				ImageType.BANNER -> ImageHelper.ASPECT_RATIO_BANNER.toFloat()
				ImageType.THUMB -> ImageHelper.ASPECT_RATIO_16_9.toFloat()
				else -> defaultAspectRatio
			},
			image = getImage(imageType),
			iconRes = R.drawable.ic_clapperboard,
		)

		when (baseItem?.type) {
			BaseItemKind.AUDIO, BaseItemKind.MUSIC_ALBUM -> base.copy(
				iconRes = R.drawable.ic_music_album,
				aspectRatio = if (uniformAspect || base.aspectRatio < 0.8f) 1f else base.aspectRatio,
			)

			BaseItemKind.PERSON,
			BaseItemKind.MUSIC_ARTIST -> base.copy(
				iconRes = R.drawable.ic_user,
				aspectRatio = if (uniformAspect || base.aspectRatio < 0.8f) 1f else base.aspectRatio,
			)

			BaseItemKind.SEASON, BaseItemKind.SERIES -> base.copy(
				aspectRatio = if (imageType == ImageType.POSTER) ImageHelper.ASPECT_RATIO_2_3.toFloat() else base.aspectRatio,
				iconRes = R.drawable.ic_tv
			)

			BaseItemKind.EPISODE -> when (preferSeriesPoster) {
				true -> base.copy(
					aspectRatio = ImageHelper.ASPECT_RATIO_2_3.toFloat(),
					iconRes = R.drawable.ic_tv
				)

				false -> base.copy(
					aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
					iconRes = R.drawable.ic_tv,
					overrideShowInfo = true,
				)
			}

			BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW -> base.copy(
				aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
				iconRes = R.drawable.ic_folder,
			)

			BaseItemKind.FOLDER, BaseItemKind.GENRE, BaseItemKind.MUSIC_GENRE -> base.copy(
				iconRes = R.drawable.ic_folder,
			)

			BaseItemKind.PHOTO -> base.copy(
				iconRes = R.drawable.ic_photo
			)

			BaseItemKind.PHOTO_ALBUM, BaseItemKind.PLAYLIST -> base.copy(
				iconRes = R.drawable.ic_folder
			)

			BaseItemKind.MOVIE, BaseItemKind.VIDEO -> base.copy(
				aspectRatio = when (imageType) {
					ImageType.POSTER -> ImageHelper.ASPECT_RATIO_2_3.toFloat()
					else -> base.aspectRatio
				},
				iconRes = R.drawable.ic_clapperboard,
			)

			else -> base
		}
	}

	BaseRowType.Person -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_7_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_user,
	)

	BaseRowType.Chapter -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_16_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_clapperboard,
	)

	BaseRowType.GridButton -> BaseRowItemDisplayConfig(
		aspectRatio = ImageHelper.ASPECT_RATIO_7_9.toFloat(),
		image = getImage(imageType),
		iconRes = R.drawable.ic_clapperboard,
	)
}

/** How long the focus rests on a card before its image is loaded again at full quality. */
private val FullQualityFocusDelay = 300.milliseconds

/**
 * One card of the Compose library grid (LibraryGrid): the same body the leanback grid's cards draw,
 * without the pool, the stagger or the held size, which only the leanback grid needs.
 *
 * The binding is kept while [item] is the same instance and replaced when it is a new one: items
 * compare by id, so a refreshed item (new watched state, same id) must still give a new binding.
 */
@Composable
internal fun GridCardContent(
	item: BaseRowItem,
	focused: Boolean,
	imageType: ImageType,
	staticHeight: Int,
	env: GridCardEnv?,
) {
	val holder = remember { arrayOfNulls<BoundCard>(1) }
	val bound = gridBinding(holder, item)

	if (env != null && isLeanGridItem(item)) {
		LeanGridCardContent(bound, focused, imageType, staticHeight, env)
	} else {
		CardViewHolderContent(
			bound = bound,
			focused = focused,
			showInfo = false,
			imageType = imageType,
			staticHeight = staticHeight,
			uniformAspect = true,
			fixedAspectRatio = null,
		)
	}
}

/**
 * True for a grid card drawn as a [LeanItemCard]: an item with a title-less poster (films, sets,
 * series). Folders, photos, people and grid buttons show a title or an icon and keep the full card.
 * LibraryGrid gives the two kinds their own contentType, so neither is reused as the other.
 */
internal fun isLeanGridItem(item: BaseRowItem): Boolean =
	item.baseItem != null && !item.showCardInfoOverlay && item !is GridButtonBaseRowItem

/** What every grid card shares, made once per grid (task 201) rather than looked up per card. */
@Stable
class GridCardEnv internal constructor(
	internal val api: ApiClient,
	internal val watchedBehavior: WatchedIndicatorBehavior,
	internal val paint: CardOverlayPaint,
	/** A preview url built ahead off the main thread (LibraryGridSource.previewUrl), or null. */
	internal val previewUrl: (UUID?) -> String?,
)

@Composable
internal fun rememberGridCardEnv(previewUrl: (UUID?) -> String?): GridCardEnv {
	val api = koinInject<ApiClient>()
	val userPreferences = koinInject<UserPreferences>()
	val watchedBehavior = userPreferences[UserPreferences.watchedIndicatorBehavior]
	val paint = rememberCardOverlayPaint()
	return remember(api, watchedBehavior, paint, previewUrl) { GridCardEnv(api, watchedBehavior, paint, previewUrl) }
}

/** The card's box: the row's static height, at the artwork's (or display config's) aspect ratio. */
private fun cardSize(item: BaseRowItem, aspectRatio: Float, staticHeight: Int, fixedAspectRatio: Float?): DpSize = when {
	// A fixed shape is the row's decision and outranks the item's: the two default heights
	// below are what makes a landscape card shorter than the portrait one beside it.
	fixedAspectRatio != null || item.staticHeight -> DpSize(staticHeight.dp * aspectRatio, staticHeight.dp)
	aspectRatio > 1f -> DpSize(130.dp * aspectRatio, 130.dp)
	else -> DpSize(150.dp * aspectRatio, 150.dp)
}

private fun BaseRowItemDisplayConfig.cardAspectRatio(): Float = aspectRatio.takeIf { it >= 0.1f }
	?: image?.aspectRatio?.takeIf { it >= 0.1f } ?: 1f

/**
 * The preview url a lean grid card loads for [item], or null when it has no lean poster: the same
 * size and quality the card asks for itself, so LibraryGridSource can build it ahead on a background
 * thread (task 201). Pure maths on its inputs.
 */
internal fun gridPreviewUrl(item: BaseRowItem, imageType: ImageType, staticHeight: Int, density: Density, api: ApiClient): String? {
	if (!isLeanGridItem(item)) return null
	val config = item.getDisplayConfig(imageType, uniformAspect = true, fixedAspectRatio = null)
	val image = config.image ?: return null
	val size = cardSize(item, config.cardAspectRatio(), staticHeight, fixedAspectRatio = null)
	return with(density) {
		image.getUrl(api, maxWidth = size.width.roundToPx(), maxHeight = size.height.roundToPx(), quality = ImageQuality.PREVIEW)
	}
}

/**
 * A film's grid poster in fewer pieces ([LeanItemCard], task 201): the picture, and everything over
 * it painted in one step. It skips what only other screens need: the leanback grid's stagger and held
 * size, the title and subtitle (a lean poster shows none), the live playback listeners, and a focus
 * job on every card (only the focused one waits to load full quality).
 */
@Composable
private fun LeanGridCardContent(
	bound: BoundCard,
	focused: Boolean,
	imageType: ImageType,
	staticHeight: Int,
	env: GridCardEnv,
) {
	val item = bound.item
	val baseItem = item.baseItem ?: return
	val density = LocalDensity.current
	val displayConfig = remember(bound, imageType) { item.getDisplayConfig(imageType, uniformAspect = true, fixedAspectRatio = null) }
	val image = displayConfig.image
	val aspectRatio = displayConfig.cardAspectRatio()
	val size = cardSize(item, aspectRatio, staticHeight, fixedAspectRatio = null)
	val overlay = remember(bound, env.watchedBehavior) { cardOverlay(baseItem, env.watchedBehavior) }

	// Cards load at preview quality. One the focus rests on for a moment is loaded again at full
	// quality, over the preview, and keeps it. Only a focused card runs that wait.
	var fullQuality by remember(bound) { mutableStateOf(false) }
	if (focused && !fullQuality) {
		LaunchedEffect(bound) {
			delay(FullQualityFocusDelay)
			fullQuality = true
		}
	}

	LeanItemCard(
		focused = focused,
		overlay = overlay,
		paint = env.paint,
		modifier = Modifier.size(size),
	) {
		if (image != null) {
			val maxWidth = with(density) { size.width.roundToPx() }
			val maxHeight = with(density) { size.height.roundToPx() }
			val url = remember(bound, image, fullQuality, maxWidth, maxHeight, env) {
				if (fullQuality) image.getUrl(env.api, maxWidth = maxWidth, maxHeight = maxHeight, quality = ImageQuality.FULL)
				else env.previewUrl(baseItem.id)
					?: image.getUrl(env.api, maxWidth = maxWidth, maxHeight = maxHeight, quality = ImageQuality.PREVIEW)
			}
			AsyncImage(
				url = url,
				blurHash = image.blurHash,
				aspectRatio = aspectRatio,
				scaleType = displayConfig.scaleType ?: ImageView.ScaleType.CENTER_CROP,
				modifier = Modifier.fillMaxSize(),
			)
		} else {
			Image(
				painter = painterResource(displayConfig.iconRes),
				contentDescription = null,
				modifier = Modifier
					.fillMaxSize(0.4f)
					.align(Alignment.Center)
			)
		}
	}
}

/** The binding in [holder] while it is for this very [item] instance, else a new one stored there. */
internal fun gridBinding(holder: Array<BoundCard?>, item: BaseRowItem): BoundCard {
	val current = holder[0]
	if (current != null && current.item === item) return current
	return BoundCard(item).also { holder[0] = it }
}

@Composable
@Stable
private fun CardViewHolderContent(
	bound: BoundCard?,
	focused: Boolean,
	showInfo: Boolean,
	imageType: ImageType,
	staticHeight: Int,
	uniformAspect: Boolean,
	fixedAspectRatio: Float?,
	quiet: Boolean = false,
	memo: CardSizeMemo = remember { CardSizeMemo() },
) {
	val context = LocalContext.current
	val localDensity = LocalDensity.current
	val item = bound?.item

	// Keyed on the binding, not the item: items compare by id, so two chapters of one film (or two
	// grid buttons) are "equal", and a reused card would keep the first one's name and picture.
	val displayConfig = remember(bound, imageType, uniformAspect, fixedAspectRatio) {
		item?.getDisplayConfig(imageType, uniformAspect, fixedAspectRatio)
	}
	if (item == null || displayConfig == null) return

	val image = displayConfig.image
	val aspectRatio = displayConfig.cardAspectRatio()
	val size = cardSize(item, aspectRatio, staticHeight, fixedAspectRatio)

	// Until its turn (BodyStagger), a card from the pool is an empty box of its last exact size, as
	// long as its artwork box is the same size as last time; otherwise it is built at once.
	val bodyReady = rememberBodyReady(bound)
	val hold = bound.holdSize
	if (!bodyReady && hold != null && memo.cardSize == size) {
		SideEffect { heldBodies++; lastHeldSize = hold }
		Box(Modifier.layout { _, _ -> layout(hold.width, hold.height) {} })
		return
	}
	SideEffect { memo.cardSize = size }

	val title = remember(bound, context) { item.getCardName(context) }
	val subtitle = remember(bound, context) { item.getSubText(context) }
	val usePreview = displayConfig.overrideShowInfo ?: showInfo

	// Cards load at preview quality. One the focus rests on for a moment is loaded again at full
	// quality, over the preview, and keeps it: going back to the preview would only fetch again.
	var fullQuality by remember(bound) { mutableStateOf(false) }
	LaunchedEffect(bound, focused) {
		if (focused && !fullQuality) {
			delay(FullQualityFocusDelay)
			fullQuality = true
		}
	}

	val card = @Composable {
		ItemCard(
			image = {
				if (image != null) {
					val api = koinInject<ApiClient>()
					val maxWidth = with(localDensity) { size.width.roundToPx() }
					val maxHeight = with(localDensity) { size.height.roundToPx() }
					val url = remember(bound, image, fullQuality, maxWidth, maxHeight, api) {
						image.getUrl(
							api,
							maxWidth = maxWidth,
							maxHeight = maxHeight,
							quality = if (fullQuality) ImageQuality.FULL else ImageQuality.PREVIEW,
						)
					}
					AsyncImage(
						url = url,
						blurHash = image.blurHash,
						aspectRatio = aspectRatio,
						scaleType = displayConfig.scaleType ?: ImageView.ScaleType.CENTER_CROP,
						modifier = Modifier
							.fillMaxSize()
					)
				} else if (item is GridButtonBaseRowItem && item.gridButton.imageRes != null) {
					Image(
						painter = painterResource(item.gridButton.imageRes),
						contentDescription = null,
						modifier = Modifier
							.fillMaxSize()
					)
				} else {
					Image(
						painter = painterResource(displayConfig.iconRes),
						contentDescription = null,
						modifier = Modifier
							.fillMaxSize(0.4f)
							.align(Alignment.Center)
					)
				}
			},
			overlay = {
				val showInfo = !usePreview && item.showCardInfoOverlay && (!quiet || focused)
				item.baseItem?.let { baseItem ->
					ItemCardBaseItemOverlay(
						item = baseItem,
						badges = !quiet,
						footer = {
							if (showInfo && title != null) {
								val focusModifier = if (focused) Modifier.titleMarquee() else Modifier

								Box(
									modifier = Modifier
										.fillMaxWidth()
										.background(Tokens.Color.colorBluegrey900.copy(alpha = 0.6f), JellyfinTheme.shapes.extraSmall),
								) {
									Text(
										text = title,
										maxLines = 1,
										overflow = TextOverflow.Ellipsis,
										textAlign = TextAlign.Center,
										color = Tokens.Color.colorWhite,
										modifier = Modifier
											.then(focusModifier)
											.padding(Tokens.Space.spaceXs),
									)
								}
							}
						}
					)
				}
			},
			focused = focused,
			modifier = Modifier
				.size(size)
		)
	}

	if (usePreview) {
		// A quiet card keeps the space its labels take, so the row's height never changes under the
		// focus, and only shows them while focused.
		val labelAlpha = if (quiet && !focused) 0f else 1f
		val focusModifier = (if (focused) Modifier.titleMarquee() else Modifier).alpha(labelAlpha)

		ItemPreview(
			card = { card() },
			title = title?.let { text ->
				{
					Text(
						text = text,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						textAlign = TextAlign.Center,
						modifier = Modifier.then(focusModifier),
					)
				}
			},
			subtitle = subtitle?.let { text ->
				{
					Text(
						text = text,
						maxLines = 1,
						overflow = TextOverflow.Ellipsis,
						textAlign = TextAlign.Center,
						modifier = Modifier.then(focusModifier),
					)
				}
			},
			focused = focused,
		)
	} else {
		card()
	}
}

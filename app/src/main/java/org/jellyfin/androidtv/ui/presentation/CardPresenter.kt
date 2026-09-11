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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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
		private val _item = MutableStateFlow<BaseRowItem?>(null)
		private val _focused = MutableStateFlow(false)

		init {
			composeView.setContent {
				val item by _item.collectAsState()
				val focused by _focused.collectAsState()

				CardViewHolderContent(
					item = item,
					focused = focused,
					showInfo = showInfo,
					imageType = imageType,
					staticHeight = staticHeight,
					uniformAspect = uniformAspect,
					fixedAspectRatio = fixedAspectRatio,
				)
			}

			_focused.value = view.isFocused
			composeView.onFocusChangeListener = { _, focused -> _focused.value = focused }
		}

		fun bind(item: BaseRowItem) {
			_item.value = item
			_focused.value = view.isFocused
		}

		fun unbind() {
			_item.value = null
			_focused.value = false
		}
	}
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
 */
private fun Modifier.titleMarquee() = basicMarquee(
	iterations = Int.MAX_VALUE,
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

@Composable
@Stable
private fun CardViewHolderContent(
	item: BaseRowItem?,
	focused: Boolean,
	showInfo: Boolean,
	imageType: ImageType,
	staticHeight: Int,
	uniformAspect: Boolean,
	fixedAspectRatio: Float?,
) {
	val context = LocalContext.current
	val localDensity = LocalDensity.current

	val title = remember(item, context) { item?.getCardName(context) }
	val subtitle = remember(item, context) { item?.getSubText(context) }
	val displayConfig = remember(item, imageType, uniformAspect, fixedAspectRatio) {
		item?.getDisplayConfig(imageType, uniformAspect, fixedAspectRatio)
	}
	if (item == null || displayConfig == null) return

	val image = displayConfig.image
	val aspectRatio = displayConfig.aspectRatio.takeIf { it >= 0.1f }
		?: image?.aspectRatio?.takeIf { it >= 0.1f } ?: 1f

	val size = when {
		// A fixed shape is the row's decision and outranks the item's: the two default heights
		// below are what makes a landscape card shorter than the portrait one beside it.
		fixedAspectRatio != null || item.staticHeight -> DpSize(staticHeight.dp * aspectRatio, staticHeight.dp)
		aspectRatio > 1f -> DpSize(130.dp * aspectRatio, 130.dp)
		else -> DpSize(150.dp * aspectRatio, 150.dp)
	}

	val usePreview = displayConfig.overrideShowInfo ?: showInfo

	val card = @Composable {
		ItemCard(
			image = {
				if (image != null) {
					val api = koinInject<ApiClient>()
					AsyncImage(
						url = image.getUrl(
							api,
							maxWidth = with(localDensity) { size.width.roundToPx() },
							maxHeight = with(localDensity) { size.height.roundToPx() },
						),
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
				val showInfo = !usePreview && item.showCardInfoOverlay
				item.baseItem?.let { baseItem ->
					ItemCardBaseItemOverlay(
						item = baseItem,
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
		val focusModifier = if (focused) Modifier.titleMarquee() else Modifier

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

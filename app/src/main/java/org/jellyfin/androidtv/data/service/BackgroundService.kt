package org.jellyfin.androidtv.data.service

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.constant.BackdropBehavior
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A backdrop, and how bright it turned out to be.
 *
 * The brightness travels with the picture because the filter laid over it has to be chosen from it,
 * and it is measured once here — where the image is decoded, off the main thread — rather than
 * being worked out again on every frame that draws it.
 */
data class Backdrop(
	val image: ImageBitmap,
	/** Mean relative luminance across the whole picture, 0f for black and 1f for white. */
	val luminance: Float,
)

class BackgroundService(
	private val context: Context,
	private val jellyfin: Jellyfin,
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val imageLoader: ImageLoader,
) {
	companion object {
		val SLIDESHOW_DURATION = 30.seconds
		val TRANSITION_DURATION = 800.milliseconds

		/** The size backdrops are decoded at. The screen they are drawn on, not the source file. */
		private const val BACKDROP_WIDTH = 1920
		private const val BACKDROP_HEIGHT = 1080

		// A mean does not need every pixel. Nine lines spread down the frame is a stratified sample
		// of the whole picture for a fiftieth of the reads, and reading whole lines keeps it to nine
		// getPixels calls rather than one that copies eight megabytes.
		private const val LUMINANCE_SAMPLE_ROWS = 9

		// Rec. 709. Green carries most of what the eye reads as brightness, blue almost none.
		private const val RED_WEIGHT = 0.2126
		private const val GREEN_WEIGHT = 0.7152
		private const val BLUE_WEIGHT = 0.0722
		private const val MAX_CHANNEL_VALUE = 255.0

		// Pulling the channels out of a packed ARGB int.
		private const val RED_SHIFT = 16
		private const val GREEN_SHIFT = 8
		private const val CHANNEL_MASK = 0xFF

		/** What a picture is assumed to be when it cannot be measured: the old fixed filter's tuning. */
		private const val DEFAULT_LUMINANCE = 0.29f
	}

	// Async
	private val scope = MainScope()
	private var loadBackgroundsJob: Job? = null
	private var updateBackgroundTimerJob: Job? = null
	private var lastBackgroundTimerUpdate = 0L

	// Current background data
	private var _backgrounds = emptyList<Backdrop>()
	private var _currentIndex = 0
	private var _currentBackground = MutableStateFlow<Backdrop?>(null)
	private var _blurBackground = MutableStateFlow(false)
	private var _enabled = MutableStateFlow(true)
	val currentBackground get() = _currentBackground.asStateFlow()
	val blurBackground get() = _blurBackground.asStateFlow()
	val enabled get() = _enabled.asStateFlow()

	/**
	 * Use all available backdrops from [baseItem] as background.
	 */
	fun setBackground(baseItem: BaseItemDto?) {
		val backdropBehavior = userPreferences[UserPreferences.backdropBehavior]

		// Check if item is set and backgrounds are enabled
		if (baseItem == null || backdropBehavior == BackdropBehavior.DISABLED)
			return clearBackgrounds()

		// Enable blur for backdrops
		_blurBackground.value = backdropBehavior == BackdropBehavior.BACKDROP_WITH_BLUR

		// Get all backdrop urls
		val backdropUrls = (baseItem.itemBackdropImages + baseItem.parentBackdropImages)
			.map { it.getUrl(api) }
			.toSet()

		loadBackgrounds(backdropUrls)
	}

	/**
	 * Use splashscreen from [server] as background.
	 */
	fun setBackground(server: Server) {
		// Check if item is set and backgrounds are enabled
		if (userPreferences[UserPreferences.backdropBehavior] == BackdropBehavior.DISABLED)
			return clearBackgrounds()

		// Check if splashscreen is enabled in (cached) branding options
		if (!server.splashscreenEnabled)
			return clearBackgrounds()

		// Disable blur on splashscreen
		_blurBackground.value = false

		// Manually grab the backdrop URL
		val api = jellyfin.createApi(baseUrl = server.address)
		val splashscreenUrl = api.imageApi.getSplashscreenUrl()

		loadBackgrounds(setOf(splashscreenUrl))
	}

	/**
	 * Decode one backdrop, at screen size rather than at whatever the server happens to return.
	 *
	 * Without a bound the request decodes at the source resolution, which for a backdrop is
	 * routinely larger than the screen it is about to be drawn on — every pixel of the difference
	 * paid for in decode time and in heap.
	 *
	 * The decode is asked for in software so that [measureLuminance] can read it, and handed to the
	 * GPU afterwards. That ordering is the whole point: it replaces a second decode of the same
	 * file, which a trace on a Chromecast measured at 19-344 ms against the 63-541 ms this one
	 * costs. Scaling a decode down does not make it cheap — the JPEG is parsed either way — so the
	 * thumbnail that used to be fetched purely to be measured was costing a third to a half of the
	 * picture it was measuring, on every focus step in every grid in the app.
	 */
	private suspend fun loadBackground(url: String): Backdrop? {
		val decoded = imageLoader
			.execute(
				request = ImageRequest.Builder(context)
					.data(url)
					.size(BACKDROP_WIDTH, BACKDROP_HEIGHT)
					// Readable, so the brightness comes off this decode rather than another one.
					.allowHardware(false)
					.build()
			)
			.image?.toBitmap() ?: return null

		val luminance = measureLuminance(decoded)

		return Backdrop(image = decoded.toHardwareBitmapOrSelf().asImageBitmap(), luminance = luminance)
	}

	/**
	 * Move the picture into graphics memory, on the platforms that have somewhere to put it.
	 *
	 * Backdrops are held for the length of a selection — one for the screen and the rest for a
	 * slideshow — and at 1920x1080 each is eight megabytes of heap it would rather not be. A
	 * hardware bitmap is also what the GPU wants for something redrawn on every frame, which is why
	 * this is Coil's default and why the request above turns it off only for as long as it takes to
	 * read the pixels.
	 *
	 * The copy can fail when graphics memory is short, and returns null rather than throwing; the
	 * software bitmap is a perfectly good fallback, so nothing is lost but the heap.
	 */
	private fun Bitmap.toHardwareBitmapOrSelf(): Bitmap {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return this

		return copy(Bitmap.Config.HARDWARE, false) ?: this
	}

	/**
	 * How bright [bitmap] is overall, between 0f for black and 1f for white.
	 *
	 * Measured from the picture that is about to be drawn, over [LUMINANCE_SAMPLE_ROWS] lines spread
	 * down it rather than every pixel of it — a mean does not need all two million, and the sparse
	 * read is what keeps this off the cost of the decode it follows.
	 *
	 * Rec. 709 weights on the sRGB values as they are stored, without converting back to linear light
	 * first. That makes this a perceptual average rather than a photometric one, which is what is
	 * wanted here: the question being asked is whether lettering will read against it.
	 *
	 * Anything unexpected returns [DEFAULT_LUMINANCE], which is the brightness the old fixed filter
	 * was tuned for — so a picture that cannot be measured is treated exactly as every picture used
	 * to be, rather than left unreadable or blacked out. A hardware bitmap is the case that matters:
	 * `getPixels` on one throws `IllegalStateException` outright, and this runs for every backdrop on
	 * every screen, so getting it wrong would take the app down.
	 */
	private fun measureLuminance(bitmap: Bitmap): Float {
		if (bitmap.config == Bitmap.Config.HARDWARE) return DEFAULT_LUMINANCE
		if (bitmap.width < 1 || bitmap.height < 1) return DEFAULT_LUMINANCE

		val rows = LUMINANCE_SAMPLE_ROWS.coerceAtMost(bitmap.height)
		val row = IntArray(bitmap.width)
		var total = 0.0

		for (index in 0 until rows) {
			// Centres of equal bands, so the sample is spread over the frame rather than bunched at
			// one edge of it, and never falls outside the picture.
			val y = (((index * 2 + 1) * bitmap.height) / (rows * 2)).coerceIn(0, bitmap.height - 1)
			bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)

			for (pixel in row) {
				total += RED_WEIGHT * ((pixel shr RED_SHIFT) and CHANNEL_MASK) +
					GREEN_WEIGHT * ((pixel shr GREEN_SHIFT) and CHANNEL_MASK) +
					BLUE_WEIGHT * (pixel and CHANNEL_MASK)
			}
		}

		return (total / (rows * bitmap.width) / MAX_CHANNEL_VALUE).toFloat()
	}

	private fun loadBackgrounds(backdropUrls: Set<String>) {
		if (backdropUrls.isEmpty()) return clearBackgrounds()

		// Re-enable backgrounds if disabled
		_enabled.value = true

		// Cancel current loading job
		loadBackgroundsJob?.cancel()
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			// The first one on its own, and on the screen before the rest are even asked for.
			//
			// This used to fetch and decode every backdrop the item had before publishing any of
			// them, and setBackground fires on every focus step in every grid, folder, search
			// result and detail screen in the app. So holding a direction key down across a row
			// started N decodes per cell and cancelled them mid-flight on the next one, and
			// nothing appeared until the last image of the set resolved.
			//
			// Only index 0 is ever shown to begin with. The rest exist for a 30 second slideshow
			// that a focus step cancels long before it fires, so they are worth loading only once
			// the viewer has stopped moving — which is exactly what deferring them to after the
			// first has been published achieves, since the job is cancelled outright by the next
			// selection.
			val first = loadBackground(backdropUrls.first())

			_backgrounds = listOfNotNull(first)
			_currentIndex = 0
			update()

			if (backdropUrls.size > 1) {
				_backgrounds = _backgrounds + backdropUrls.drop(1).mapNotNull { loadBackground(it) }

				// Only to start the slideshow timer, which update() leaves cancelled while there
				// is a single background. The picture on screen is index 0 either way.
				update()
			}
		}
	}

	fun clearBackgrounds() {
		loadBackgroundsJob?.cancel()

		// Re-enable backgrounds if disabled
		_enabled.value = true

		if (_backgrounds.isEmpty()) return

		_backgrounds = emptyList()
		update()
	}

	/**
	 * Disable the showing of backgrounds until any function manipulating the backgrounds is called.
	 */
	fun disable() {
		_enabled.value = false
	}

	internal fun update() {
		val now = Instant.now().toEpochMilli()
		if (lastBackgroundTimerUpdate > now - TRANSITION_DURATION.inWholeMilliseconds)
			return setTimer((lastBackgroundTimerUpdate - now).milliseconds + TRANSITION_DURATION, false)

		lastBackgroundTimerUpdate = now

		// Get next background to show
		if (_currentIndex >= _backgrounds.size) _currentIndex = 0

		// Set background
		_currentBackground.value = _backgrounds.getOrNull(_currentIndex)

		// Set timer for next background
		if (_backgrounds.size > 1) setTimer()
		else updateBackgroundTimerJob?.cancel()
	}

	private fun setTimer(updateDelay: Duration = SLIDESHOW_DURATION, increaseIndex: Boolean = true) {
		updateBackgroundTimerJob?.cancel()
		updateBackgroundTimerJob = scope.launch {
			delay(updateDelay)

			if (increaseIndex) _currentIndex++

			update()
		}
	}
}

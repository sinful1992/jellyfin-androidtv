package org.jellyfin.androidtv.data.service

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
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
	}

	// Async
	private val scope = MainScope()
	private var loadBackgroundsJob: Job? = null
	private var updateBackgroundTimerJob: Job? = null
	private var lastBackgroundTimerUpdate = 0L

	// Current background data
	private var _backgrounds = emptyList<ImageBitmap>()
	private var _currentIndex = 0
	private var _currentBackground = MutableStateFlow<ImageBitmap?>(null)
	private var _blurBackground = MutableStateFlow(false)
	private var _plainBackground = MutableStateFlow(false)

	/**
	 * What [plainBackground] becomes once the picture it was asked for is the one on the screen.
	 *
	 * Held back rather than published straight away because the two have to change together. The
	 * pictures arrive whenever they finish loading, and a flag that changed on the asking would
	 * strip the blur off whatever was still showing and put it back again when the new one landed.
	 */
	private var _pendingPlain = false
	private var _enabled = MutableStateFlow(true)
	val currentBackground get() = _currentBackground.asStateFlow()
	val blurBackground get() = _blurBackground.asStateFlow()

	/**
	 * Whether the picture is being shown for its own sake rather than as something to read over.
	 *
	 * Everywhere else the backdrop is scenery behind a screenful of text, and is blurred and dimmed
	 * until it cannot compete with it. The home hero is the one place where the picture is the point
	 * — it is the artwork of the title being put forward, and there is nothing else on that half of
	 * the screen — so it is shown as it is and the lettering is given a wash of its own instead.
	 *
	 * Set by whoever asks for the background, which means every ordinary call turns it back off and
	 * no screen can be left wearing it.
	 */
	val plainBackground get() = _plainBackground.asStateFlow()
	val enabled get() = _enabled.asStateFlow()

	/**
	 * Use all available backdrops from [baseItem] as background.
	 *
	 * [plain] asks for the picture unblurred and undimmed, for a screen that carries its own
	 * lettering. See [plainBackground].
	 */
	@JvmOverloads
	fun setBackground(baseItem: BaseItemDto?, plain: Boolean = false) {
		val backdropBehavior = userPreferences[UserPreferences.backdropBehavior]

		// Check if item is set and backgrounds are enabled
		if (baseItem == null || backdropBehavior == BackdropBehavior.DISABLED)
			return clearBackgrounds()

		// Enable blur for backdrops
		_blurBackground.value = backdropBehavior == BackdropBehavior.BACKDROP_WITH_BLUR
		_pendingPlain = plain

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
		_pendingPlain = false

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
	 */
	private suspend fun loadBackground(url: String) = imageLoader
		.execute(
			request = ImageRequest.Builder(context)
				.data(url)
				.size(BACKDROP_WIDTH, BACKDROP_HEIGHT)
				.build()
		)
		.image?.toBitmap()?.asImageBitmap()

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

		// Both, because this can return below without ever reaching the update that pairs them.
		_pendingPlain = false
		_plainBackground.value = false

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
		_plainBackground.value = _pendingPlain

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

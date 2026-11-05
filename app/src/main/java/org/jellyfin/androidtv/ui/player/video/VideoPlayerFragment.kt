package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.DisplayLinkMonitor
import org.jellyfin.androidtv.util.DisplayModeSwitcher
import org.jellyfin.playback.core.PlaybackEvent
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStreamType
import org.koin.android.ext.android.inject
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class VideoPlayerFragment : Fragment() {
	companion object {
		const val EXTRA_POSITION: String = "position"
	}

	private val videoQueueManager by inject<VideoQueueManager>()
	private val playbackManager by inject<PlaybackManager>()
	private val navigationRepository by inject<NavigationRepository>()
	private val api by inject<ApiClient>()
	private val userPreferences by inject<UserPreferences>()

	private var displayLinkMonitor: DisplayLinkMonitor? = null
	private var displayModeSwitcher: DisplayModeSwitcher? = null
	private var initialModeSwitch: Deferred<Unit>? = null
	private var playbackStarted = false
	private var unpauseOnResume = true
	private var leaving = false

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Create a queue from the items added to the legacy video queue. The start position travels
		// with the queue so the backend can apply it as it prepares the item.
		val startPosition = arguments?.getInt(EXTRA_POSITION)?.milliseconds ?: Duration.ZERO
		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(
			api,
			videoQueueManager.getCurrentVideoQueue(),
			false,
			startPosition,
		)
		Timber.i("Created a queue with ${queueSupplier.items.size} items, starting at $startPosition")
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier)

		// Switch the display mode before playback starts, so the HDMI re-sync happens before the
		// audio passthrough and the display-link monitors start.
		val switcher = DisplayModeSwitcher(requireActivity(), userPreferences[UserPreferences.refreshRateSwitchingBehavior])
		displayModeSwitcher = switcher
		val firstItem = queueSupplier.items.firstOrNull()
		initialModeSwitch = lifecycleScope.async {
			runCatching { switcher.switchFor(withVideoStream(firstItem)) }
				.onFailure { Timber.w(it, "Display mode switch failed") }
			Unit
		}
		switchModePerEntry(switcher)

		awaitPlaybackStart()
		reportPlaybackEvents()

		// Pause player until the initial resume
		playbackManager.state.pause()
	}

	/** The item with its media streams, fetched when the queue's copy came without them. */
	private suspend fun withVideoStream(item: BaseItemDto?): BaseItemDto? {
		if (item == null) return null
		if (item.mediaStreams?.any { it.type == MediaStreamType.VIDEO } == true) return item
		return runCatching { api.userLibraryApi.getItem(item.id).content }.getOrNull()
	}

	/** Later queue entries (the next episode) can have another frame rate. */
	private fun switchModePerEntry(switcher: DisplayModeSwitcher) {
		lifecycleScope.launch {
			playbackManager.queue.entry
				.mapNotNull { it?.baseItem }
				.distinctUntilChanged { old, new -> old.id == new.id }
				.drop(1)
				.collect { item ->
					initialModeSwitch?.await()
					displayLinkMonitor?.stop()
					runCatching { switcher.switchFor(withVideoStream(item)) }
						.onFailure { Timber.w(it, "Display mode switch failed") }
					if (view != null && !leaving) displayLinkMonitor = startDisplayLinkMonitor()
				}
		}
	}

	/**
	 * Tell the user about the things playback passes over in silence.
	 *
	 * An entry with no resolvable stream is skipped, and without this the queue simply jumped an
	 * episode with nothing said - indistinguishable from the episode not being there.
	 *
	 * Collected while started, so a message cannot arrive over whatever replaced the player.
	 */
	private fun reportPlaybackEvents() {
		lifecycleScope.launch {
			lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
				playbackManager.events.collect { event ->
					when (event) {
						is PlaybackEvent.EntryUnplayable -> {
							val name = event.entry.baseItem?.name
							val message = when (name) {
								null -> getString(R.string.msg_cannot_play)
								else -> getString(R.string.msg_skipped_unplayable_item, name)
							}
							Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
						}
					}
				}
			}
		}
	}

	/**
	 * Wait for playback to start, and for the queue to run out afterwards.
	 *
	 * The resume position is no longer applied here. It travels with the queue entry and the
	 * backend applies it as it prepares the item, which is what removed a seek that could be
	 * dropped: this waited for the first PLAYING state on a conflated [kotlinx.coroutines.flow
	 * .StateFlow], buffering reports as PAUSED, and a stream that stalls the instant it starts can
	 * pass through PLAYING before this collector is scheduled — leaving the seek unapplied and the
	 * item playing from its beginning.
	 *
	 * The first PLAYING state is still awaited because it is what tells [onPause] whether a paused
	 * player was paused by the user or has simply not started yet.
	 *
	 * Waiting for the queue to run out is here because nothing else leaves the player when it does
	 * and it would otherwise sit on a black screen.
	 */
	private fun awaitPlaybackStart() {
		lifecycleScope.launch {
			playbackManager.state.playState.first { it == PlayState.PLAYING }
			playbackStarted = true

			// The queue clears its entry when the last one finishes. Stopping the player clears it
			// too, so only act while the player is still the screen being shown.
			playbackManager.queue.entry.first { it == null }
			if (!isResumed) return@launch

			Timber.i("Queue ended, leaving the player")
			leavePlayer()
		}
	}

	/**
	 * Stop playback and return to whatever opened the player. Stopping clears the queue, which is
	 * itself a reason to leave, so this only ever runs once.
	 */
	private fun leavePlayer() {
		if (leaving) return
		leaving = true

		playbackManager.state.stop()

		if (navigationRepository.canGoBack) navigationRepository.goBack()
		else navigationRepository.reset(Destinations.home)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		BaseScreen {
			VideoPlayerScreen()
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		viewLifecycleOwner.lifecycleScope.launch {
			initialModeSwitch?.await()
			displayLinkMonitor = startDisplayLinkMonitor()
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()

		displayLinkMonitor?.stop()
		displayLinkMonitor = null
	}

	/**
	 * Stop playback when the display link drops, which happens when the television is switched off.
	 *
	 * Televisions that keep hotplug detect asserted while powered off produce no display state
	 * change, no hotplug event and no CEC standby message, so playback otherwise continues
	 * indefinitely into a dark room. See [DisplayLinkMonitor] for how that is detected.
	 */
	private fun startDisplayLinkMonitor() = DisplayLinkMonitor(requireContext()) {
		Timber.i("Display link lost, ending playback")

		leavePlayer()
	}.also { it.start(lifecycleScope) }

	override fun onPause() {
		super.onPause()

		// Only resume playback later when it wasn't already paused by the user. Buffering reports as
		// PAUSED too, so being backgrounded mid-rebuffer leaves playback paused until the user
		// presses play again. Playback is also paused by [onCreate] until the initial resume, so the
		// guard only applies once playback has actually started.
		unpauseOnResume = !playbackStarted || playbackManager.state.playState.value == PlayState.PLAYING

		playbackManager.state.pause()
	}

	override fun onResume() {
		super.onResume()

		if (unpauseOnResume) lifecycleScope.launch {
			initialModeSwitch?.await()
			if (isResumed) playbackManager.state.unpause()
		}
	}

	override fun onDestroy() {
		super.onDestroy()

		displayModeSwitcher?.reset()
		displayModeSwitcher = null
	}

	override fun onStop() {
		super.onStop()

		playbackManager.state.stop()
	}
}

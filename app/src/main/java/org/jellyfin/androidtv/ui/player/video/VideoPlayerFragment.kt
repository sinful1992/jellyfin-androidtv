package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.DisplayLinkMonitor
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.sdk.api.client.ApiClient
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

	private var displayLinkMonitor: DisplayLinkMonitor? = null
	private var playbackStarted = false
	private var unpauseOnResume = true

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Create a queue from the items added to the legacy video queue
		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(api, videoQueueManager.getCurrentVideoQueue(), false)
		Timber.i("Created a queue with ${queueSupplier.items.size} items")
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier)

		// Set position
		val startPosition = arguments?.getInt(EXTRA_POSITION)?.milliseconds ?: Duration.ZERO
		awaitPlaybackStart(startPosition)

		// Pause player until the initial resume
		playbackManager.state.pause()
	}

	/**
	 * Wait for playback to start, then seek to the requested start position.
	 *
	 * Seeks are forwarded straight to the backend, which discards them while the media item is not
	 * prepared and seekable, and nothing queues commands issued before that point. The media stream
	 * is resolved asynchronously after the queue is populated, so a seek issued during [onCreate] is
	 * always dropped. Waiting for the first PLAYING state guarantees a prepared, seekable timeline,
	 * at the cost of briefly showing the start of the item before the seek lands.
	 *
	 * That first PLAYING state is also what tells [onPause] whether a paused player was paused by
	 * the user or has simply not started yet, so it is awaited even with no position to apply.
	 */
	private fun awaitPlaybackStart(position: Duration) {
		lifecycleScope.launch {
			playbackManager.state.playState.first { it == PlayState.PLAYING }
			playbackStarted = true

			if (position > Duration.ZERO) {
				Timber.i("Applying start position of $position")
				playbackManager.state.seek(position)
			}
		}
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		JellyfinTheme {
			VideoPlayerScreen()
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		displayLinkMonitor = startDisplayLinkMonitor()
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

		playbackManager.state.stop()

		if (navigationRepository.canGoBack) navigationRepository.goBack()
		else navigationRepository.reset(Destinations.home)
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

		if (unpauseOnResume) playbackManager.state.unpause()
	}

	override fun onStop() {
		super.onStop()

		playbackManager.state.stop()
	}
}

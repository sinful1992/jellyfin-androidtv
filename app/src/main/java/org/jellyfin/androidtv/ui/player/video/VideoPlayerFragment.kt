package org.jellyfin.androidtv.ui.player.video

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.ui.base.BaseScreen
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.VideoQueueManager
import org.jellyfin.androidtv.ui.playback.rewrite.RewriteMediaManager
import org.jellyfin.androidtv.util.DisplayLinkMonitor
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.sdk.api.client.ApiClient
import org.koin.android.ext.android.inject
import timber.log.Timber
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

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// Create a queue from the items added to the legacy video queue
		val queueSupplier = RewriteMediaManager.BaseItemQueueSupplier(api, videoQueueManager.getCurrentVideoQueue(), false)
		Timber.i("Created a queue with ${queueSupplier.items.size} items")
		playbackManager.queue.clear()
		playbackManager.queue.addSupplier(queueSupplier)

		// Set position
		arguments?.getInt(EXTRA_POSITION)?.milliseconds?.let {
			lifecycleScope.launch {
				playbackManager.state.seek(it)
			}
		}

		// Pause player until the initial resume
		playbackManager.state.pause()
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

		playbackManager.state.pause()
	}

	override fun onResume() {
		super.onResume()

		playbackManager.state.unpause()
	}

	override fun onStop() {
		super.onStop()

		playbackManager.state.stop()
	}
}

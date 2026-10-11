package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.Player
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.test.core.app.ApplicationProvider
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.queue.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The stop path the Bravia reports go through: Back mid-episode stops and clears the player, and the
 * next play prepares it again. Runs a real ExoPlayer on a short PCM file, so it covers the order of
 * stop, clear and prepare, not the TV's own decoders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StopAndPlayTests {
	@get:Rule
	val folder = TemporaryFolder()

	private lateinit var backend: ExoPlayerBackend
	private val playStates = mutableListOf<PlayState>()

	@Before
	fun setUp() {
		backend = ExoPlayerBackend(ApplicationProvider.getApplicationContext(), ExoPlayerOptions())
		backend.setListener(object : PlayerBackendEventListener() {
			override fun onPlayStateChange(state: PlayState) {
				playStates += state
			}
		})
	}

	private fun entry(name: String): QueueEntry {
		val file = wav(folder.newFile("$name.wav"))
		return QueueEntry().apply {
			mediaStream = PlayableMediaStream(
				identifier = name,
				conversionMethod = MediaConversionMethod.None,
				container = MediaStreamContainer("wav"),
				tracks = emptyList(),
				queueEntry = this,
				url = file.toURI().toString(),
			)
		}
	}

	/**
	 * Robolectric's audio output never reports ready, so an item counts as loaded once its tracks
	 * are known: it then has a playing period, which is what the stop path tears down.
	 */
	private fun runUntilLoaded() = runMainLooperUntil {
		!backend.exoPlayer.currentTracks.isEmpty || backend.exoPlayer.playerError != null
	}

	@Test
	fun theNextItemLoadsAfterAStopMidItem() {
		backend.playItem(entry("first"))
		backend.play()
		runUntilLoaded()

		backend.stop()
		run(backend.exoPlayer).untilPendingCommandsAreFullyHandled()
		assertEquals(0, backend.exoPlayer.mediaItemCount)
		assertEquals(Player.STATE_IDLE, backend.exoPlayer.playbackState)

		backend.playItem(entry("second"))
		runUntilLoaded()

		assertEquals("second", (backend.exoPlayer.currentMediaItem?.localConfiguration?.tag as QueueEntry).mediaStream?.identifier)
		assertEquals(null, backend.exoPlayer.playerError)
	}

	@Test
	fun aTrackReselectionAfterAStopIsNotReportedAsAnError() {
		backend.playItem(entry("first"))
		backend.play()
		runUntilLoaded()

		backend.stop()
		run(backend.exoPlayer).untilPendingCommandsAreFullyHandled()
		playStates.clear()

		// What the TV does when the audio output goes away with the stream (a passthrough sink
		// released, the TV switched off): the renderers report new capabilities and the player
		// reselects tracks, on a playlist that is already empty.
		val trackSelector = backend.exoPlayer.trackSelector as DefaultTrackSelector
		trackSelector.onRendererCapabilitiesChanged(backend.exoPlayer.getRenderer(0))
		run(backend.exoPlayer).untilPendingCommandsAreFullyHandled()

		assertFalse("play states after the stop: $playStates (player error ${backend.exoPlayer.playerError?.cause})", PlayState.ERROR in playStates)

		// And the player is not left unable to play: the next item loads as usual.
		backend.playItem(entry("second"))
		runUntilLoaded()
		assertEquals(null, backend.exoPlayer.playerError)
	}

	/** A second of 16-bit mono silence at 8 kHz. */
	private fun wav(file: File): File {
		val sampleRate = 8000
		val data = ByteArray(sampleRate * 2)
		val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
			put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
			put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
			putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
			put("data".toByteArray()); putInt(data.size)
		}
		file.writeBytes(header.array() + data)
		return file
	}
}

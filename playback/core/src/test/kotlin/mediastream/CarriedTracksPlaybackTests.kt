package org.jellyfin.playback.core.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.jellyfin.playback.core.NoOpPlayerVolumeState
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.PlaybackManagerOptions
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueService
import org.jellyfin.playback.core.queue.supplier.QueueSupplier
import java.util.Collections
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val episodeTracks = listOf(
	MediaStreamAudioTrack(index = 1, codec = "eac3", language = "eng", isDefault = true, bitrate = 0, channels = 6, sampleRate = 48000),
	MediaStreamAudioTrack(index = 2, codec = "aac", language = "spa", bitrate = 0, channels = 2, sampleRate = 48000),
	MediaStreamSubtitleTrack(index = 3, codec = "subrip", language = "eng", isDefault = true),
	MediaStreamSubtitleTrack(index = 4, codec = "subrip", language = "spa", isForced = true),
	MediaStreamSubtitleTrack(index = 5, codec = "subrip", language = "spa"),
)

/** Records each stream request with the track choice it was made with, as the server would see it. */
private class RecordingResolver(
	private val conversionMethod: MediaConversionMethod,
	private val knowsTracks: Boolean,
) : MediaStreamResolver {
	val requests: MutableList<Pair<Int?, Int?>> = Collections.synchronizedList(mutableListOf())

	override suspend fun getStream(queueEntry: QueueEntry): PlayableMediaStream {
		requests += queueEntry.selectedAudioStreamIndex to queueEntry.selectedSubtitleStreamIndex
		return PlayableMediaStream(
			identifier = "session-${requests.size}",
			conversionMethod = conversionMethod,
			container = MediaStreamContainer("mkv"),
			tracks = episodeTracks,
			queueEntry = queueEntry,
			url = "http://server/stream",
		)
	}

	override fun getKnownTracks(queueEntry: QueueEntry) = episodeTracks.takeIf { knowsTracks }
}

private class Playback(resolver: RecordingResolver, carried: MediaStreamService.() -> Unit) {
	val backend = mockk<PlayerBackend>(relaxed = true)
	val queue = QueueService()
	val service = MediaStreamService(listOf(resolver), Duration.ZERO, null) { "alice" }.apply(carried)
	val entry = QueueEntry().apply { trackCarryGroup = "series-a" }

	init {
		PlaybackManager(
			backend = backend,
			services = mutableListOf(queue, service),
			options = PlaybackManagerOptions(NoOpPlayerVolumeState(), { 10.seconds }, { 10.seconds }),
		)
	}

	suspend fun play() {
		queue.addSupplier(object : QueueSupplier {
			override val size = 1
			override suspend fun getItem(index: Int) = entry.takeIf { index == 0 }
		})
		queue.setIndex(0)
		verify(timeout = 5_000) { backend.playItem(entry, any()) }
	}
}

@OptIn(ExperimentalCoroutinesApi::class)
class CarriedTracksPlaybackTests : FunSpec({
	beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
	afterTest { Dispatchers.resetMain() }

	val spanishDub: MediaStreamService.() -> Unit = {
		carriedTracks(QueueEntry().apply { trackCarryGroup = "series-a" }).apply {
			audio = TrackPreference(language = "spa", codec = "aac")
			subtitle = TrackPreference(language = "spa")
		}
	}

	test("a converted stream with a carried choice is asked for once, with the choice in it") {
		val resolver = RecordingResolver(MediaConversionMethod.Transcode, knowsTracks = true)
		val playback = Playback(resolver, spanishDub)

		playback.play()

		resolver.requests shouldContainExactly listOf(2 to 5)
		playback.entry.selectedAudioStreamIndex shouldBe 2
		playback.entry.selectedSubtitleStreamIndex shouldBe 5
	}

	test("without the tracks known ahead a converted stream is asked for again with the choice") {
		val resolver = RecordingResolver(MediaConversionMethod.Transcode, knowsTracks = false)
		val playback = Playback(resolver, spanishDub)

		playback.play()

		resolver.requests shouldContainExactly listOf(null to null, 2 to 5)
	}

	test("direct play applies the carried choice on the stream it has") {
		val resolver = RecordingResolver(MediaConversionMethod.None, knowsTracks = false)
		val playback = Playback(resolver, spanishDub)

		playback.play()

		resolver.requests.size shouldBe 1
		playback.entry.selectedAudioStreamIndex shouldBe 2
		playback.entry.selectedSubtitleStreamIndex shouldBe 5
	}

	test("subtitles off with Spanish audio still show the Spanish forced track") {
		val resolver = RecordingResolver(MediaConversionMethod.Transcode, knowsTracks = true)
		val playback = Playback(resolver) {
			carriedTracks(QueueEntry().apply { trackCarryGroup = "series-a" }).apply {
				audio = TrackPreference(language = "spa", codec = "aac")
				subtitle = TrackPreference.OFF
			}
		}

		playback.play()

		resolver.requests shouldContainExactly listOf(2 to 4)
	}

	test("subtitles off with the default English audio show none") {
		val resolver = RecordingResolver(MediaConversionMethod.Transcode, knowsTracks = true)
		val playback = Playback(resolver) {
			carriedTracks(QueueEntry().apply { trackCarryGroup = "series-a" }).subtitle = TrackPreference.OFF
		}

		playback.play()

		resolver.requests shouldContainExactly listOf(null to MediaStreamSubtitleTrack.INDEX_NONE)
	}
})

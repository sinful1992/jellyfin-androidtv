package org.jellyfin.playback.core.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.jellyfin.playback.core.NoOpPlayerVolumeState
import org.jellyfin.playback.core.PlaybackEvent
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.PlaybackManagerOptions
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueService
import org.jellyfin.playback.core.queue.startPosition
import org.jellyfin.playback.core.queue.supplier.QueueSupplier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A resolver whose answer for an entry can be held back, standing in for a slow server, and that
 * can be told an entry has no stream at all.
 */
private class HeldResolver(
	private val conversionMethod: MediaConversionMethod = MediaConversionMethod.None,
	private val tracks: Collection<MediaStreamTrack> = emptyList(),
) : MediaStreamResolver {
	/** Every request made, in order. */
	val requests: MutableList<QueueEntry> = Collections.synchronizedList(mutableListOf())
	val unresolvable: MutableSet<QueueEntry> = ConcurrentHashMap.newKeySet()
	private val held = ConcurrentHashMap<QueueEntry, CompletableDeferred<Unit>>()
	private val heldFrom = ConcurrentHashMap<QueueEntry, Int>()

	/** Requests for [entry], from its [from]th on, wait until [release] is called for it. */
	fun hold(entry: QueueEntry, from: Int = 1) {
		heldFrom[entry] = from
		held[entry] = CompletableDeferred()
	}

	fun release(entry: QueueEntry) {
		held.remove(entry)?.complete(Unit)
	}

	fun requestsFor(entry: QueueEntry) = synchronized(requests) { requests.count { it === entry } }

	override suspend fun getStream(queueEntry: QueueEntry): PlayableMediaStream? {
		requests += queueEntry
		val number = requestsFor(queueEntry)
		if (number >= (heldFrom[queueEntry] ?: 1)) held[queueEntry]?.await()
		if (queueEntry in unresolvable) return null

		return PlayableMediaStream(
			identifier = "session-${System.identityHashCode(queueEntry)}-$number",
			conversionMethod = conversionMethod,
			container = MediaStreamContainer("mkv"),
			tracks = tracks,
			queueEntry = queueEntry,
			url = "http://server/stream",
		)
	}
}

/**
 * A real [PlaybackManager], [QueueService] and [MediaStreamService] over a backend that records
 * what it is asked to do and reports the play state a player would.
 */
private class Player(val resolver: HeldResolver, entryCount: Int = 3) {
	/** What the backend was asked to do, in order: "play 0" is playItem for the first entry. */
	val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
	val events: MutableList<PlaybackEvent> = Collections.synchronizedList(mutableListOf())
	val entries = List(entryCount) { QueueEntry() }

	@Volatile
	private var listener: PlayerBackendEventListener? = null

	@Volatile
	var position: Duration = Duration.ZERO

	val backend = object : PlayerBackend by mockk<PlayerBackend>(relaxed = true) {
		override fun setListener(eventListener: PlayerBackendEventListener?) {
			listener = eventListener
		}

		override fun getPositionInfo() = PositionInfo(position, position, 600.seconds)

		override fun playItem(item: QueueEntry, startPosition: Duration) {
			asked += "play ${entries.indexOf(item)}"
			listener?.onPlayStateChange(PlayState.PLAYING)
		}

		override fun play() {
			asked += "resume"
			listener?.onPlayStateChange(PlayState.PLAYING)
		}

		override fun pause() {
			asked += "pause"
			listener?.onPlayStateChange(PlayState.PAUSED)
		}

		override fun stop() {
			asked += "stop"
			listener?.onPlayStateChange(PlayState.STOPPED)
		}

		override fun seekTo(position: Duration) {
			asked += "seek ${position.inWholeSeconds}"
		}
	}

	val queue = QueueService()
	val service = MediaStreamService(listOf(resolver), Duration.ZERO)
	val manager = PlaybackManager(
		backend = backend,
		services = mutableListOf(queue, service),
		options = PlaybackManagerOptions(NoOpPlayerVolumeState(), { 10.seconds }, { 10.seconds }),
	)
	private val eventScope = CoroutineScope(Dispatchers.Unconfined)

	init {
		eventScope.launch { manager.events.collect { events += it } }
	}

	/** Fill the queue. It moves to its first entry by itself, as it does in the app. */
	suspend fun start() {
		queue.addSupplier(object : QueueSupplier {
			override val size = entries.size
			override suspend fun getItem(index: Int) = entries.getOrNull(index)
		})
		withTimeout(5_000) { while (queue.entry.value !== entries[0]) delay(5) }
	}

	val plays: List<String> get() = synchronized(asked) { asked.filter { it.startsWith("play ") } }

	fun close() {
		eventScope.cancel()
		service.coroutineScope.cancel()
		queue.coroutineScope.cancel()
	}
}

private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) {
	while (!condition()) delay(5)
}

/** Long enough for a start that should not happen to have happened. */
private suspend fun settle() = delay(300)

@OptIn(ExperimentalCoroutinesApi::class)
class StaleResolveTests : FunSpec({
	lateinit var resolver: HeldResolver
	lateinit var player: Player

	beforeTest {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		resolver = HeldResolver()
		player = Player(resolver)
	}

	afterTest {
		player.close()
		Dispatchers.resetMain()
	}

	/** The queue is on its first entry and the server has been asked for its stream, held back. */
	suspend fun startHeld() {
		val entry = player.entries[0]
		resolver.hold(entry)
		player.start()
		awaitUntil { resolver.requestsFor(entry) == 1 }
	}

	// JF-2 (audit 137): the queue collector handles one entry at a time and waits for the server
	// in the middle of it. An entry the user had already left was still started when its stream
	// arrived, and only then replaced.

	test("an entry skipped while its stream was being asked for is never started") {
		val (first, second) = player.entries
		first.startPosition = 30.seconds
		startHeld()

		player.queue.setIndex(1)
		resolver.release(first)
		awaitUntil { "play 1" in player.plays }
		settle()

		player.plays shouldContainExactly listOf("play 1")
		// Not used up by a start that never happened: going back to it still resumes.
		first.startPosition shouldBe 30.seconds
		second.startPosition shouldBe null
	}

	test("two skips while the first stream was being asked for start only the entry the queue is on") {
		startHeld()

		player.queue.setIndex(1)
		player.queue.setIndex(2)
		resolver.release(player.entries[0])
		awaitUntil { "play 2" in player.plays }
		settle()

		player.plays shouldContainExactly listOf("play 2")
	}

	test("an entry left and come back to while its stream was being asked for starts once") {
		startHeld()

		player.queue.setIndex(1)
		player.queue.setIndex(0)
		resolver.release(player.entries[0])
		awaitUntil { "play 0" in player.plays }
		settle()

		player.plays shouldContainExactly listOf("play 0")
	}

	test("an entry with no stream that was skipped meanwhile does not skip the entry after it") {
		val first = player.entries[0]
		resolver.unresolvable += first
		startHeld()

		player.queue.setIndex(1)
		resolver.release(first)
		awaitUntil { "play 1" in player.plays }
		settle()

		player.queue.entry.value shouldBe player.entries[1]
		player.plays shouldContainExactly listOf("play 1")
		// Nothing to tell the user about an entry they had already left.
		player.events.shouldBeEmpty()
	}

	test("an entry with no stream that the queue is still on is reported and skipped as before") {
		val first = player.entries[0]
		resolver.unresolvable += first
		startHeld()

		resolver.release(first)
		awaitUntil { "play 1" in player.plays }
		settle()

		player.events shouldContainExactly listOf(PlaybackEvent.EntryUnplayable(first))
		player.plays shouldContainExactly listOf("play 1")
	}

	test("a stream that arrives after playback was stopped starts nothing") {
		startHeld()

		player.manager.state.stop()
		resolver.release(player.entries[0])
		settle()

		player.plays.shouldBeEmpty()
		player.manager.state.playState.value shouldBe PlayState.STOPPED
	}

	test("an entry whose stream arrives while the queue is still on it starts where it was asked to") {
		player.entries[0].startPosition = 30.seconds
		startHeld()

		resolver.release(player.entries[0])
		awaitUntil { "play 0" in player.plays }
		settle()

		player.plays shouldContainExactly listOf("play 0")
		player.entries[0].startPosition shouldBe null
	}

	test("an entry skipped while its stream was asked for again with a carried track is never started") {
		// A converted stream has the audio baked in, so a carried choice asks the server a second
		// time: the entry can be left during that request as well.
		player.close()
		resolver = HeldResolver(
			conversionMethod = MediaConversionMethod.Transcode,
			tracks = listOf(
				MediaStreamAudioTrack(index = 1, codec = "eac3", language = "eng", isDefault = true, bitrate = 0, channels = 6, sampleRate = 48000),
				MediaStreamAudioTrack(index = 2, codec = "aac", language = "spa", bitrate = 0, channels = 2, sampleRate = 48000),
			),
		)
		player = Player(resolver)
		val (first, second) = player.entries
		// The service's first look at the empty queue forgets the queue's carried choices: wait for it.
		awaitUntil { "stop" in player.asked }
		player.service.carriedTracks(first).audio = TrackPreference(language = "spa", codec = "aac")
		first.startPosition = 30.seconds

		resolver.hold(first, from = 2)
		player.start()
		awaitUntil { resolver.requestsFor(first) == 2 }

		player.queue.setIndex(1)
		resolver.release(first)
		awaitUntil { "play 1" in player.plays }
		settle()

		player.plays shouldContainExactly listOf("play 1")
		first.startPosition shouldBe 30.seconds
		second.selectedAudioStreamIndex shouldBe 2
	}

	// JF-5 (audit 137): a reload read "was it paused" before it asked the server for the stream,
	// so a pause pressed while it waited was undone when the new stream started playing.

	/** Entry 0 plays at 42 s and a reload of its stream is waiting for the server. */
	suspend fun reloadHeld(paused: Boolean) {
		val entry = player.entries[0]
		player.start()
		awaitUntil { "play 0" in player.plays }
		player.position = 42.seconds
		if (paused) player.manager.state.pause()
		player.asked.clear()

		resolver.hold(entry)
		player.service.applyStreamOptionsChange()
		awaitUntil { resolver.requestsFor(entry) == 2 }
	}

	test("a pause pressed while a reload waits for the server is kept") {
		reloadHeld(paused = false)

		player.manager.state.pause()
		resolver.release(player.entries[0])
		awaitUntil { "play 0" in player.plays }
		settle()

		player.asked shouldContainExactly listOf("pause", "play 0", "seek 42", "pause")
		player.manager.state.playState.value shouldBe PlayState.PAUSED
	}

	test("play pressed while a reload of a paused stream waits for the server is kept") {
		reloadHeld(paused = true)

		player.manager.state.unpause()
		resolver.release(player.entries[0])
		awaitUntil { "play 0" in player.plays }
		settle()

		player.asked shouldContainExactly listOf("resume", "play 0", "seek 42")
		player.manager.state.playState.value shouldBe PlayState.PLAYING
	}

	test("a reload of a paused stream ends paused") {
		reloadHeld(paused = true)

		resolver.release(player.entries[0])
		awaitUntil { "play 0" in player.plays }
		settle()

		player.asked shouldContainExactly listOf("play 0", "seek 42", "pause")
		player.manager.state.playState.value shouldBe PlayState.PAUSED
	}

	test("a reload of a playing stream ends playing") {
		reloadHeld(paused = false)

		resolver.release(player.entries[0])
		awaitUntil { "play 0" in player.plays }
		settle()

		player.asked shouldContainExactly listOf("play 0", "seek 42")
		player.manager.state.playState.value shouldBe PlayState.PLAYING
	}

	test("a pause pressed and released again while a reload waits ends playing") {
		reloadHeld(paused = false)

		player.manager.state.pause()
		player.manager.state.unpause()
		resolver.release(player.entries[0])
		awaitUntil { "play 0" in player.plays }
		settle()

		player.asked shouldContainExactly listOf("pause", "resume", "play 0", "seek 42")
		player.manager.state.playState.value shouldBe PlayState.PLAYING
	}
})

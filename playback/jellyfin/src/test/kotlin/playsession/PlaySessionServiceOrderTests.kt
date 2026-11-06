package org.jellyfin.playback.jellyfin.playsession

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.playback.core.NoOpPlayerVolumeState
import org.jellyfin.playback.core.PlaybackManager
import org.jellyfin.playback.core.PlaybackManagerOptions
import org.jellyfin.playback.core.backend.PlayerBackend
import org.jellyfin.playback.core.backend.PlayerBackendEventListener
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStream
import org.jellyfin.playback.core.mediastream.MediaStreamContainer
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PositionInfo
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.core.queue.QueueService
import org.jellyfin.playback.core.queue.supplier.QueueSupplier
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.operations.PlayStateApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import java.util.Collections
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val API_EXTENSIONS = "org.jellyfin.sdk.api.client.extensions.ApiClientExtensionsKt"

/** A report as the server receives it: recorded when the call completes, not when it is made. */
private sealed interface Report {
	val session: String?

	data class Started(override val session: String?) : Report
	data class Progress(override val session: String?, val isPaused: Boolean) : Report
	data class Stopped(override val session: String?) : Report
}

/**
 * A real [PlaybackManager] and [QueueService] around [PlaySessionService], with a fake backend whose
 * play state the test drives and a play state API that records what reaches the server.
 */
private class Session {
	private val received: MutableList<Report> = Collections.synchronizedList(mutableListOf())

	/** What the server has received so far, in order. */
	val reports: List<Report> get() = synchronized(received) { received.toList() }

	/** Progress reports wait here before they reach the server, standing in for a slow request. */
	@Volatile
	var progressGate = CompletableDeferred(Unit)
	val progressEntered = CompletableDeferred<Unit>()

	@Volatile
	private var listener: PlayerBackendEventListener? = null
	private val backend = object : PlayerBackend by mockk<PlayerBackend>(relaxed = true) {
		override fun setListener(eventListener: PlayerBackendEventListener?) {
			listener = eventListener
		}

		override fun getPositionInfo() = PositionInfo(42.seconds, 42.seconds, 600.seconds)
		override fun getFinalPosition(stream: MediaStream): Duration? = null
	}

	private val api = mockk<ApiClient>()
	private val playStateApi = mockk<PlayStateApi>()

	val queue: QueueService = QueueService::class.java.getDeclaredConstructor()
		.apply { isAccessible = true }
		.newInstance()
	val service = PlaySessionService(api)

	val entry = QueueEntry()

	init {
		every { api.playStateApi } returns playStateApi
		coEvery { playStateApi.reportPlaybackStart(any()) } coAnswers {
			received += Report.Started(firstArg<PlaybackStartInfo>().playSessionId)
			Response(Unit, 204, emptyMap())
		}
		coEvery { playStateApi.reportPlaybackProgress(any()) } coAnswers {
			val info = firstArg<PlaybackProgressInfo>()
			progressEntered.complete(Unit)
			progressGate.await()
			received += Report.Progress(info.playSessionId, info.isPaused)
			Response(Unit, 204, emptyMap())
		}
		coEvery { playStateApi.reportPlaybackStopped(any()) } coAnswers {
			received += Report.Stopped(firstArg<PlaybackStopInfo>().playSessionId)
			Response(Unit, 204, emptyMap())
		}

		// The constructor is internal to the core module.
		PlaybackManager::class.java.getDeclaredConstructor(
			PlayerBackend::class.java,
			MutableList::class.java,
			PlaybackManagerOptions::class.java,
			Job::class.java,
		).apply { isAccessible = true }.newInstance(
			backend,
			mutableListOf<PlayerService>(queue, service),
			PlaybackManagerOptions(NoOpPlayerVolumeState(), { 10.seconds }, { 10.seconds }),
			null,
		)

		entry.baseItem = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE)
		entry.mediaStream = PlayableMediaStream(
			identifier = SESSION,
			conversionMethod = MediaConversionMethod.None,
			container = MediaStreamContainer("mkv"),
			tracks = emptyList(),
			queueEntry = entry,
			url = "http://server/stream",
		)
	}

	suspend fun play() {
		queue.addSupplier(object : QueueSupplier {
			override val size = 1
			override suspend fun getItem(index: Int) = entry.takeIf { index == 0 }
		})
		awaitUntil { queue.entry.value == entry }

		backendSays(PlayState.PLAYING)
		awaitUntil { Report.Started(SESSION) in reports }
	}

	fun backendSays(playState: PlayState) {
		requireNotNull(listener).onPlayStateChange(playState)
	}

	fun close() {
		service.coroutineScope.cancel()
		queue.coroutineScope.cancel()
	}

	companion object {
		const val SESSION = "session-1"
	}
}

private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(5_000) {
	while (!condition()) delay(10)
}

/** Whatever the server received for [session] after it was told the session stopped. */
private fun List<Report>.afterStop(session: String): List<Report> {
	val stop = indexOfFirst { it is Report.Stopped && it.session == session }
	if (stop == -1) return emptyList()
	return drop(stop + 1).filter { it.session == session }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaySessionServiceOrderTests : FunSpec({
	lateinit var session: Session

	beforeTest {
		Dispatchers.setMain(UnconfinedTestDispatcher())
		mockkStatic(API_EXTENSIONS)
		session = Session()
	}

	afterTest {
		session.close()
		unmockkStatic(API_EXTENSIONS)
		Dispatchers.resetMain()
	}

	test("a pause report still in flight when Back stops the player never reaches the server after the stop") {
		session.play()
		session.progressGate = CompletableDeferred()

		// Back: the pause's progress report is under way when the queue is cleared.
		session.backendSays(PlayState.PAUSED)
		withTimeout(5_000) { session.progressEntered.await() }
		session.service.manager.state.stop()

		// Give a stop that does not wait for the report time to go out ahead of it.
		withTimeoutOrNull(1_000) {
			awaitUntil { session.reports.any { it is Report.Stopped } }
		}
		session.progressGate.complete(Unit)

		awaitUntil {
			val reports = session.reports
			reports.any { it is Report.Stopped } && reports.any { it is Report.Progress }
		}
		// Anything still queued behind the stop has had its chance to land.
		delay(200)

		val reports = session.reports
		reports.afterStop(Session.SESSION) shouldBe emptyList<Report>()
		reports.count { it is Report.Stopped } shouldBe 1
	}

	test("a pause and then a stop tell the server it paused before it stopped") {
		session.play()

		session.backendSays(PlayState.PAUSED)
		awaitUntil { session.reports.any { it is Report.Progress } }
		session.service.manager.state.stop()
		awaitUntil { session.reports.any { it is Report.Stopped } }
		delay(200)

		session.reports shouldContainExactly listOf(
			Report.Started(Session.SESSION),
			Report.Progress(Session.SESSION, isPaused = true),
			Report.Stopped(Session.SESSION),
		)
	}
})

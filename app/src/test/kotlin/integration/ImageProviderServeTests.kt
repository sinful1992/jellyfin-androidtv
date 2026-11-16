package org.jellyfin.androidtv.integration.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/** The thread name without the " @coroutine#N" that kotlinx debug mode adds. */
private fun thread() = Thread.currentThread().name.substringBefore(" @")

class ImageProviderServeTests : FunSpec({
	val executor = Executors.newSingleThreadExecutor { Thread(it, "launcher-pictures") }
	val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
	val scope = CoroutineScope(
		SupervisorJob() + executor.asCoroutineDispatcher() + CoroutineExceptionHandler { _, err -> uncaught += err }
	)

	afterSpec { executor.shutdownNow() }
	beforeEach { uncaught.clear() }

	test("openFile's work returns at once and loads and writes off the caller's thread (task 171)") {
		val gate = CompletableDeferred<Unit>()
		val events = Collections.synchronizedList(mutableListOf<String>())
		val job = serveImage(
			scope = scope,
			load = { gate.await(); events += "load:" + thread(); "picture" },
			write = { events += "write $it:" + thread() },
			close = { events += "close" },
		)
		// The caller is back while the picture is still loading.
		events.shouldBeEmpty()
		gate.complete(Unit)
		runBlocking { job.join() }
		events shouldBe listOf("load:launcher-pictures", "write picture:launcher-pictures", "close")
	}

	test("a picture that does not load closes the pipe without writing") {
		val events = mutableListOf<String>()
		runBlocking { serveImage<String>(scope, load = { null }, write = { events += "write" }, close = { events += "close" }).join() }
		events shouldBe listOf("close")
	}

	test("a load that throws closes the pipe and never reaches the app's crash handler") {
		val events = mutableListOf<String>()
		runBlocking {
			serveImage<String>(scope, load = { error("server gone") }, write = { events += "write" }, close = { events += "close" }).join()
		}
		events shouldBe listOf("close")
		uncaught.shouldBeEmpty()
	}

	test("a write that throws (launcher closed its end) still closes, quietly") {
		val events = mutableListOf<String>()
		runBlocking {
			serveImage(scope, load = { "picture" }, write = { throw IllegalStateException("pipe") }, close = { events += "close" }).join()
		}
		events shouldBe listOf("close")
		uncaught.shouldBeEmpty()
	}

	test("a close that throws is swallowed too") {
		runBlocking { serveImage(scope, load = { "picture" }, write = { }, close = { error("already closed") }).join() }
		uncaught.shouldBeEmpty()
	}

	test("many pictures at once all get served and closed") {
		val closed = Collections.synchronizedList(mutableListOf<Int>())
		val jobs = (1..60).map { n -> serveImage(scope, load = { n }, write = { }, close = { closed += n }) }
		runBlocking { jobs.forEach { it.join() } }
		closed.sorted() shouldBe (1..60).toList()
	}

	test("source: no image-loader target callback (main thread) left in ImageProvider, and the size is capped") {
		val source = File("src/main/java/org/jellyfin/androidtv/integration/provider/ImageProvider.kt").readText()
		source shouldNotContain "target("
		source shouldContain "serveImage("
		source shouldContain "size(MAX_SIZE)"
	}

	test("source: the launcher rows and search ask the server for launcher-sized pictures, not the original") {
		val worker = File("src/main/java/org/jellyfin/androidtv/integration/LeanbackChannelWorker.kt").readText()
		val search = File("src/main/java/org/jellyfin/androidtv/integration/MediaContentProvider.kt").readText()
		worker shouldContain "getUrl(api, maxHeight = ImageProvider.MAX_HEIGHT)"
		search shouldContain "getUrl(api, maxHeight = ImageProvider.MAX_HEIGHT)"
	}
})

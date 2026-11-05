package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

class SwitchThenStartTests : FunSpec({
	test("a finished switch starts playback") {
		var started = 0
		switchThenStart({ }, { false }, { started++ }) shouldBe true
		started shouldBe 1
	}

	test("a failed switch still starts playback") {
		var started = 0
		switchThenStart({ error("no modes") }, { false }, { started++ }) shouldBe true
		started shouldBe 1
	}

	test("leaving during the switch does not start playback") {
		var gone = false
		var started = 0
		switchThenStart({ gone = true }, { gone }, { started++ }) shouldBe false
		started shouldBe 0
	}

	test("the player destroyed mid switch does not start playback") {
		var started = 0
		val switching = CompletableDeferred<Unit>()
		coroutineScope {
			val job = launch(start = CoroutineStart.UNDISPATCHED) {
				switchThenStart({ switching.complete(Unit); awaitCancellation() }, { false }, { started++ })
			}
			switching.await()
			job.cancel()
			job.join()
			job.isCancelled shouldBe true
		}
		started shouldBe 0
	}

	test("a switch that swallows cancellation still does not start playback") {
		var started = 0
		coroutineScope {
			val job = launch(start = CoroutineStart.UNDISPATCHED) {
				switchThenStart({ runCatching { awaitCancellation() } }, { false }, { started++ })
			}
			yield()
			job.cancel()
			job.join()
		}
		started shouldBe 0
	}
})

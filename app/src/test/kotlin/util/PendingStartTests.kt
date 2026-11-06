package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PendingStartTests : FunSpec({
	test("a held start runs when released") {
		var started = 0
		val pending = PendingStart()
		pending.hold { started++ }
		pending.release(leaving = false)
		started shouldBe 1
	}

	test("a second release does not start twice") {
		var started = 0
		val pending = PendingStart()
		pending.hold { started++ }
		pending.release(leaving = false)
		pending.release(leaving = false)
		started shouldBe 1
	}

	test("a leaving player drops the held start") {
		var started = 0
		val pending = PendingStart()
		pending.hold { started++ }
		pending.release(leaving = true)
		pending.release(leaving = false)
		started shouldBe 0
	}

	test("release with nothing held does nothing") {
		var started = 0
		PendingStart().release(leaving = false)
		started shouldBe 0
	}

	test("a newer hold replaces the older one") {
		val ran = mutableListOf<Int>()
		val pending = PendingStart()
		pending.hold { ran += 1 }
		pending.hold { ran += 2 }
		pending.release(leaving = false)
		ran shouldBe listOf(2)
	}
})

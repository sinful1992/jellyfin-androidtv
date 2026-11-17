package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NavTraceTests : FunSpec({
	test("a Back line has the contract's fields (task 176)") {
		NavTraceState().back("details", "home", toHome = true, at = 1000) shouldBe
			"back from=details to=home at=1000"
	}

	test("a Back to Home times its first draw once") {
		val state = NavTraceState()
		state.back("details", "home", toHome = true, at = 1000)
		state.homeDrawn(at = 1350) shouldBe "home_back_drawn since_back=350 at=1350"
		state.homeDrawn(at = 2000) shouldBe null
	}

	test("a Back to another page does not time a later Home draw") {
		val state = NavTraceState()
		state.back("details", "other", toHome = false, at = 1000)
		state.homeDrawn(at = 1200) shouldBe null
		state.homeRows(calls = 2, at = 1300) shouldBe null
		state.rowsPending shouldBe false
	}

	test("a Home draw with no Back before it (app start) logs nothing") {
		NavTraceState().homeDrawn(at = 50) shouldBe null
	}

	test("rows line once per Back, with the calls fired, 0 allowed") {
		val state = NavTraceState()
		state.back("details", "home", toHome = true, at = 1000)
		state.rowsPending shouldBe true
		state.homeRows(calls = 0, at = 2600) shouldBe "home_back_rows since_back=1600 calls=0"
		state.rowsPending shouldBe false
		state.homeRows(calls = 3, at = 2700) shouldBe null
	}

	test("a second Back restarts the clock from the newer Back") {
		val state = NavTraceState()
		state.back("details", "home", toHome = true, at = 1000)
		state.back("details", "home", toHome = true, at = 5000)
		state.homeDrawn(at = 5200) shouldBe "home_back_drawn since_back=200 at=5200"
	}

	test("unknown pages print none") {
		NavTraceState().back(null, null, toHome = false, at = 1) shouldBe "back from=none to=none at=1"
	}

	test("Home is decided by the flag, not the name: a renamed release class still times (linus review)") {
		val state = NavTraceState()
		state.back("tw1", "xq2", toHome = true, at = 1000)
		state.homeDrawn(at = 1040) shouldBe "home_back_drawn since_back=40 at=1040"
	}

	test("a forward navigation gives up a Back still waiting for its Home") {
		val state = NavTraceState()
		state.back("details", "home", toHome = true, at = 1000)
		state.navigated()
		state.homeDrawn(at = 1100) shouldBe null
		state.homeRows(calls = 1, at = 1200) shouldBe null
		state.rowsPending shouldBe false
	}

	test("a Home drawn more than 10 s after its Back logs nothing (stale flag)") {
		val state = NavTraceState()
		state.back("details", "home", toHome = true, at = 1000)
		state.homeDrawn(at = 11_001) shouldBe null
		state.homeRows(calls = 0, at = 11_002) shouldBe null
	}

	test("exactly 10 s still logs") {
		val state = NavTraceState()
		state.back("details", "home", toHome = true, at = 0)
		state.homeDrawn(at = 10_000) shouldBe "home_back_drawn since_back=10000 at=10000"
	}
})

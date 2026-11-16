package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StartupTraceTests : FunSpec({
	test("a phase line has the contract's fields in order (task 171, I1)") {
		StartupTrace.formatPhase("home_rows_done", t = 3412, pid = 17612, cold = true, baseProc = true) shouldBe
			"phase=home_rows_done t=3412 pid=17612 cold=1 base=proc"
	}

	test("a warm phase line says cold=0 base=act") {
		StartupTrace.formatPhase("main_activity", t = 97, pid = 5, cold = false, baseProc = false) shouldBe
			"phase=main_activity t=97 pid=5 cold=0 base=act"
	}

	test("process age is added only when given") {
		StartupTrace.formatPhase("startup_activity", t = 0, pid = 5, cold = true, baseProc = false, procAge = 7_200_000) shouldBe
			"phase=startup_activity t=0 pid=5 cold=1 base=act proc_age=7200000"
	}

	test("time from the process start only for a first Activity soon after the process started") {
		StartupTrace.timeFromProcessStart(cold = true, procAgeMs = 650) shouldBe true
		StartupTrace.timeFromProcessStart(cold = true, procAgeMs = StartupTrace.COLD_WINDOW_MS - 1) shouldBe true
	}

	test("a process the channel job started hours earlier is timed from the Activity, not the process (Bravia 11-16)") {
		StartupTrace.timeFromProcessStart(cold = true, procAgeMs = StartupTrace.COLD_WINDOW_MS) shouldBe false
		StartupTrace.timeFromProcessStart(cold = true, procAgeMs = 4 * 60 * 60 * 1000L) shouldBe false
	}

	test("a warm start is timed from the Activity even in a young process") {
		StartupTrace.timeFromProcessStart(cold = false, procAgeMs = 100) shouldBe false
	}

	test("a stall line lists the top frames, at most eight") {
		val frames = (1..12).map { StackTraceElement("a.B$it", "m$it", "B.kt", it) }
		StartupTrace.formatStall(dur = 1450, pid = 9, runT = 2210, frames = frames) shouldBe
			"stall dur=1450 pid=9 run_t=2210 at=" + (1..8).joinToString("|") { "a.B$it.m$it:$it" }
	}

	test("a stall before any run says run_t=-1, an empty stack leaves at= empty") {
		StartupTrace.formatStall(dur = 800, pid = 9, runT = -1, frames = emptyList()) shouldBe
			"stall dur=800 pid=9 run_t=-1 at="
	}

	test("a main thread waiting in its message queue is idle, not stalled (fake stall after standby, linus review)") {
		val idle = listOf(
			StackTraceElement("android.os.MessageQueue", "nativePollOnce", "MessageQueue.java", -2),
			StackTraceElement("android.os.MessageQueue", "next", "MessageQueue.java", 335),
			StackTraceElement("android.os.Looper", "loop", "Looper.java", 183),
		)
		StartupTrace.isIdle(idle) shouldBe true
	}

	test("a main thread busy in app code or blocked on a write is a stall") {
		StartupTrace.isIdle(listOf(StackTraceElement("android.graphics.Bitmap", "nativeCompress", "Bitmap.java", -2))) shouldBe false
		StartupTrace.isIdle(listOf(StackTraceElement("libcore.io.Linux", "writeBytes", "Linux.java", -2))) shouldBe false
		StartupTrace.isIdle(emptyList()) shouldBe false
	}

	test("source: the watchdog times pings with uptime, which stops during standby") {
		val source = java.io.File("src/main/java/org/jellyfin/androidtv/util/StartupTrace.kt").readText()
		val watchdog = source.substringAfter("private fun createWatchdog()")
		watchdog.contains("clock = SystemClock::uptimeMillis") shouldBe true
		watchdog.contains("now()") shouldBe false
	}

	/** A fake clock that moves only when the watchdog waits; the ping is answered after [answerAt] ms, or never. */
	class FakePing(val answerAt: Long = Long.MAX_VALUE) {
		var t = 0L
		val waits = mutableListOf<Long>()
		fun await(ms: Long): Boolean {
			waits += ms
			if (waits.size > 100) error("watchdog spins: ${waits.take(10)}")
			val until = t + maxOf(ms, 0)
			if (until >= answerAt) { t = answerAt; return true }
			t = until
			return false
		}
	}

	val idleStack = listOf(StackTraceElement("android.os.MessageQueue", "nativePollOnce", "MessageQueue.java", -2))
	val busyStack = listOf(StackTraceElement("android.graphics.Bitmap", "nativeCompress", "Bitmap.java", -2))

	test("a ping held while main is idle (sync barrier) waits a full second per check and logs nothing (linus review)") {
		val ping = FakePing(answerAt = 5_300)
		val logged = mutableListOf<Long>()
		val checks = StartupTrace.awaitPing(ping::await, { ping.t }, { idleStack }, { late, _ -> logged += late })
		logged shouldBe emptyList()
		checks shouldBe 5
		ping.waits shouldBe listOf(700L, 1_000L, 1_000L, 1_000L, 1_000L, 1_000L)
	}

	test("a stuck main thread is logged at 0.7 s and then every second until the ping runs") {
		val ping = FakePing(answerAt = 3_000)
		val logged = mutableListOf<Long>()
		StartupTrace.awaitPing(ping::await, { ping.t }, { busyStack }, { late, _ -> logged += late })
		logged shouldBe listOf(700L, 1_700L, 2_700L)
	}

	test("a ping answered in time logs nothing and makes no checks") {
		val ping = FakePing(answerAt = 20)
		val logged = mutableListOf<Long>()
		StartupTrace.awaitPing(ping::await, { ping.t }, { busyStack }, { late, _ -> logged += late }) shouldBe 0
		logged shouldBe emptyList()
	}

	test("a stall is reported at 700 ms and then every second") {
		StartupTrace.nextReportAt(0) shouldBe 700
		StartupTrace.nextReportAt(1) shouldBe 1_700
		StartupTrace.nextReportAt(7) shouldBe 7_700
	}
})

class StartupRowsTraceTests : FunSpec({
	fun trace(expected: Int): Pair<StartupRowsTrace, MutableList<String>> {
		val events = mutableListOf<String>()
		return StartupRowsTrace(expected, { events += "first" }, { events += "all" }) to events
	}

	test("first row with items, then all rows answered") {
		val (rows, events) = trace(3)
		rows.answered(hasItems = false)
		events shouldBe emptyList()
		rows.answered(hasItems = true)
		events shouldBe listOf("first")
		rows.answered(hasItems = true)
		events shouldBe listOf("first", "all")
	}

	test("rows that all come back empty still finish, with no first row") {
		val (rows, events) = trace(2)
		rows.answered(false)
		rows.answered(false)
		events shouldBe listOf("all")
	}

	test("no rows at all finishes at once") {
		val (_, events) = trace(0)
		events shouldBe listOf("all")
	}

	test("a row answering again after a refresh logs nothing more") {
		val (rows, events) = trace(1)
		rows.answered(true)
		rows.answered(true)
		rows.answered(false)
		events shouldBe listOf("first", "all")
	}
})

package org.jellyfin.androidtv.util

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One logcat line per start-up phase, tag [TAG], kept in release builds (team contract task 171, I1):
 *
 *     phase=<name> t=<ms> pid=<pid> cold=<0|1> base=<proc|act>[ proc_age=<ms>]
 *
 * plus a main-thread watchdog that logs where the main thread is stuck when it answers a ping late:
 *
 *     stall dur=<ms> pid=<pid> run_t=<ms or -1> at=<frame>|<frame>|...
 *
 * No user names, tokens or item names go into either line.
 */
object StartupTrace {
	const val TAG = "JFStartup"

	/** A first Activity this soon after the process started is a real cold start, timed from the process start. */
	const val COLD_WINDOW_MS = 10_000L

	/** The main thread answering a ping this late is reported. */
	const val STALL_MS = 700L

	/** While still stuck, report again this often. */
	const val STALL_REPEAT_MS = 1_000L

	/** Time between two pings when the main thread is answering. */
	private const val PING_INTERVAL_MS = 500L
	private const val STALL_FRAMES = 8

	private val lock = Any()
	private var appCreatedAt = -1L
	private var activityCreated = false
	private var runBase = -1L
	private var runCold = false
	private var runBaseProc = false
	private val logged = HashSet<String>()
	private var watchdog: Thread? = null

	private fun now() = SystemClock.elapsedRealtime()
	private fun pid() = Process.myPid()

	private fun processStart(): Long = when {
		Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> Process.getStartElapsedRealtime()
		appCreatedAt >= 0 -> appCreatedAt
		else -> now()
	}

	/** From [android.app.Application.onCreate]. Logs `app_create` and starts the watchdog. */
	fun appCreate() {
		val line = synchronized(lock) {
			val at = now()
			if (appCreatedAt < 0) appCreatedAt = at
			formatPhase("app_create", at - processStart(), pid(), cold = true, baseProc = true)
		}
		Log.i(TAG, line)
		startWatchdog()
	}

	/** From the start of StartupActivity.onCreate: begins a new run. */
	fun startupActivityCreated() {
		val line = synchronized(lock) {
			val at = now()
			val procAge = at - processStart()
			runCold = !activityCreated
			activityCreated = true
			runBaseProc = timeFromProcessStart(runCold, procAge)
			runBase = if (runBaseProc) processStart() else at
			logged.clear()
			logged.add("startup_activity")
			formatPhase("startup_activity", at - runBase, pid(), runCold, runBaseProc, procAge)
		}
		Log.i(TAG, line)
	}

	/** Any other Activity created; a later StartupActivity in this process is then a warm start. */
	fun activityCreated() = synchronized(lock) { activityCreated = true }

	/** Logs [name] once per run. Nothing before the first StartupActivity of the process. */
	fun phase(name: String) {
		val line = synchronized(lock) {
			if (runBase < 0 || !logged.add(name)) return
			formatPhase(name, now() - runBase, pid(), runCold, runBaseProc)
		}
		Log.i(TAG, line)
	}

	private fun runTime(): Long = synchronized(lock) { if (runBase < 0) -1 else now() - runBase }

	fun timeFromProcessStart(cold: Boolean, procAgeMs: Long) = cold && procAgeMs < COLD_WINDOW_MS

	fun formatPhase(name: String, t: Long, pid: Int, cold: Boolean, baseProc: Boolean, procAge: Long? = null) = buildString {
		append("phase=").append(name)
		append(" t=").append(t)
		append(" pid=").append(pid)
		append(" cold=").append(if (cold) 1 else 0)
		append(" base=").append(if (baseProc) "proc" else "act")
		if (procAge != null) append(" proc_age=").append(procAge)
	}

	fun formatStall(dur: Long, pid: Int, runT: Long, frames: List<StackTraceElement>) = buildString {
		append("stall dur=").append(dur)
		append(" pid=").append(pid)
		append(" run_t=").append(runT)
		append(" at=")
		frames.take(STALL_FRAMES).joinTo(this, "|") { "${it.className}.${it.methodName}:${it.lineNumber}" }
	}

	/**
	 * When the next report of one stall is due: the first at [STALL_MS] late, then every [STALL_REPEAT_MS].
	 * [reports] is how many were already logged for this stall.
	 */
	fun nextReportAt(reports: Int) = STALL_MS + reports * STALL_REPEAT_MS

	private fun startWatchdog() = synchronized(lock) {
		if (watchdog != null) return
		val mainLooper = Looper.getMainLooper()
		val main = Handler(mainLooper)
		watchdog = Thread({
			while (true) {
				val answered = CountDownLatch(1)
				val sentAt = now()
				main.post { answered.countDown() }
				var reports = 0
				while (!answered.await(nextReportAt(reports) - (now() - sentAt), TimeUnit.MILLISECONDS)) {
					val late = now() - sentAt
					if (late < nextReportAt(reports)) continue
					Log.i(TAG, formatStall(late, pid(), runTime(), mainLooper.thread.stackTrace.toList()))
					reports++
				}
				SystemClock.sleep(PING_INTERVAL_MS)
			}
		}, "JFStartupWatchdog").apply {
			isDaemon = true
			priority = Thread.MIN_PRIORITY
			start()
		}
	}
}

/**
 * Counts the home rows' first answers for [StartupTrace]: [onFirstWithItems] once, for the first row that came back
 * with items, and [onAll] once every one of the [expected] rows has answered (with items, empty or failed).
 */
class StartupRowsTrace(
	private val expected: Int,
	private val onFirstWithItems: () -> Unit,
	private val onAll: () -> Unit,
) {
	private var answered = 0
	private var firstSeen = false
	private var allSeen = false

	init {
		if (expected <= 0) finish()
	}

	fun answered(hasItems: Boolean) {
		if (allSeen) return
		answered++
		if (hasItems && !firstSeen) {
			firstSeen = true
			onFirstWithItems()
		}
		if (answered >= expected) finish()
	}

	private fun finish() {
		allSeen = true
		onAll()
	}
}

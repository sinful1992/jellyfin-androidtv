package org.jellyfin.androidtv

import android.content.Context
import androidx.startup.Initializer
import timber.log.Timber

class LogInitializer : Initializer<Unit> {
	override fun create(context: Context) {
		// Enable improved logging for leaking resources
		// https://wh0.github.io/2020/08/12/closeguard.html
		if (BuildConfig.DEBUG) {
			try {
				Class.forName("dalvik.system.CloseGuard")
					.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
					.invoke(null, true)
			} catch (e: ReflectiveOperationException) {
				@Suppress("TooGenericExceptionThrown")
				throw RuntimeException(e)
			}
		}

		// Initialize the logging library.
		//
		// Debug builds only. The plant call used to sit outside the guard above, so release builds
		// carried a DebugTree too, and a planted tree is not something R8 can strip the calls into
		// — every Timber.i/d/w/e in the app formatted its message and wrote it to logcat on the
		// television.
		//
		// The cost is not only the write. DebugTree derives its tag from the call site, and it does
		// that by constructing a Throwable and walking its stack trace whenever no explicit tag was
		// set. There is exactly one Timber.tag call in this tree against 292 call sites, so all but
		// one of them paid for a stack trace capture per log line.
		//
		// ACRA reads logcat rather than Timber for its report's log section, so crash reports are
		// unaffected by this.
		if (BuildConfig.DEBUG) {
			Timber.plant(Timber.DebugTree())
			Timber.i("Debug tree planted")
		}
	}

	override fun dependencies() = emptyList<Class<out Initializer<*>>>()
}

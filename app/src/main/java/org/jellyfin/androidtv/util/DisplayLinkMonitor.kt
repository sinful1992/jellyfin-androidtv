package org.jellyfin.androidtv.util

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports when the display link drops, which happens when the television is switched off.
 *
 * Two mechanisms are used because neither covers every device:
 *
 * - [HdmiPlugMonitor] reacts to the HDMI audio plug broadcast. It is immediate and works on every
 *   supported API level, but some devices keep the plug state asserted while the television is off,
 *   where it never reports anything.
 * - [HdcpMonitor] polls the HDCP level, which does drop on those devices. It needs API 28.
 *
 * Whichever notices first wins, and playback is only ended once.
 */
class DisplayLinkMonitor(
	context: Context,
	private val onLinkLost: () -> Unit,
) {
	private val fired = AtomicBoolean(false)

	private val hdmiPlugMonitor = HdmiPlugMonitor(context, ::reportLinkLost)
	private val hdcpMonitor = HdcpMonitor(onHdcpLost = ::reportLinkLost)

	fun start(scope: CoroutineScope) {
		hdmiPlugMonitor.start()
		hdcpMonitor.start(scope)
	}

	fun stop() {
		hdmiPlugMonitor.stop()
		hdcpMonitor.stop()
	}

	/**
	 * Both monitors deliver on the main thread, so the guard only needs to cover the case where
	 * both notice the same disconnection.
	 */
	private fun reportLinkLost() {
		if (!fired.compareAndSet(false, true)) {
			Timber.i("Display link loss already handled, ignoring")
			return
		}

		onLinkLost()
	}
}

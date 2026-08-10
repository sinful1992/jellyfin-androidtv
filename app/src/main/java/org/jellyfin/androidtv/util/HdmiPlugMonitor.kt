package org.jellyfin.androidtv.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * Watches the HDMI audio plug state and reports when the display is disconnected.
 *
 * [AudioManager.ACTION_HDMI_AUDIO_PLUG] is sticky, so the current state arrives as soon as the
 * receiver is registered rather than only on the next change.
 *
 * Not every device reports this correctly. Some keep the plug state asserted while the television
 * is powered off, which is why [HdcpMonitor] exists alongside this. This one is still worth having
 * because it costs nothing while idle and works below API 28, where the HDCP level cannot be read
 * at all.
 */
class HdmiPlugMonitor(
	context: Context,
	private val onDisconnected: () -> Unit,
) {
	private companion object {
		private const val STATE_CONNECTED = 1
		private const val STATE_DISCONNECTED = 0
		private const val STATE_UNKNOWN = -1
	}

	private val context = context.applicationContext

	/**
	 * True once the display has been reported as connected. Prevents acting on a disconnected
	 * state that was already true when monitoring started, where there is nothing to react to.
	 */
	private var sawConnected = false

	private var receiver: BroadcastReceiver? = null

	fun start() {
		if (receiver != null) return

		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context?, intent: Intent?) {
				val state = intent?.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, STATE_UNKNOWN)
					?: STATE_UNKNOWN
				onPlugStateChanged(state)
			}
		}

		this.receiver = receiver

		// Must be registered as exported. The broadcast comes from the system rather than from
		// this application, and registering as not exported attaches a permission requirement the
		// system does not hold, which silently filters out every delivery. The action is a
		// protected broadcast, so no other application can send it.
		ContextCompat.registerReceiver(
			context,
			receiver,
			IntentFilter(AudioManager.ACTION_HDMI_AUDIO_PLUG),
			ContextCompat.RECEIVER_EXPORTED,
		)
	}

	private fun onPlugStateChanged(state: Int) {
		Timber.i("HDMI plug state=%d sawConnected=%b", state, sawConnected)

		if (state == STATE_CONNECTED) {
			sawConnected = true
			return
		}

		if (state != STATE_DISCONNECTED) return
		if (!sawConnected) return

		Timber.i("HDMI audio disconnected, display link lost")
		onDisconnected()
	}

	fun stop() {
		val receiver = receiver ?: return
		this.receiver = null

		try {
			context.unregisterReceiver(receiver)
		} catch (error: IllegalArgumentException) {
			Timber.w(error, "HDMI plug receiver was already unregistered")
		}
	}
}

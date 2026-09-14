package org.jellyfin.androidtv.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
	private val disconnectGrace: Duration = 4.seconds,
	private val onDisconnected: () -> Unit,
) {
	private companion object {
		private const val STATE_CONNECTED = 1
		private const val STATE_DISCONNECTED = 0
		private const val STATE_UNKNOWN = -1
	}

	private val context = context.applicationContext

	private var scope: CoroutineScope? = null

	/**
	 * Set while a disconnect is waiting out [disconnectGrace], cancelled if the plug comes back.
	 *
	 * Switching video mode renegotiates HDMI and the audio sink drops and returns, so a single
	 * state 0 can be a false positive - the same hazard [HdcpMonitor] guards against with
	 * consecutive readings, which this arm had no equivalent of. A television that was really
	 * switched off does not come back, so the only cost of waiting is that playback runs a few
	 * seconds longer into a dark room.
	 */
	private var pendingDisconnect: Job? = null

	/**
	 * True once the display has been reported as connected. Prevents acting on a disconnected
	 * state that was already true when monitoring started, where there is nothing to react to.
	 */
	private var sawConnected = false

	private var receiver: BroadcastReceiver? = null

	fun start(scope: CoroutineScope) {
		if (receiver != null) return

		this.scope = scope

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
		Timber.i("HDMI plug state=%d sawConnected=%b pending=%b", state, sawConnected, pendingDisconnect != null)

		if (state == STATE_CONNECTED) {
			sawConnected = true
			// It came back, so whatever dropped it was a renegotiation and not the television
			// being switched off.
			pendingDisconnect?.let {
				Timber.i("HDMI audio back before the grace period elapsed, not stopping playback")
				it.cancel()
			}
			pendingDisconnect = null
			return
		}

		if (state != STATE_DISCONNECTED) return
		if (!sawConnected) return
		if (pendingDisconnect != null) return

		val scope = scope ?: return
		pendingDisconnect = scope.launch {
			delay(disconnectGrace)
			pendingDisconnect = null

			Timber.i("HDMI audio still disconnected after %s, display link lost", disconnectGrace)
			onDisconnected()
		}
	}

	fun stop() {
		pendingDisconnect?.cancel()
		pendingDisconnect = null
		scope = null

		val receiver = receiver ?: return
		this.receiver = null

		try {
			context.unregisterReceiver(receiver)
		} catch (error: IllegalArgumentException) {
			Timber.w(error, "HDMI plug receiver was already unregistered")
		}
	}
}

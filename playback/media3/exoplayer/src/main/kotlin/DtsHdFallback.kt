package org.jellyfin.playback.media3.exoplayer

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.edit
import androidx.core.content.getSystemService

/**
 * Remembers that DTS-HD stalled on this device's audio output, so later titles start with the DTS
 * core. Kept with the firmware build and the HDMI audio outputs it happened on: a firmware update
 * or another receiver gets full DTS-HD tried again.
 */
class DtsHdFallback(context: Context) {
	private companion object {
		const val PREFERENCES = "dts_hd_fallback"
		const val KEY_STALLED_ON = "stalled_on"

		val hdmiTypes = setOf(
			AudioDeviceInfo.TYPE_HDMI,
			AudioDeviceInfo.TYPE_HDMI_ARC,
			// AudioDeviceInfo.TYPE_HDMI_EARC, API 31.
			29,
		)
	}

	private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
	private val audioManager = context.getSystemService<AudioManager>()

	private fun outputKey(): String {
		val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
			.filter { it.type in hdmiTypes }
			.map { "${it.type}:${it.productName}:${it.encodings.sorted().joinToString(",")}" }
			.sorted()
		return (listOf(Build.FINGERPRINT) + outputs).joinToString("|")
	}

	val stalled: Boolean
		get() = preferences.getString(KEY_STALLED_ON, null) == outputKey()

	fun rememberStall() = preferences.edit { putString(KEY_STALLED_ON, outputKey()) }
}

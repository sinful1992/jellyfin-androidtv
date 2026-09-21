package org.jellyfin.playback.media3.exoplayer

import android.media.audiofx.LoudnessEnhancer
import timber.log.Timber

class ExoPlayerAudioPipeline {
	private var loudnessEnhancer: LoudnessEnhancer? = null
	private var audioSessionId: Int? = null

	var normalizationGain: Float? = null
		set(value) {
			Timber.d("Normalization gain changed to $value")
			field = value
			applyGain()
		}

	fun setAudioSessionId(audioSessionId: Int) {
		Timber.d("Audio session id changed to $audioSessionId")

		this.audioSessionId = audioSessionId

		// The enhancer belongs to the session it was built for, so drop the old one and let
		// applyGain build a new one if this entry actually has a gain to apply.
		releaseEnhancer()
		applyGain()
	}

	private fun applyGain() {
		val targetGain = normalizationGain
			// Convert to millibels
			?.times(100f)
			// Round to integer
			?.toInt()
			// Ignore if zero (so the enhancer will be disabled)
			?.takeIf { it != 0 }

		// With nothing to apply the enhancer is not merely disabled, it is released. An effect
		// stays attached to its audio session while it exists, enabled or not, and a session
		// carrying one cannot be served by a compressed direct output: AudioFlinger parks the
		// chain on the PCM mixer thread instead. Most entries carry no gain at all, so building
		// the effect unconditionally put every one of them in that state for nothing.
		if (targetGain == null) {
			releaseEnhancer()
			return
		}

		val enhancer = loudnessEnhancer ?: createEnhancer() ?: return

		Timber.d("Applying gain (targetGain=$targetGain)")
		runCatching {
			enhancer.setTargetGain(targetGain)
		}.onSuccess {
			enhancer.setEnabled(true)
		}.onFailure { error ->
			Timber.e(error, "Failed to apply gain of $targetGain")
			enhancer.setEnabled(false)
		}
	}

	private fun createEnhancer(): LoudnessEnhancer? {
		val sessionId = audioSessionId ?: return null

		loudnessEnhancer = runCatching { LoudnessEnhancer(sessionId) }
			.onFailure { Timber.w(it, "Failed to create LoudnessEnhancer") }
			.getOrNull()

		return loudnessEnhancer
	}

	private fun releaseEnhancer() {
		val enhancer = loudnessEnhancer ?: return
		loudnessEnhancer = null

		runCatching {
			enhancer.setEnabled(false)
			enhancer.release()
		}.onFailure { Timber.w(it, "Failed to release LoudnessEnhancer") }
	}
}

package org.jellyfin.playback.media3.exoplayer

import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.DtsUtil
import java.nio.ByteBuffer

/**
 * An audio sink that can pass DTS-HD through as its DTS core only: 5.1 surround at the core's
 * bitrate (up to 1.5 Mbps) instead of the full DTS-HD stream.
 *
 * Measured on a Bravia (Android 12) feeding a soundbar over eARC: DTS-HD MA passed through stalled
 * the audio clock for 100-300 ms at a time on the heaviest passages, with the output buffer full
 * and no underruns, so the stall is past the app. Decoding to PCM is no way out there either: the
 * TV mixes all PCM to stereo. The core is the part of the stream every DTS receiver plays.
 *
 * With [DtsHdOutput.AUTO], DTS-HD goes out in full while the audio clock keeps time. Once it falls
 * behind, the sink switches to the core at the next access unit and [fallback] remembers it.
 * Plain DTS and every other format are untouched.
 */
@OptIn(UnstableApi::class)
class DtsCoreAudioSink(
	sink: AudioSink,
	private val output: DtsHdOutput = DtsHdOutput.CORE,
	private val fallback: DtsHdFallback? = null,
	private val stallDetector: ClockStallDetector = ClockStallDetector(),
	private val wallClockUs: () -> Long = { SystemClock.elapsedRealtime() * 1000 },
) : ForwardingAudioSink(sink) {
	private var config: AudioSink.AudioSinkConfig? = null
	private var stripping = false
	private var watching = false
	private var switchToCore = false
	private var playing = false
	private var midBuffer = false
	private var pendingBuffer: ByteBuffer? = null
	private var pendingCoreEnd = 0

	// The core is enough to take DTS-HD: it can always be sent instead.
	override fun supportsFormat(format: Format): Boolean = super.supportsFormat(format.asCore())

	override fun getFormatSupport(format: Format): Int = super.getFormatSupport(format.asCore())

	override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
		if (format.isDtsHd) AudioOffloadSupport.DEFAULT_UNSUPPORTED
		else super.getFormatOffloadSupport(format)

	override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
		val format = audioSinkConfig.format
		config = audioSinkConfig
		pendingBuffer = null
		midBuffer = false
		switchToCore = false
		stallDetector.reset()

		stripping = format.isDtsHd && when (output) {
			DtsHdOutput.CORE -> true
			DtsHdOutput.FULL -> false
			DtsHdOutput.AUTO -> fallback?.stalled == true || !super.supportsFormat(format)
		}
		watching = format.isDtsHd && !stripping && output == DtsHdOutput.AUTO
		// Shows on the TV whether the remembered output still matches after standby or a reboot.
		if (format.isDtsHd && fallback != null) Log.i(TAG, "DTS-HD ($output, core=$stripping) on ${fallback.outputKey()}")
		if (stripping) Log.i(TAG, "Passing DTS-HD through as its DTS core ($output)")

		super.configure(if (stripping) audioSinkConfig.toCore() else audioSinkConfig)
	}

	override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
		// Switched only between access units: a partly written one belongs to the old output.
		if (switchToCore && !midBuffer) {
			switchToCore = false
			watching = false
			stripping = true
			pendingBuffer = null
			// The sink plays out what it holds, then reopens its output for the core.
			config?.let { super.configure(it.toCore()) }
		}

		if (!stripping) {
			val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
			midBuffer = !handled
			return handled
		}

		// The sink is called again with the same buffer until it has taken all of it, and the
		// renderer reuses buffer objects between samples, so a new access unit starts once the
		// previous one was handled.
		if (pendingBuffer !== buffer) {
			pendingBuffer = buffer
			pendingCoreEnd = buffer.position() + coreSize(buffer)
		}

		val limit = buffer.limit()
		buffer.limit(minOf(pendingCoreEnd, limit))
		val handled = try {
			super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
		} finally {
			buffer.limit(limit)
		}

		if (handled) {
			// The extension substream after the core is dropped.
			buffer.position(limit)
			pendingBuffer = null
		}
		return handled
	}

	override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
		val positionUs = super.getCurrentPositionUs(sourceEnded)
		if (watching && playing && !switchToCore && positionUs != AudioSink.CURRENT_POSITION_NOT_SET) {
			if (stallDetector.sample(wallClockUs(), positionUs, playbackParameters.speed)) {
				Log.w(TAG, "DTS-HD passthrough is losing time, switching to the DTS core")
				switchToCore = true
				fallback?.rememberStall()
			}
		}
		return positionUs
	}

	override fun play() {
		playing = true
		stallDetector.reset()
		super.play()
	}

	override fun pause() {
		playing = false
		stallDetector.reset()
		super.pause()
	}

	override fun handleDiscontinuity() {
		stallDetector.reset()
		super.handleDiscontinuity()
	}

	override fun flush() {
		pendingBuffer = null
		midBuffer = false
		stallDetector.reset()
		super.flush()
	}

	override fun reset() {
		pendingBuffer = null
		midBuffer = false
		stallDetector.reset()
		super.reset()
	}

	internal companion object {
		// Logged with Log, not Timber: release builds plant no tree, and this is what shows on a TV
		// that a switch happened.
		private const val TAG = "DtsCoreAudioSink"
		private const val HEADER_BYTES = 16

		private val Format.isDtsHd
			get() = sampleMimeType == MimeTypes.AUDIO_DTS_HD

		private fun AudioSink.AudioSinkConfig.toCore() = AudioSink.AudioSinkConfig.Builder(format.asCore())
			.setPreferredBufferSizeOverride(preferredBufferSizeOverride)
			.setTimeline(timeline)
			.setMediaPeriodId(mediaPeriodId)
			.apply { outputChannelMapping?.let(::setOutputChannelMapping) }
			.build()

		/**
		 * The format of the DTS core inside a DTS-HD stream. The core carries at most 5.1 here:
		 * the extra channels of a 7.1 track are in the extension substream.
		 */
		fun Format.asCore(): Format =
			if (!isDtsHd) this
			else buildUpon()
				.setSampleMimeType(MimeTypes.AUDIO_DTS)
				.setCodecs(null)
				.apply { if (channelCount > 6) setChannelCount(6) }
				.build()

		/**
		 * Size of the DTS core frame at the buffer's position, or everything that is left when the
		 * access unit does not start with a core frame.
		 */
		fun coreSize(buffer: ByteBuffer): Int {
			val remaining = buffer.remaining()
			if (remaining < HEADER_BYTES) return remaining

			val header = ByteArray(HEADER_BYTES)
			buffer.duplicate().get(header)
			// Big-endian whatever the buffer's byte order, as DtsUtil reads sync words.
			val word = (header[0].toInt() and 0xFF shl 24) or (header[1].toInt() and 0xFF shl 16) or
				(header[2].toInt() and 0xFF shl 8) or (header[3].toInt() and 0xFF)
			if (DtsUtil.getFrameType(word) != DtsUtil.FRAME_TYPE_CORE) return remaining

			val frameSize = DtsUtil.getDtsFrameSize(header)
			return if (frameSize in 1..remaining) frameSize else remaining
		}
	}
}

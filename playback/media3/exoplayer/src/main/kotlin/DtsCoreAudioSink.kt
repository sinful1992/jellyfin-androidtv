package org.jellyfin.playback.media3.exoplayer

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
 * An audio sink that passes DTS-HD through as its DTS core only: 5.1 surround at the core's
 * bitrate (up to 1.5 Mbps) instead of the full DTS-HD stream.
 *
 * Measured on a Bravia (Android 12) feeding a soundbar over eARC: DTS-HD MA passed through stalled
 * the audio clock for 100-300 ms at a time on the heaviest passages, with the output buffer full
 * and no underruns, so the stall is past the app. Decoding to PCM is no way out there either: the
 * TV mixes all PCM to stereo. The core is the part of the stream every DTS receiver plays.
 * Plain DTS and every other format are untouched.
 */
@OptIn(UnstableApi::class)
class DtsCoreAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
	private var stripping = false
	private var pendingBuffer: ByteBuffer? = null
	private var pendingCoreEnd = 0

	override fun supportsFormat(format: Format): Boolean = super.supportsFormat(format.asCore())

	override fun getFormatSupport(format: Format): Int = super.getFormatSupport(format.asCore())

	override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
		if (format.isDtsHd) AudioOffloadSupport.DEFAULT_UNSUPPORTED
		else super.getFormatOffloadSupport(format)

	override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
		stripping = audioSinkConfig.format.isDtsHd
		pendingBuffer = null
		if (!stripping) return super.configure(audioSinkConfig)

		val coreConfig = AudioSink.AudioSinkConfig.Builder(audioSinkConfig.format.asCore())
			.setPreferredBufferSizeOverride(audioSinkConfig.preferredBufferSizeOverride)
			.setTimeline(audioSinkConfig.timeline)
			.setMediaPeriodId(audioSinkConfig.mediaPeriodId)
			.apply { audioSinkConfig.outputChannelMapping?.let(::setOutputChannelMapping) }
			.build()
		super.configure(coreConfig)
	}

	override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
		if (!stripping) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

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

	override fun flush() {
		pendingBuffer = null
		super.flush()
	}

	override fun reset() {
		pendingBuffer = null
		super.reset()
	}

	internal companion object {
		private const val HEADER_BYTES = 16

		private val Format.isDtsHd
			get() = sampleMimeType == MimeTypes.AUDIO_DTS_HD

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

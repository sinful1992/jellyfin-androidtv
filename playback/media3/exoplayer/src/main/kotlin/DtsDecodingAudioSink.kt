package org.jellyfin.playback.media3.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * An audio sink that refuses DTS in its encoded form, so it is never passed through or offloaded
 * and the FFmpeg renderer decodes it to PCM on the device instead.
 *
 * Measured on a Bravia (Android 12) feeding a soundbar over eARC: DTS-HD MA passed through stalled
 * the audio clock for 100-300 ms at a time on the heaviest passages, with tens of seconds of media
 * buffered, and the video waited on it. Decoded on the device the same passages played without a
 * stall. Dolby formats are untouched and still go out as they are.
 */
@OptIn(UnstableApi::class)
class DtsDecodingAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
	private val Format.isEncodedDts
		get() = sampleMimeType in dtsMimeTypes

	override fun supportsFormat(format: Format): Boolean =
		!format.isEncodedDts && super.supportsFormat(format)

	override fun getFormatSupport(format: Format): Int =
		if (format.isEncodedDts) AudioSink.SINK_FORMAT_UNSUPPORTED
		else super.getFormatSupport(format)

	override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
		if (format.isEncodedDts) AudioOffloadSupport.DEFAULT_UNSUPPORTED
		else super.getFormatOffloadSupport(format)

	private companion object {
		val dtsMimeTypes = setOf(
			MimeTypes.AUDIO_DTS,
			MimeTypes.AUDIO_DTS_HD,
			MimeTypes.AUDIO_DTS_EXPRESS,
			MimeTypes.AUDIO_DTS_X,
		)
	}
}

package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

private fun format(mimeType: String) = Format.Builder().setSampleMimeType(mimeType).build()

class DtsDecodingAudioSinkTests : FunSpec({
	// A sink that would pass everything through, like the Bravia's eARC output.
	val passthroughSink = mockk<AudioSink> {
		every { supportsFormat(any()) } returns true
		every { getFormatSupport(any()) } returns AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
		every { getFormatOffloadSupport(any()) } returns AudioOffloadSupport.Builder()
			.setIsFormatSupported(true)
			.build()
	}
	val sink = DtsDecodingAudioSink(passthroughSink)

	val dtsTypes = listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X)

	test("encoded DTS of every kind is refused, so it cannot be passed through") {
		for (mimeType in dtsTypes) {
			sink.supportsFormat(format(mimeType)) shouldBe false
			sink.getFormatSupport(format(mimeType)) shouldBe AudioSink.SINK_FORMAT_UNSUPPORTED
		}
	}

	test("encoded DTS of every kind is refused for offload too") {
		for (mimeType in dtsTypes) {
			sink.getFormatOffloadSupport(format(mimeType)).isFormatSupported shouldBe false
		}
	}

	test("Dolby formats are still passed through as the sink allows") {
		for (mimeType in listOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_TRUEHD)) {
			sink.supportsFormat(format(mimeType)) shouldBe true
			sink.getFormatSupport(format(mimeType)) shouldBe AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
			sink.getFormatOffloadSupport(format(mimeType)).isFormatSupported shouldBe true
		}
	}

	test("decoded PCM, which is what the DTS decoder hands over, is accepted") {
		val pcm = Format.Builder()
			.setSampleMimeType(MimeTypes.AUDIO_RAW)
			.setPcmEncoding(androidx.media3.common.C.ENCODING_PCM_FLOAT)
			.setChannelCount(8)
			.setSampleRate(48000)
			.build()

		sink.supportsFormat(pcm) shouldBe true
	}
})

package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import android.util.Log
import androidx.media3.common.PlaybackParameters
import org.jellyfin.playback.media3.exoplayer.DtsCoreAudioSink.Companion.asCore
import org.jellyfin.playback.media3.exoplayer.DtsCoreAudioSink.Companion.coreSize
import java.nio.ByteBuffer

private fun format(mimeType: String, channels: Int = 8) = Format.Builder()
	.setSampleMimeType(mimeType)
	.setChannelCount(channels)
	.setSampleRate(48000)
	.build()

// A 16-bit big-endian DTS core frame header (sync 0x7FFE8001) whose FSIZE field gives a 2013 byte
// frame, the size of a 1509 kbps core frame.
private fun coreFrame(size: Int = 2013): ByteArray {
	val fsize = size - 1
	return ByteArray(size).apply {
		this[0] = 0x7F; this[1] = 0xFE.toByte(); this[2] = 0x80.toByte(); this[3] = 0x01
		this[4] = 0xFC.toByte()
		this[5] = (0x3C or ((fsize shr 12) and 0x03)).toByte()
		this[6] = ((fsize shr 4) and 0xFF).toByte()
		this[7] = ((fsize and 0x0F) shl 4).toByte()
	}
}

private val extensionSubstream = byteArrayOf(0x64, 0x58, 0x20, 0x25) + ByteArray(3000)

class DtsCoreAudioSinkTests : FunSpec({
	mockkStatic(Log::class)
	every { Log.i(any(), any()) } returns 0
	every { Log.w(any<String>(), any<String>()) } returns 0

	test("DTS-HD is offered to the sink as the DTS core, at most 5.1") {
		val core = format(MimeTypes.AUDIO_DTS_HD).asCore()
		core.sampleMimeType shouldBe MimeTypes.AUDIO_DTS
		core.channelCount shouldBe 6
		format(MimeTypes.AUDIO_DTS_HD, channels = 6).asCore().channelCount shouldBe 6
	}

	test("plain DTS and Dolby formats are left as they are") {
		for (mimeType in listOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_TRUEHD)) {
			val format = format(mimeType)
			format.asCore() shouldBe format
		}
	}

	test("DTS-HD is supported when the sink can pass DTS through, even without DTS-HD") {
		val dtsOnlySink = mockk<AudioSink> {
			every { supportsFormat(any()) } answers { firstArg<Format>().sampleMimeType == MimeTypes.AUDIO_DTS }
			every { getFormatSupport(any()) } answers {
				if (firstArg<Format>().sampleMimeType == MimeTypes.AUDIO_DTS) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
				else AudioSink.SINK_FORMAT_UNSUPPORTED
			}
		}
		val sink = DtsCoreAudioSink(dtsOnlySink)

		sink.supportsFormat(format(MimeTypes.AUDIO_DTS_HD)) shouldBe true
		sink.getFormatSupport(format(MimeTypes.AUDIO_DTS_HD)) shouldBe AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
	}

	test("DTS-HD is not offloaded, so it takes the passthrough path") {
		val sink = DtsCoreAudioSink(mockk {
			every { getFormatOffloadSupport(any()) } returns AudioOffloadSupport.Builder().setIsFormatSupported(true).build()
		})
		sink.getFormatOffloadSupport(format(MimeTypes.AUDIO_DTS_HD)).isFormatSupported shouldBe false
		sink.getFormatOffloadSupport(format(MimeTypes.AUDIO_AC3)).isFormatSupported shouldBe true
	}

	test("the core size is read from the core frame header") {
		coreSize(ByteBuffer.wrap(coreFrame() + extensionSubstream)) shouldBe 2013
	}

	test("an access unit without a core frame is passed whole") {
		val buffer = ByteBuffer.wrap(extensionSubstream)
		coreSize(buffer) shouldBe extensionSubstream.size
	}

	test("only the core reaches the sink, and the whole access unit is consumed") {
		val written = mutableListOf<Int>()
		val configured = slot<AudioSink.AudioSinkConfig>()
		val passthroughSink = mockk<AudioSink>(relaxed = true) {
			every { configure(capture(configured)) } returns Unit
			every { handleBuffer(any(), any(), any()) } answers {
				val buffer = firstArg<ByteBuffer>()
				written += buffer.remaining()
				buffer.position(buffer.limit())
				true
			}
		}
		val sink = DtsCoreAudioSink(passthroughSink)
		sink.configure(AudioSink.AudioSinkConfig.Builder(format(MimeTypes.AUDIO_DTS_HD)).build())

		val buffer = ByteBuffer.wrap(coreFrame() + extensionSubstream)
		sink.handleBuffer(buffer, 0, 1) shouldBe true

		configured.captured.format.sampleMimeType shouldBe MimeTypes.AUDIO_DTS
		written shouldBe listOf(2013)
		buffer.hasRemaining() shouldBe false
	}

	test("a core the sink takes in parts is finished before the next access unit") {
		val written = mutableListOf<Int>()
		var calls = 0
		val passthroughSink = mockk<AudioSink>(relaxed = true) {
			every { handleBuffer(any(), any(), any()) } answers {
				val buffer = firstArg<ByteBuffer>()
				calls++
				// First call: the sink is full after 1000 bytes.
				val take = if (calls == 1) 1000 else buffer.remaining()
				written += take
				buffer.position(buffer.position() + take)
				!buffer.hasRemaining()
			}
		}
		val sink = DtsCoreAudioSink(passthroughSink)
		sink.configure(AudioSink.AudioSinkConfig.Builder(format(MimeTypes.AUDIO_DTS_HD)).build())

		val buffer = ByteBuffer.wrap(coreFrame() + extensionSubstream)
		sink.handleBuffer(buffer, 0, 1) shouldBe false
		buffer.limit() shouldBe buffer.capacity()
		sink.handleBuffer(buffer, 0, 1) shouldBe true

		written shouldBe listOf(1000, 1013)
		buffer.hasRemaining() shouldBe false
	}

	test("other formats are written untouched") {
		val written = mutableListOf<Int>()
		val passthroughSink = mockk<AudioSink>(relaxed = true) {
			every { handleBuffer(any(), any(), any()) } answers {
				val buffer = firstArg<ByteBuffer>()
				written += buffer.remaining()
				buffer.position(buffer.limit())
				true
			}
		}
		val sink = DtsCoreAudioSink(passthroughSink)
		sink.configure(AudioSink.AudioSinkConfig.Builder(format(MimeTypes.AUDIO_DTS)).build())

		sink.handleBuffer(ByteBuffer.wrap(coreFrame() + extensionSubstream), 0, 1) shouldBe true
		written shouldBe listOf(2013 + extensionSubstream.size)
	}

	context("automatic") {
		// A sink that passes DTS-HD and DTS through, with an audio clock the test moves.
		class Bravia {
			var positionUs = 0L
			val configured = mutableListOf<String?>()
			val written = mutableListOf<Int>()
			val sink = mockk<AudioSink>(relaxed = true) {
				every { supportsFormat(any()) } returns true
				every { playbackParameters } returns PlaybackParameters.DEFAULT
				every { getCurrentPositionUs(any()) } answers { positionUs }
				every { configure(any()) } answers { configured += firstArg<AudioSink.AudioSinkConfig>().format.sampleMimeType }
				every { handleBuffer(any(), any(), any()) } answers {
					val buffer = firstArg<ByteBuffer>()
					written += buffer.remaining()
					buffer.position(buffer.limit())
					true
				}
			}
		}

		fun accessUnit() = ByteBuffer.wrap(coreFrame() + extensionSubstream)

		test("DTS-HD goes out in full while the clock keeps time") {
			val tv = Bravia()
			var wallUs = 0L
			val sink = DtsCoreAudioSink(tv.sink, DtsHdOutput.AUTO, wallClockUs = { wallUs })
			sink.configure(AudioSink.AudioSinkConfig.Builder(format(MimeTypes.AUDIO_DTS_HD)).build())
			sink.play()
			repeat(1200) {
				wallUs += 100_000
				tv.positionUs += 100_000
				sink.getCurrentPositionUs(false)
				sink.handleBuffer(accessUnit(), tv.positionUs, 1) shouldBe true
			}
			tv.configured shouldBe listOf(MimeTypes.AUDIO_DTS_HD)
			tv.written.last() shouldBe 2013 + extensionSubstream.size
		}

		test("a stalling clock switches to the core at the next access unit, and is remembered") {
			val tv = Bravia()
			val fallback = mockk<DtsHdFallback>(relaxed = true) { every { stalled } returns false; every { outputKey() } returns "tv" }
			var wallUs = 0L
			val sink = DtsCoreAudioSink(tv.sink, DtsHdOutput.AUTO, fallback, wallClockUs = { wallUs })
			sink.configure(AudioSink.AudioSinkConfig.Builder(format(MimeTypes.AUDIO_DTS_HD)).build())
			sink.play()
			for (step in 1..900) {
				wallUs += 100_000
				// 200 ms lost every 3 s.
				tv.positionUs += if (step % 30 == 0) -100_000 else 100_000
				sink.getCurrentPositionUs(false)
				sink.handleBuffer(accessUnit(), tv.positionUs, 1) shouldBe true
			}
			tv.configured shouldBe listOf(MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS)
			tv.written.last() shouldBe 2013
			verify(exactly = 1) { fallback.rememberStall() }
		}

		test("a remembered stall starts DTS-HD as the core") {
			val tv = Bravia()
			val fallback = mockk<DtsHdFallback> { every { stalled } returns true; every { outputKey() } returns "tv" }
			val sink = DtsCoreAudioSink(tv.sink, DtsHdOutput.AUTO, fallback)
			sink.configure(AudioSink.AudioSinkConfig.Builder(format(MimeTypes.AUDIO_DTS_HD)).build())
			tv.configured shouldBe listOf(MimeTypes.AUDIO_DTS)
		}

		test("paused and rebuffering time is not counted") {
			val tv = Bravia()
			var wallUs = 0L
			val sink = DtsCoreAudioSink(tv.sink, DtsHdOutput.AUTO, wallClockUs = { wallUs })
			sink.configure(AudioSink.AudioSinkConfig.Builder(format(MimeTypes.AUDIO_DTS_HD)).build())
			sink.play()
			repeat(300) {
				wallUs += 100_000
				tv.positionUs += 100_000
				sink.getCurrentPositionUs(false)
			}
			// A 5 s rebuffer: the renderer pauses the sink, the clock stands still.
			sink.pause()
			repeat(50) {
				wallUs += 100_000
				sink.getCurrentPositionUs(false)
			}
			sink.play()
			repeat(600) {
				wallUs += 100_000
				tv.positionUs += 100_000
				sink.getCurrentPositionUs(false)
				sink.handleBuffer(accessUnit(), tv.positionUs, 1)
			}
			tv.configured shouldBe listOf(MimeTypes.AUDIO_DTS_HD)
		}

		test("a sink without DTS-HD gets the core from the start") {
			val tv = Bravia()
			every { tv.sink.supportsFormat(any()) } answers { firstArg<Format>().sampleMimeType == MimeTypes.AUDIO_DTS }
			val sink = DtsCoreAudioSink(tv.sink, DtsHdOutput.AUTO)
			sink.configure(AudioSink.AudioSinkConfig.Builder(format(MimeTypes.AUDIO_DTS_HD)).build())
			tv.configured shouldBe listOf(MimeTypes.AUDIO_DTS)
		}
	}
})

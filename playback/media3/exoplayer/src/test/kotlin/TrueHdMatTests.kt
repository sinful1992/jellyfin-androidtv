package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ranges.shouldBeIn
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * A synthetic TrueHD stream (48 kHz, 40 samples per access unit, a major sync every [SYNC_EVERY] units) with
 * splices where both the input timing and the output timing jump, like the joins on a seamless-branching disc.
 */
private object Thd {
	const val UNIT = 128
	const val SYNC_EVERY = 16
	const val UNIT_US = 2500.0 / 3 // 40 samples at 48 kHz

	/** A jump at [atUnit]. [sync]: the unit is a major sync carrying the new output timing (a disc join); false is a
	 * jump in the input timing alone, mid-stream, with the next major sync carrying the output timing as usual. */
	class Splice(val atUnit: Int, val inputJump: Int, val outputJump: Int, val sync: Boolean = true)

	fun stream(units: Int, frameStart: Int = 1000, outputLead: Int = 200, splices: List<Splice> = emptyList()): ByteArray {
		val out = ByteArrayOutputStream()
		var frame = frameStart
		var output = frameStart + 40 + outputLead
		for (i in 0 until units) {
			val splice = splices.firstOrNull { it.atUnit == i }
			if (splice != null) {
				frame += splice.inputJump
				output += splice.outputJump
			}
			out.write(unit(frame and 0xFFFF, if (i % SYNC_EVERY == 0 || splice?.sync == true) output and 0xFFFF else -1))
			frame += 40
			output += 40
		}
		return out.toByteArray()
	}

	/** One access unit; a major sync carries [outputTiming] in its first substream's restart header. */
	private fun unit(frameTime: Int, outputTiming: Int): ByteArray {
		val u = ByteArray(UNIT)
		val words = UNIT / 2
		u[0] = (0xF0 or (words shr 8)).toByte()
		u[1] = words.toByte()
		u[2] = (frameTime shr 8).toByte()
		u[3] = frameTime.toByte()
		if (outputTiming >= 0) {
			u[4] = 0xF8.toByte(); u[5] = 0x72; u[6] = 0x6F; u[7] = 0xBA.toByte()
			u[8] = 0x00 // 48 kHz
			u[20] = 0x10 // one substream
			// directory entry at bit 224 (no extra word), restart header marker 0b11 at bit 240, timing at bit 256
			u[4 + 30] = 0xC0.toByte()
			u[4 + 32] = (outputTiming shr 8).toByte()
			u[4 + 33] = outputTiming.toByte()
		}
		for (i in 40 until UNIT) u[i] = (i * 7).toByte()
		return u
	}

	fun units(d: ByteArray) = sequence {
		var pos = 0
		while (pos + 4 <= d.size) {
			val size = (((d[pos].toInt() and 0xF) shl 8) or (d[pos + 1].toInt() and 0xFF)) * 2
			yield(pos to size)
			pos += size
		}
	}
}

/** Fake inner sink that refuses buffers at random, as the real one does when its track is full. */
private class InnerSink(seed: Int = 1, private val refuse: Double = 0.0) {
	val calls = ArrayList<String>()
	val pts = ArrayList<Long>()
	val bytes = ByteArrayOutputStream()
	var config: AudioSink.AudioSinkConfig? = null
	var params = PlaybackParameters(1.5f)
	var skip = true
	private val random = Random(seed)
	private var held: ByteBuffer? = null

	val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
		when (method.name) {
			"configure" -> { config = args[0] as AudioSink.AudioSinkConfig; null }
			"handleBuffer" -> {
				val buffer = args[0] as ByteBuffer
				check(buffer.order() == ByteOrder.LITTLE_ENDIAN) { "byte order" }
				check(held == null || held === buffer) { "buffer changed while held" }
				if (random.nextDouble() < refuse) {
					held = buffer
					false
				} else {
					held = null
					pts += args[1] as Long
					val data = ByteArray(buffer.remaining())
					buffer.get(data)
					bytes.write(data)
					true
				}
			}
			"getPlaybackParameters" -> params
			"setPlaybackParameters" -> { params = args[0] as PlaybackParameters; null }
			"getSkipSilenceEnabled" -> skip
			"setSkipSilenceEnabled" -> { skip = args[0] as Boolean; null }
			"hasPendingData" -> false
			"flush" -> { held = null; calls += "flush"; null }
			else -> { calls += method.name; null }
		}
	} as AudioSink
}

private fun trueHd(rate: Int = 48_000) = AudioSink.AudioSinkConfig.Builder(
	Format.Builder().setSampleMimeType(MimeTypes.AUDIO_TRUEHD).setSampleRate(rate).setChannelCount(8).build()
).build()

private fun pcm() = AudioSink.AudioSinkConfig.Builder(
	Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
		.setSampleRate(48_000).setChannelCount(2).build()
).build()

/** Feeds the stream in groups of [group] units, like ExoPlayer's TrueHD rechunker. */
private fun feed(sink: AudioSink, data: ByteArray, startUs: Long, group: Int = 16) {
	val units = Thd.units(data).toList()
	units.chunked(group).forEachIndexed { chunkIndex, chunk ->
		val from = chunk.first().first
		val to = chunk.last().let { it.first + it.second }
		val buffer = ByteBuffer.wrap(data.copyOfRange(from, to)).order(ByteOrder.LITTLE_ENDIAN)
		val pts = startUs + (chunkIndex * group * Thd.UNIT_US).toLong()
		var tries = 0
		while (!sink.handleBuffer(buffer, pts, chunk.size)) check(tries++ < 1000) { "stuck" }
		check(!buffer.hasRemaining()) { "not consumed" }
	}
}

private fun packAll(packer: TrueHdMatPacker, data: ByteArray): Pair<Int, ByteArray> {
	val out = ByteArrayOutputStream()
	var dropped = 0
	for ((pos, size) in Thd.units(data)) {
		if (!packer.pack(data, pos, size)) dropped++
		while (true) out.write(packer.pollBurst() ?: break)
	}
	return dropped to out.toByteArray()
}

private fun isPreamble(b: ByteArray, at: Int) =
	b[at] == 0x72.toByte() && b[at + 1] == 0xF8.toByte() && b[at + 2] == 0x1F.toByte() && b[at + 3] == 0x4E.toByte() &&
		b[at + 4].toInt() == 0x16 && b[at + 6] == 0xF0.toByte() && b[at + 7] == 0xEF.toByte()

private const val GOLDEN_RATATOUILLE = "0d5b3a60cfa0ae4bbd7170444dd0334291fa2e0c828fdd7cb8a0c36e562eb9c0" // the packer at 4a7ecf941
private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

class TrueHdMatTests : FunSpec({
	val mat = TrueHdMatPacker.MAT_SIZE
	val splices = listOf(
		Thd.Splice(atUnit = 1600, inputJump = 1556, outputJump = 1556),
		Thd.Splice(atUnit = 4000, inputJump = 1813, outputJump = 1813),
	)

	test("packer: plain stream, every unit packed into whole IEC 61937 bursts") {
		val (dropped, out) = packAll(TrueHdMatPacker(), Thd.stream(6000))
		dropped shouldBe 0
		(out.size % mat) shouldBe 0
		(0 until out.size / mat).all { isPreamble(out, it * mat) }.shouldBeTrue()
		// 24 units of 40 samples = 20 ms per MAT frame
		(out.size / mat) shouldBe 6000 / 24 - 1
	}

	test("packer: splices where both timings jump keep every unit and the burst count") {
		val (dropped, out) = packAll(TrueHdMatPacker(), Thd.stream(6000, splices = splices))
		dropped shouldBe 0
		(out.size / mat) shouldBe packAll(TrueHdMatPacker(), Thd.stream(6000)).second.size / mat
	}

	test("packer: output just behind input at a splice is not a seek with the signed offset (Kodi drops there)") {
		// Output 8 samples behind the input before the join, 40 ahead after it (like the film's 8:49 join).
		val behind = listOf(Thd.Splice(atUnit = 1600, inputJump = 1556, outputJump = 1556 + 48))
		val data = Thd.stream(6000, outputLead = -8, splices = behind)
		packAll(TrueHdMatPacker(signedOffset = true), data).first shouldBe 0
		(packAll(TrueHdMatPacker(signedOffset = false), data).first > 0).shouldBeTrue()
	}

	/*
	 * Task 199: jumps in the input timing alone, mid-stream (the output timing goes on). The reference is FFmpeg's
	 * IEC 61937 muxer after 2db563fac2 ("preserve TrueHD MAT padding across branches", trac #9569/#10948, mpv #13943):
	 * the padding a jump asks for is kept up to two MAT frames (122 848 bytes), because dropping it causes dropouts on
	 * receivers; a jump beyond that, or back, is unusual timing and gets no padding. Every unit is kept either way:
	 * the old packer cleared its state past five frames of padding and dropped units until the next major sync.
	 */
	val keptJumps = listOf("forward 5 ms" to 240, "forward 30 ms" to 1_440, "forward 39 ms (Ratatouille 1:40:51)" to 1_840)
	val unusualJumps = listOf(
		"forward just over 2 frames" to 1_960,
		"forward 200 ms" to 9_600,
		"forward 1 s" to 48_000,
		"back 1 unit" to -80, // a step of -40
		"back 50 ms" to -2_400,
		"back 1 s" to -48_000,
	)
	val plainFrames = packAll(TrueHdMatPacker(), Thd.stream(6000)).second.size / mat
	fun jumped(at: Int, jump: Int) = Thd.stream(6000, splices = listOf(Thd.Splice(atUnit = at, inputJump = jump, outputJump = 0, sync = false)))

	for ((name, jump) in keptJumps) for (at in listOf(1600, 1607)) {
		test("packer: an input timing jump up to 2 frames ($name, unit $at) keeps every unit and its padding") {
			val (dropped, out) = packAll(TrueHdMatPacker(), jumped(at, jump))
			dropped shouldBe 0
			(out.size / mat - plainFrames) shouldBeIn (jump / 960)..(jump / 960 + 1)
		}
	}

	for ((name, jump) in unusualJumps) for (at in listOf(1600, 1607)) {
		test("packer: an unusual input timing jump ($name, unit $at) keeps every unit and adds no padding") {
			val (dropped, out) = packAll(TrueHdMatPacker(), jumped(at, jump))
			dropped shouldBe 0
			(out.size / mat - plainFrames) shouldBeIn 0..1
		}
	}

	test("packer: an input timing jump across the 16 bit wrap keeps every unit") {
		val data = Thd.stream(6000, frameStart = 0xFFFF - 40 * 1500, splices = listOf(Thd.Splice(1600, 3_000, 0, sync = false)))
		packAll(TrueHdMatPacker(), data).first shouldBe 0
	}

	test("sink: a mid-stream input timing jump of any size reports no discontinuity and keeps pts at 20 ms steps") {
		for ((_, jump) in keptJumps + unusualJumps) {
			val inner = InnerSink(seed = 3, refuse = 0.3)
			val sink = TrueHdMatAudioSink(inner.sink, TrueHdMatOutput { true })
			sink.configure(trueHd())
			feed(sink, jumped(1600, jump), startUs = 100_000_000L)
			inner.calls shouldNotContain "handleDiscontinuity"
			inner.pts.zipWithNext().all { (a, b) -> b - a == 20_000L }.shouldBeTrue()
		}
	}

	/*
	 * The real bytes: Ratatouille from the major sync before 1:40:51 (657 units, the film's +1880 sample step off a major
	 * sync at unit 257). Its padding is under two frames, so it packs exactly as before, as FFmpeg 2db563fac2 does.
	 */
	test("packer: Ratatouille 1:40:51 (real bytes) packs every unit, byte for byte as before") {
		val data = TrueHdMatTests::class.java.getResourceAsStream("/rata-100m51s.thd")!!.readBytes()
		Thd.units(data).count() shouldBe 657
		val (dropped, out) = packAll(TrueHdMatPacker(), data)
		dropped shouldBe 0
		sha256(out) shouldBe GOLDEN_RATATOUILLE
	}

	test("packer: junk and short units are refused without output") {
		val packer = TrueHdMatPacker()
		packer.pack(ByteArray(8), 0, 8).shouldBeFalse()
		packer.pack(ByteArray(64), 0, 64).shouldBeFalse() // no major sync seen yet
		packer.pollBurst() shouldBe null
	}

	test("sink: TrueHD goes to the inner sink as 16 bit 8 channel 192 kHz PCM, speed and silence skip off") {
		val inner = InnerSink()
		val sink = TrueHdMatAudioSink(inner.sink, TrueHdMatOutput { true })
		sink.configure(trueHd())
		val format = inner.config!!.format
		format.sampleMimeType shouldBe MimeTypes.AUDIO_RAW
		format.sampleRate shouldBe 192_000
		format.channelCount shouldBe 8
		format.pcmEncoding shouldBe C.ENCODING_PCM_16BIT
		inner.params shouldBe PlaybackParameters.DEFAULT
		inner.skip.shouldBeFalse()
		sink.setPlaybackParameters(PlaybackParameters(2f))
		inner.params shouldBe PlaybackParameters.DEFAULT
	}

	test("sink: across splices and refused buffers, pts rise by exactly 20 ms and no discontinuity is reported") {
		val inner = InnerSink(seed = 7, refuse = 0.5)
		val sink = TrueHdMatAudioSink(inner.sink, TrueHdMatOutput { true })
		sink.configure(trueHd())
		val data = Thd.stream(6000, splices = splices)
		feed(sink, data, startUs = 100_000_000L)
		val out = inner.bytes.toByteArray()
		(0 until mat).all { out[it].toInt() == 0 }.shouldBeTrue() // the sink ramps a silent burst, not a real one
		inner.pts.zipWithNext().all { (a, b) -> b - a == 20_000L }.shouldBeTrue()
		inner.calls shouldNotContain "handleDiscontinuity"
		out.copyOfRange(mat, out.size).contentEquals(packAll(TrueHdMatPacker(), data).second).shouldBeTrue()
	}

	test("sink: start at 0 sends silence first and resyncs; after a flush it starts again on a major sync") {
		val inner = InnerSink()
		val sink = TrueHdMatAudioSink(inner.sink, TrueHdMatOutput { true })
		sink.configure(trueHd())
		feed(sink, Thd.stream(1200), startUs = 0L)
		inner.pts[0] shouldBe -20_000L
		("handleDiscontinuity" in inner.calls).shouldBeTrue()
		sink.flush()
		val before = inner.pts.size
		feed(sink, Thd.stream(1200, frameStart = 30_000), startUs = 50_000_000L)
		(inner.pts[before] in 49_980_000L..50_000_000L).shouldBeTrue()
	}

	test("sink: back to PCM restores format, speed and silence skip, and passes PCM untouched") {
		val inner = InnerSink()
		val output = TrueHdMatOutput { true }
		val sink = TrueHdMatAudioSink(inner.sink, output)
		sink.configure(trueHd())
		output.packing.shouldBeTrue()
		sink.setPlaybackParameters(PlaybackParameters(1.25f))
		sink.configure(pcm())
		inner.config!!.format.sampleRate shouldBe 48_000
		inner.params.speed shouldBe 1.25f
		inner.skip.shouldBeTrue()
		output.packing.shouldBeFalse()
		sink.handleBuffer(ByteBuffer.wrap(ByteArray(64)).order(ByteOrder.LITTLE_ENDIAN), 1L, 1).shouldBeTrue()
		inner.pts.last() shouldBe 1L
	}

	test("sink: a device without IEC 61937 gets raw TrueHD exactly as before") {
		val inner = InnerSink()
		TrueHdMatAudioSink(inner.sink, TrueHdMatOutput { false }).configure(trueHd())
		inner.config!!.format.sampleMimeType shouldBe MimeTypes.AUDIO_TRUEHD
	}

	test("sink: the 44.1 kHz family goes out at 176.4 kHz") {
		val inner = InnerSink()
		TrueHdMatAudioSink(inner.sink, TrueHdMatOutput { true }).configure(trueHd(44_100))
		inner.config!!.format.sampleRate shouldBe 176_400
	}

	test("sink: junk buffers are taken and nothing is sent") {
		val inner = InnerSink()
		val sink = TrueHdMatAudioSink(inner.sink, TrueHdMatOutput { true })
		sink.configure(trueHd())
		for (junk in listOf(ByteArray(0), ByteArray(3), ByteArray(500), ByteArray(500) { -1 }, Random(3).nextBytes(5000))) {
			sink.handleBuffer(ByteBuffer.wrap(junk).order(ByteOrder.LITTLE_ENDIAN), 0L, 1).shouldBeTrue()
		}
		inner.pts.isEmpty().shouldBeTrue()
	}

	test("output: the track is swapped to IEC 61937 only while packing and only for the burst format") {
		val output = TrueHdMatOutput { true }
		output.isBurstTrack(C.ENCODING_PCM_16BIT, 192_000, TrueHdMatOutput.CHANNEL_MASK).shouldBeFalse()
		output.packing = true
		output.isBurstTrack(C.ENCODING_PCM_16BIT, 192_000, TrueHdMatOutput.CHANNEL_MASK).shouldBeTrue()
		output.isBurstTrack(C.ENCODING_PCM_16BIT, 176_400, TrueHdMatOutput.CHANNEL_MASK).shouldBeTrue()
		output.isBurstTrack(C.ENCODING_PCM_16BIT, 48_000, TrueHdMatOutput.CHANNEL_MASK).shouldBeFalse()
		output.isBurstTrack(C.ENCODING_PCM_16BIT, 192_000, 12).shouldBeFalse()
		output.isBurstTrack(C.ENCODING_DOLBY_TRUEHD, 192_000, TrueHdMatOutput.CHANNEL_MASK).shouldBeFalse()
	}

	test("gate: only Amlogic on Android 10 or newer; the Bravia and Shield keep today's sink") {
		TrueHdMatOutput.isAmlogic("amlogic", 34).shouldBeTrue()
		TrueHdMatOutput.isAmlogic("AMLOGIC", 29).shouldBeTrue()
		TrueHdMatOutput.isAmlogic("amlogic", 28).shouldBeFalse()
		TrueHdMatOutput.isAmlogic("mt5895", 34).shouldBeFalse() // Bravia (MediaTek)
		TrueHdMatOutput.isAmlogic("darcy", 30).shouldBeFalse() // Shield
		TrueHdMatOutput.isAmlogic("", 34).shouldBeFalse()
	}
})

package org.jellyfin.playback.media3.exoplayer

import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

// HEVC NAL unit types used below.
private const val TRAIL_R = 1
private const val IDR = 19
private const val CRA = 21
private const val VPS = 32
private const val SPS = 33
private const val PPS = 34
private const val AUD = 35
private const val PREFIX_SEI = 39
private const val RPU = 62
private const val EL = 63

/** One NAL unit with a 4-byte start code: header for [type], then [body]. */
private fun nal(type: Int, vararg body: Int, shortStartCode: Boolean = false): ByteArray {
	val code = if (shortStartCode) byteArrayOf(0, 0, 1) else byteArrayOf(0, 0, 0, 1)
	return code + byteArrayOf((type shl 1).toByte(), 1) + body.map { it.toByte() }.toByteArray()
}

/** An enhancement layer unit: NAL 63 carrying an EL unit of [innerType]. */
private fun el(innerType: Int, vararg body: Int) = nal(EL, (innerType shl 1), 9, *body)

private fun au(vararg units: ByteArray) = units.reduce { a, b -> a + b }

/** Run [data] through [join] at an offset, as a codec input buffer would hold it. */
private fun DolbyVisionElJoin.run(data: ByteArray, offset: Int = 3): ByteArray {
	val buffer = ByteBuffer.allocate(data.size + offset + 8)
	buffer.position(offset)
	buffer.put(data)
	buffer.limit(offset + data.size)
	buffer.position(offset)
	filter(buffer)
	val out = ByteArray(buffer.limit() - offset)
	for (i in out.indices) out[i] = buffer.get(offset + i)
	return out
}

// Layouts from the files (linus #9027, #9034, #9045).
// A base keyframe whose EL picture continues from earlier frames: EL PPS, pic_timing SEI, trailing picture.
private val midElKeyframe = au(nal(CRA, 7, 7), el(PPS, 4), el(PREFIX_SEI, 1, 3), el(TRAIL_R, 8, 8), nal(RPU, 5))
private val midElKeyframeBase = au(nal(CRA, 7, 7), nal(RPU, 5))

// The EL's own start off the base keyframe: full setup and SEI 129, 0, 1, 6, 137, 144, then an IRAP.
private fun elStartAu(irap: Int) = au(
	nal(TRAIL_R, 6),
	el(VPS, 1), el(SPS, 2), el(PPS, 4),
	el(PREFIX_SEI, 129), el(PREFIX_SEI, 0), el(PREFIX_SEI, 1), el(PREFIX_SEI, 6), el(PREFIX_SEI, 137), el(PREFIX_SEI, 144),
	el(irap, 8),
	nal(RPU, 5),
)
private val elTrailing = au(nal(TRAIL_R, 6), el(TRAIL_R, 8), nal(RPU, 5))
private val elTrailingBase = au(nal(TRAIL_R, 6), nal(RPU, 5))

// A key where both layers start together: EL AUD first, then the full set.
private val warfareKey = au(
	el(AUD, 80), el(VPS, 1), el(SPS, 2), el(PPS, 4), el(PREFIX_SEI, 0), el(PREFIX_SEI, 1),
	nal(CRA, 7), el(CRA, 8), nal(RPU, 5),
)

class DolbyVisionElJoinTests : FunSpec({
	test("a resume onto an EL picture without its start keeps only the base layer and RPU") {
		DolbyVisionElJoin().run(midElKeyframe).toList() shouldBe midElKeyframeBase.toList()
	}

	test("EL units between base units are removed and the rest moved up") {
		val mixed = au(nal(CRA, 7), el(PPS, 4), el(PREFIX_SEI, 1, 3), el(TRAIL_R, 8), nal(RPU, 5), nal(PREFIX_SEI, 2))
		DolbyVisionElJoin().run(mixed).toList() shouldBe au(nal(CRA, 7), nal(RPU, 5), nal(PREFIX_SEI, 2)).toList()
	}

	test("the EL joins whole at its own CRA start and passes from then on") {
		val join = DolbyVisionElJoin()
		join.run(midElKeyframe)
		join.run(elTrailing).toList() shouldBe elTrailingBase.toList()
		join.run(elStartAu(CRA)).toList() shouldBe elStartAu(CRA).toList()
		join.waiting shouldBe false
		join.run(elTrailing).toList() shouldBe elTrailing.toList()
		join.run(midElKeyframe).toList() shouldBe midElKeyframe.toList()
	}

	test("the EL joins at an IDR start too") {
		val join = DolbyVisionElJoin()
		join.run(midElKeyframe)
		join.run(elStartAu(IDR)).toList() shouldBe elStartAu(IDR).toList()
		join.waiting shouldBe false
	}

	test("a key where both layers start together is untouched, EL AUD and SEI included") {
		val join = DolbyVisionElJoin()
		join.run(warfareKey).toList() shouldBe warfareKey.toList()
		join.waiting shouldBe false
	}

	test("an EL IRAP without an EL SPS in its access unit does not join") {
		val join = DolbyVisionElJoin()
		val irapNoSps = au(nal(TRAIL_R, 6), el(PPS, 4), el(PREFIX_SEI, 0), el(CRA, 8), nal(RPU, 5))
		join.run(irapNoSps).toList() shouldBe elTrailingBase.toList()
		join.waiting shouldBe true
	}

	test("EL parameter sets without an EL IRAP do not join") {
		val join = DolbyVisionElJoin()
		val setsOnly = au(nal(CRA, 7), el(VPS, 1), el(SPS, 2), el(PPS, 4), el(TRAIL_R, 8))
		join.run(setsOnly).toList() shouldBe nal(CRA, 7).toList()
		join.waiting shouldBe true
	}

	test("a stream whose EL never starts stays base layer and RPU, every frame") {
		val join = DolbyVisionElJoin()
		repeat(50) { join.run(midElKeyframe).toList() shouldBe midElKeyframeBase.toList() }
		join.waiting shouldBe true
	}

	test("3-byte start codes are handled") {
		val short = au(nal(CRA, 7, shortStartCode = true), el(TRAIL_R, 8).let { it.copyOfRange(1, it.size) })
		DolbyVisionElJoin().run(short).toList() shouldBe nal(CRA, 7, shortStartCode = true).toList()
	}

	test("a buffer without start codes, or empty, is left alone") {
		DolbyVisionElJoin().run(byteArrayOf(9, 9, 9, 9)).toList() shouldBe listOf<Byte>(9, 9, 9, 9)
		DolbyVisionElJoin().run(byteArrayOf()).toList() shouldBe emptyList()
	}

	test("only profile 7 Dolby Vision streams are joined") {
		DolbyVisionElJoin.appliesTo("dvhe.07.06") shouldBe true
		DolbyVisionElJoin.appliesTo("dvh1.07.06") shouldBe true
		DolbyVisionElJoin.appliesTo("dvhe.08.06") shouldBe false
		DolbyVisionElJoin.appliesTo("dvhe.05.06") shouldBe false
		DolbyVisionElJoin.appliesTo("hvc1.2.4.L153") shouldBe false
		DolbyVisionElJoin.appliesTo(null) shouldBe false
	}

	test("the adapter queues the filtered size at the same offset, and end of stream as it is") {
		val input = ByteBuffer.allocate(256)
		val queued = mutableListOf<List<Any>>()
		val inner = Proxy.newProxyInstance(
			MediaCodecAdapter::class.java.classLoader,
			arrayOf(MediaCodecAdapter::class.java),
		) { _, method, args ->
			when (method.name) {
				"getInputBuffer" -> input
				"queueInputBuffer" -> {
					queued += args.toList()
					null
				}
				else -> null
			}
		} as MediaCodecAdapter
		val adapter = DolbyVisionElJoinAdapter(inner)

		val offset = 5
		input.position(offset)
		input.put(midElKeyframe)
		adapter.queueInputBuffer(0, offset, midElKeyframe.size, 1_000L, 0)
		adapter.queueInputBuffer(1, 0, 0, 2_000L, 4)

		queued[0] shouldBe listOf(0, offset, midElKeyframeBase.size, 1_000L, 0)
		val out = ByteArray(midElKeyframeBase.size).also { for (i in it.indices) it[i] = input.get(offset + i) }
		out.toList() shouldBe midElKeyframeBase.toList()
		queued[1] shouldBe listOf(1, 0, 0, 2_000L, 4)
	}
})

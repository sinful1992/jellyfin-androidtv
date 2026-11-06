package org.jellyfin.playback.media3.exoplayer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer

// HEVC NAL unit types used below.
private const val TRAIL_R = 1
private const val CRA = 21
private const val IDR = 19
private const val VPS = 32
private const val SPS = 33
private const val PPS = 34
private const val PREFIX_SEI = 39
private const val RPU = 62
private const val EL = 63

/** One NAL unit with a 4-byte start code: header for [type], then [body]. */
private fun nal(type: Int, vararg body: Int, shortStartCode: Boolean = false): ByteArray {
	val code = if (shortStartCode) byteArrayOf(0, 0, 1) else byteArrayOf(0, 0, 0, 1)
	return code + byteArrayOf((type shl 1).toByte(), 1) + body.map { it.toByte() }.toByteArray()
}

/** An enhancement layer unit: NAL 63 carrying an EL unit of [innerType] (layer 1). */
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

class DolbyVisionElJoinTests : FunSpec({
	// Alice at 274.48 s: a base-layer keyframe whose EL picture continues from earlier frames.
	val midElKeyframe = au(nal(CRA, 7, 7), nal(RPU, 5), el(PPS, 4), el(PREFIX_SEI, 3, 3, 3), el(TRAIL_R, 8, 8))
	val baseOnly = au(nal(CRA, 7, 7), nal(RPU, 5))

	// The EL's own start: parameter sets, SEI and an IRAP picture, as at 0 s and 362.65 s.
	val elStart = au(nal(TRAIL_R, 6), nal(RPU, 5), el(VPS, 1), el(SPS, 2), el(PPS, 4), el(PREFIX_SEI, 0, 1), el(IDR, 8))
	val elTrailing = au(nal(TRAIL_R, 6), nal(RPU, 5), el(TRAIL_R, 8))

	test("a resume onto an EL picture without its start drops only the EL units") {
		DolbyVisionElJoin().run(midElKeyframe).toList() shouldBe baseOnly.toList()
	}

	test("the EL is passed whole from its own start, and from then on") {
		val join = DolbyVisionElJoin()
		join.run(midElKeyframe)
		join.run(elTrailing).toList() shouldBe au(nal(TRAIL_R, 6), nal(RPU, 5)).toList()
		join.run(elStart).toList() shouldBe elStart.toList()
		join.waiting shouldBe false
		join.run(elTrailing).toList() shouldBe elTrailing.toList()
		join.run(midElKeyframe).toList() shouldBe midElKeyframe.toList()
	}

	test("a start at the EL's own start changes nothing") {
		DolbyVisionElJoin().run(elStart).toList() shouldBe elStart.toList()
	}

	test("a stream whose layers start together is untouched (Warfare)") {
		val join = DolbyVisionElJoin()
		val warfareKey = au(nal(CRA, 7), nal(RPU, 5), el(VPS, 1), el(SPS, 2), el(PPS, 4), el(CRA, 8))
		join.run(warfareKey).toList() shouldBe warfareKey.toList()
	}

	test("a stream whose EL never starts stays base layer and RPU, every frame") {
		val join = DolbyVisionElJoin()
		repeat(50) { join.run(midElKeyframe).toList() shouldBe baseOnly.toList() }
		join.waiting shouldBe true
	}

	test("EL parameter sets without an EL IRAP after them are dropped") {
		val join = DolbyVisionElJoin()
		val setsOnly = au(nal(CRA, 7), el(VPS, 1), el(SPS, 2), el(PPS, 4), el(TRAIL_R, 8))
		join.run(setsOnly).toList() shouldBe nal(CRA, 7).toList()
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
})

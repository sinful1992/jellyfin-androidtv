package org.jellyfin.playback.media3.exoplayer

import java.util.ArrayDeque

/**
 * Packs TrueHD access units into MAT frames inside IEC 61937 bursts (16 bit little endian, 192 kHz, 8 channels).
 *
 * Ported from Kodi's CPackerMAT (GPL-2.0-or-later, (C) 2024 Team Kodi), whose seamless-branch handling comes
 * from LAV Filters by Hendrik Leppkes. One difference: the offset between output and input timing is read as
 * signed, so a splice where the output falls a little behind the input is not taken for a seek (Kodi drops
 * audio there until the next major sync).
 */
class TrueHdMatPacker(private val signedOffset: Boolean = true) {
	private var init = false
	private var rateBits = 0
	private var outputTiming = 0
	private var outputTimingValid = false
	private var prevFrameTime = 0
	private var prevFrameTimeValid = false
	private var matFrameSize = 0
	private var prevMatFrameSize = 0
	private var padding = 0
	private var outputTimeOffset = 0

	private var buffer = ByteArray(0)
	private var count = 0
	private val output = ArrayDeque<ByteArray>()

	/** Next finished MAT frame (61440 bytes, big endian payload, burst header left zero), or null. */
	fun pollMat(): ByteArray? = output.poll()

	/** Next finished IEC 61937 burst, or null. */
	fun pollBurst(): ByteArray? = output.poll()?.let(::toBurst)

	fun reset() {
		clearState(false)
		output.clear()
	}

	private fun clearState(keepInit: Boolean) {
		init = keepInit
		rateBits = 0
		outputTiming = 0
		outputTimingValid = false
		prevFrameTime = 0
		prevFrameTimeValid = false
		matFrameSize = 0
		prevMatFrameSize = 0
		padding = 0
		outputTimeOffset = 0
		count = 0
	}

	/** Packs the access unit at [offset]. Returns false when it was dropped (not started on a major sync yet, or a seek). */
	fun pack(data: ByteArray, offset: Int, size: Int): Boolean {
		if (size < 10) return false
		val majorSync = isMajorSync(data, offset)
		var newOutputTiming = -1
		if (majorSync) {
			val info = parseMajorSync(data, offset, size)
			rateBits = info?.rateBits ?: (data[offset + 8].toInt() and 0xFF shr 4)
			newOutputTiming = info?.outputTiming ?: -1
		} else if (!prevFrameTimeValid) return false

		val frameTime = u16(data, offset + 2)
		val frameSamples = 40 shl (rateBits and 7)
		val bytesPerSample = 64 shr (rateBits and 7)
		var space = 0

		outputTiming = (outputTiming + frameSamples) and 0xFFFF
		if (newOutputTiming >= 0) {
			if (outputTimingValid && newOutputTiming != outputTiming) {
				// A splice: start timing again, and add what the output buffer gains as padding.
				prevFrameTimeValid = false
				space = frameSamples * bytesPerSample
				val current = offsetOf(newOutputTiming, frameSamples, frameTime)
				if (outputTimeOffset >= current) padding += (outputTimeOffset - current) * bytesPerSample
			}
			outputTiming = newOutputTiming
			outputTimingValid = true
		}

		if (prevFrameTimeValid) space = ((frameTime - prevFrameTime) and 0xFFFF) * bytesPerSample
		if (space < prevMatFrameSize) space = (prevMatFrameSize + bytesPerSample - 1) / bytesPerSample * bytesPerSample
		padding += space - prevMatFrameSize

		if (padding > MAT_SIZE * 5) {
			clearState(true)
			return false
		}

		if (outputTimingValid) outputTimeOffset = offsetOf(outputTiming, frameSamples, frameTime)
		prevFrameTime = frameTime
		prevFrameTimeValid = true

		if (count == 0) {
			writeHeader()
			if (!init) {
				init = true
				matFrameSize = START_CODE.size
			}
		}
		while (padding > 0) {
			writePadding()
			if (count == MAT_SIZE) {
				flush()
				writeHeader()
			}
		}

		var remaining = fill(data, offset, size, false)
		if (remaining > 0 || count == MAT_SIZE) {
			flush()
			if (remaining > 0) {
				writeHeader()
				remaining = fill(data, offset + size - remaining, remaining, false)
				check(remaining == 0)
			}
		}
		prevMatFrameSize = matFrameSize
		matFrameSize = 0
		return true
	}

	private fun offsetOf(output: Int, frameSamples: Int, frameTime: Int): Int {
		val diff = (((output - frameSamples) and 0xFFFF) - frameTime) and 0xFFFF
		return if (signedOffset) diff.toShort().toInt() else diff
	}

	private fun writeHeader() {
		buffer = ByteArray(MAT_SIZE)
		START_CODE.copyInto(buffer, BURST_HEADER)
		count = BURST_HEADER + START_CODE.size
		matFrameSize += count
		// The codes count as padding when some is due.
		if (padding > count) {
			padding -= count
			matFrameSize = 0
		} else if (padding > 0) {
			matFrameSize = count - padding
			padding = 0
		}
	}

	private fun writePadding() {
		val remaining = fill(null, 0, padding, true)
		if (remaining >= 0) {
			padding = remaining
			matFrameSize = 0
		} else {
			padding = 0
			matFrameSize = -remaining
		}
	}

	private fun append(data: ByteArray?, offset: Int, size: Int) {
		data?.copyInto(buffer, count, offset, offset + size)
		matFrameSize += size
		count += size
	}

	private fun fill(data: ByteArray?, offset: Int, size: Int, isPadding: Boolean): Int {
		if (count >= LIMIT) return size
		var remaining = size
		if (count <= MIDDLE && count + size > MIDDLE) {
			val before = MIDDLE - count
			append(data, offset, before)
			remaining -= before
			append(MIDDLE_CODE, 0, MIDDLE_CODE.size)
			if (isPadding) remaining -= MIDDLE_CODE.size
			if (remaining > 0) remaining = fill(data, offset + before, remaining, isPadding)
			return remaining
		}
		if (count + size >= LIMIT) {
			val before = LIMIT - count
			append(data, offset, before)
			remaining -= before
			append(END_CODE, 0, END_CODE.size)
			if (isPadding) remaining -= END_CODE.size
			return remaining
		}
		append(data, offset, size)
		return 0
	}

	private fun flush() {
		if (count == 0) return
		output.add(buffer)
		count = 0
	}

	private class MajorSync(val rateBits: Int, val outputTiming: Int)

	companion object {
		const val MAT_SIZE = 61440
		const val BURST_HEADER = 8
		private const val LIMIT = MAT_SIZE - 24
		private const val MIDDLE = 30708 + BURST_HEADER

		private fun codes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
		private val START_CODE = codes(
			0x07, 0x9E, 0x00, 0x03, 0x84, 0x01, 0x01, 0x01, 0x80, 0x00, 0x56, 0xA5, 0x3B, 0xF4, 0x81, 0x83, 0x49, 0x80, 0x77, 0xE0
		)
		private val MIDDLE_CODE = codes(0xC3, 0xC1, 0x42, 0x49, 0x3B, 0xFA, 0x82, 0x83, 0x49, 0x80, 0x77, 0xE0)
		private val END_CODE = codes(
			0xC3, 0xC2, 0xC0, 0xC4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x97, 0x11, 0, 0, 0, 0, 0, 0, 0, 0
		)

		private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF shl 8) or (d[o + 1].toInt() and 0xFF)

		fun isMajorSync(d: ByteArray, o: Int) =
			d[o + 4] == 0xF8.toByte() && d[o + 5] == 0x72.toByte() && d[o + 6] == 0x6F.toByte() && d[o + 7] == 0xBA.toByte()

		/** Sample rate bits, and the output timing (-1 when the first substream has no restart header). */
		private fun parseMajorSync(d: ByteArray, o: Int, size: Int): MajorSync? {
			if (size < 32) return null
			var syncSize = 28
			if (d[o + 29].toInt() and 1 != 0) syncSize += 2 + (d[o + 30].toInt() and 0xFF shr 4) * 2
			if (syncSize > size) return null
			fun bit(pos: Int): Int {
				val byte = 4 + (pos shr 3)
				return if (byte >= size) 0 else (d[o + byte].toInt() shr (7 - (pos and 7))) and 1
			}
			fun bits(pos: Int, n: Int) = (0 until n).fold(0) { v, i -> v shl 1 or bit(pos + i) }
			val rate = bits(32, 4)
			var pos = 32 + 4 + 92
			val substreams = bits(pos, 4)
			pos += 4 + 4 + (syncSize - 17) * 8
			repeat(substreams) { pos += 16 + if (bit(pos) == 1) 16 else 0 }
			val timing = if (substreams > 0 && bits(pos, 2) == 3) bits(pos + 16, 16) else -1
			return MajorSync(rate, timing)
		}

		/** Adds the IEC 61937 preamble and swaps the payload to little endian words. */
		fun toBurst(mat: ByteArray): ByteArray {
			val out = ByteArray(MAT_SIZE)
			codes(0x72, 0xF8, 0x1F, 0x4E, 0x16, 0x00, (MAT_SIZE - 16) and 0xFF, (MAT_SIZE - 16) shr 8).copyInto(out)
			for (i in BURST_HEADER until MAT_SIZE step 2) {
				out[i] = mat[i + 1]
				out[i + 1] = mat[i]
			}
			return out
		}
	}
}

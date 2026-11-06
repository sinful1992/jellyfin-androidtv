package org.jellyfin.playback.media3.exoplayer

import java.nio.ByteBuffer

/**
 * Holds back the enhancement layer (EL) of a Dolby Vision profile 7 stream after the decoder starts,
 * until the EL reaches a start point of its own. One instance per codec.
 *
 * In a single-track profile 7 file the EL travels inside the base layer's access units as NAL units
 * of type 63, each carrying one EL NAL unit. A file may start an EL GOP at a different frame than the
 * base layer, so a base-layer keyframe a resume or seek lands on can carry an EL picture that
 * continues from earlier frames, with no EL parameter sets before it. NVIDIA's Dolby Vision decoder
 * (Shield, OMX.Nvidia.DOVI.decode) then crashes the media codec process while parsing that EL's SEI;
 * Alice Through the Looking Glass crashed on every resume (task 127). Dropping those EL units until the
 * EL's next IRAP picture lets the base layer and its RPU (type 62) play from the first frame, in Dolby
 * Vision, with the EL joining at its own start, which is how a decoder starting at that frame has to
 * treat it anyway.
 *
 * Works on Annex B data (start codes), as the Matroska and MP4 extractors hand HEVC to the codec.
 */
class DolbyVisionElJoin {
	/** True while EL units are being held back. */
	var waiting = true
		private set

	/**
	 * Filter one access unit in place, between [ByteBuffer.position] and [ByteBuffer.limit]. EL units
	 * before the EL's first IRAP are removed and the limit is moved back by what was removed; the
	 * rest of the buffer is left byte for byte as it was.
	 */
	fun filter(data: ByteBuffer) {
		if (!waiting) return

		val start = data.position()
		val end = data.limit()
		val units = findUnits(data, start, end)
		if (units.isEmpty()) return

		var write = units.first().begin
		for (unit in units) {
			val keep = if (unit.type != NAL_UNSPEC63 || !waiting) {
				true
			} else if (unit.innerType in IRAP_TYPES || unit.innerType in PARAMETER_SET_TYPES && startsEl(units, unit)) {
				waiting = false
				true
			} else {
				false
			}

			if (keep) {
				if (write != unit.begin) {
					for (i in 0 until unit.end - unit.begin) data.put(write + i, data.get(unit.begin + i))
				}
				write += unit.end - unit.begin
			}
		}
		data.limit(write)
	}

	/** EL parameter sets keep the EL only when an EL IRAP follows them in the same access unit. */
	private fun startsEl(units: List<Unit>, from: Unit): Boolean =
		units.dropWhile { it !== from }.any { it.type == NAL_UNSPEC63 && it.innerType in IRAP_TYPES }

	/** One NAL unit with its start code, [begin] inclusive to [end] exclusive. */
	private class Unit(val begin: Int, val end: Int, val type: Int, val innerType: Int)

	private fun findUnits(data: ByteBuffer, start: Int, end: Int): List<Unit> {
		val codes = mutableListOf<Pair<Int, Int>>() // start code offset to payload offset
		var i = start
		while (i + 3 <= end) {
			if (data.get(i) == ZERO && data.get(i + 1) == ZERO && data.get(i + 2) == ONE) {
				val codeStart = if (i > start && data.get(i - 1) == ZERO) i - 1 else i
				codes += codeStart to i + 3
				i += 3
			} else {
				i++
			}
		}

		return codes.mapIndexed { index, (begin, payload) ->
			val unitEnd = codes.getOrNull(index + 1)?.first ?: end
			val type = if (payload < unitEnd) (data.get(payload).toInt() shr 1) and 0x3F else -1
			val innerType = if (type == NAL_UNSPEC63 && payload + 2 < unitEnd) {
				(data.get(payload + 2).toInt() shr 1) and 0x3F
			} else -1
			Unit(begin, unitEnd, type, innerType)
		}
	}

	companion object {
		private const val ZERO: Byte = 0
		private const val ONE: Byte = 1
		const val NAL_UNSPEC63 = 63
		private val IRAP_TYPES = 16..23
		private val PARAMETER_SET_TYPES = 32..34

		/** Whether [codecs] (a Format's codecs string) is a Dolby Vision profile 7 stream. */
		fun appliesTo(codecs: String?): Boolean =
			codecs != null && (codecs.startsWith("dvhe.07") || codecs.startsWith("dvh1.07"))
	}
}

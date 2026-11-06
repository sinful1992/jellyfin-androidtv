package org.jellyfin.playback.media3.exoplayer

import java.nio.ByteBuffer

/**
 * Holds back the enhancement layer (EL) of a Dolby Vision profile 7 stream after the decoder is
 * created, until the EL reaches a start point of its own. One instance per codec.
 *
 * In a single-track profile 7 file the EL travels inside the base layer's access units as NAL units
 * of type 63, each carrying one EL NAL unit. Some files start EL GOPs at different frames from the
 * base layer's, so the base-layer keyframe a resume lands on can carry an EL picture that continues
 * from earlier frames, with an EL PPS and picture timing SEI but no EL SPS or buffering period.
 * NVIDIA's Dolby Vision decoder (SHIELD, OMX.Nvidia.DOVI.decode) crashes the media codec process
 * while parsing that SEI, on every such resume. Dropping the EL units until an access unit that both
 * starts the EL (an IRAP picture) and carries its SPS lets the base layer and its RPU (type 62) play
 * from the first frame, in Dolby Vision, with the EL joining at its own start.
 *
 * The gate is the stream, not the decoder: any decoder given such a stream skips the EL until its
 * next start (under a second in the files measured), and a stream whose EL starts with the base
 * layer is passed untouched. A seek while playing keeps the decoder, which already has the EL's
 * setup, so only a new codec is held back.
 *
 * Works on Annex B data (start codes), as the Matroska and MP4 extractors hand HEVC to the codec.
 */
class DolbyVisionElJoin {
	/** True while EL units are being held back. */
	var waiting = true
		private set

	/**
	 * Filter one access unit in place, between [ByteBuffer.position] and [ByteBuffer.limit]. Until the
	 * EL starts, its units are removed and the limit is moved back by what was removed; the access
	 * unit that starts it, and every one after, is left byte for byte as it was.
	 */
	fun filter(data: ByteBuffer) {
		if (!waiting) return

		val units = findUnits(data, data.position(), data.limit())
		val el = units.filter { it.type == NAL_UNSPEC63 }
		if (el.isEmpty()) return

		if (el.any { it.innerType in IRAP_TYPES } && el.any { it.innerType == SPS }) {
			waiting = false
			return
		}

		var write = units.first().begin
		for (unit in units) {
			if (unit.type == NAL_UNSPEC63) continue
			if (write != unit.begin) {
				for (i in 0 until unit.end - unit.begin) data.put(write + i, data.get(unit.begin + i))
			}
			write += unit.end - unit.begin
		}
		data.limit(write)
	}

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
		private const val SPS = 33

		/** Whether [codecs] (a Format's codecs string) is a Dolby Vision profile 7 stream. */
		fun appliesTo(codecs: String?): Boolean =
			codecs != null && (codecs.startsWith("dvhe.07") || codecs.startsWith("dvh1.07"))
	}
}

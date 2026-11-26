package org.jellyfin.playback.media3.exoplayer

import io.kotest.core.spec.style.FunSpec
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Task 195: runs the packer plus the sink's drift check over a real TrueHD track, from one or many start points.
 * Needs T195_DIR with <T195_NAME>.thd (raw track) and packets.csv (ffprobe pts_time,size,pos per mkv block);
 * skipped without it. T195_CLIP_S is the film time of the track's first block (Ratatouille clip: 460).
 * T195_FROM_S/T195_TO_S add every major sync in that track-time range as a start. Writes
 * <T195_NAME>-results.txt and <T195_NAME>-drift.csv (worst drift per second, first start) to T195_DIR.
 */
class TrueHdMatFilmTests : FunSpec({
	val dir = System.getenv("T195_DIR")?.let(::File)
	val name = System.getenv("T195_NAME") ?: "rata-740"
	test("film drift and frame structure").config(enabled = dir != null && File(dir, "$name.thd").exists()) {
		val thdFile = RandomAccessFile(File(dir!!, "$name.thd"), "r")
		val csv = File(dir, "packets.csv")
		val count = csv.useLines { lines -> lines.count { it.isNotBlank() } }
		val pts = LongArray(count)
		val len = IntArray(count)
		csv.useLines { lines ->
			var k = 0
			for (line in lines) {
				if (line.isBlank()) continue
				val a = line.indexOf(',')
				val b = line.indexOf(',', a + 1)
				pts[k] = (line.substring(0, a).toDouble() * 1_000_000).toLong()
				len[k] = line.substring(a + 1, if (b < 0) line.length else b).toInt()
				k++
			}
		}
		val off = LongArray(count)
		for (i in 1 until count) off[i] = off[i - 1] + len[i - 1]
		check(off.last() + len.last() == thdFile.length()) { "packets do not add up to the track: ${off.last() + len.last()} vs ${thdFile.length()}" }
		val unit = ByteArray(65536)
		fun read(i: Int): ByteArray {
			thdFile.seek(off[i])
			thdFile.readFully(unit, 0, len[i])
			return unit
		}

		val out = StringBuilder()
		val burstUs = 20_000L
		val unitUs = 1_000_000.0 * 40 * 4 / 192_000
		val maxDrift = 150_000L
		val clipStartS = System.getenv("T195_CLIP_S")?.toDouble() ?: 460.0
		val fromUs = System.getenv("T195_FROM_S")?.toDouble()?.let { (it * 1e6).toLong() }
		val toUs = System.getenv("T195_TO_S")?.toDouble()?.let { (it * 1e6).toLong() }

		fun film(us: Long) = (clipStartS + us / 1_000_000.0).let { "%d:%06.3f".format((it / 60).toInt(), it % 60) }

		val syncs = (0 until count).filter { len[it] >= 10 && TrueHdMatPacker.isMajorSync(read(it), 0) }
		val starts = listOf(syncs.first()) +
			if (fromUs != null && toUs != null) syncs.filter { pts[it] in fromUs..toUs } else emptyList()
		out.appendLine("syncs=${syncs.size} starts=${starts.size} units=$count")

		var badFrames = 0
		for ((n, start) in starts.withIndex()) {
			val packer = TrueHdMatPacker()
			var frames = 0
			var packedTo = Long.MIN_VALUE
			var dropped = false
			var maxAbs = 0L
			var maxAt = 0L
			val anchors = mutableListOf<String>()
			val drops = mutableListOf<String>()
			val perSecond = if (n == 0) sortedMapOf<Long, Long>() else null
			val mat = if (n == 0 && System.getenv("T195_MAT") != null) File(dir, "$name-start0.mat").outputStream().buffered() else null
			var i = start
			while (i < count) {
				// media3's TrueHD rechunker hands the sink 16 blocks at a time, at the first block's time.
				val chunkEnd = minOf(i + 16, count)
				val bufPts = pts[i]
				for ((index, u) in (i until chunkEnd).withIndex()) {
					if (!packer.pack(read(u), 0, len[u])) {
						if (!dropped) drops += film(pts[u])
						dropped = true
						continue
					}
					val unitTime = bufPts + (index * unitUs).toLong()
					if (packedTo == Long.MIN_VALUE) {
						packedTo = unitTime
					} else {
						val drift = packedTo - unitTime
						if (abs(drift) > maxAbs) {
							maxAbs = abs(drift)
							maxAt = unitTime
						}
						perSecond?.merge(unitTime / 1_000_000, drift) { a, b -> if (abs(b) > abs(a)) b else a }
						if (dropped || abs(drift) > maxDrift) {
							anchors += "${film(unitTime)} drift=${drift / 1000}ms${if (dropped) " after drop" else ""}"
							packedTo = unitTime
						}
					}
					dropped = false
					while (true) {
						val f = packer.pollMat() ?: break
						frames++
						packedTo += burstUs
						if (f.size != TrueHdMatPacker.MAT_SIZE) badFrames++
						mat?.write(f)
					}
				}
				i = chunkEnd
			}
			mat?.close()
			perSecond?.let { m ->
				File(dir, "$name-drift.csv").writeText(
					m.entries.joinToString("\n", "film,drift_ms\n") { (s, d) -> "${film(s * 1_000_000)},${d / 1000}" }
				)
			}
			out.appendLine(
				"start ${film(pts[start])} frames=$frames maxDrift=${maxAbs / 1000}ms at ${film(maxAt)} " +
					"reanchors=${anchors.size} ${anchors.take(10)} drops=${drops.size} ${drops.take(10)}"
			)
		}
		out.appendLine("badFrames=$badFrames")
		thdFile.close()
		File(dir, "$name-results.txt").writeText(out.toString())
	}
})

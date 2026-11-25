package org.jellyfin.playback.media3.exoplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Where [TrueHdMatAudioSink] and the audio track it plays on meet: while [packing], a 16 bit 8 channel track at the
 * burst rate is opened as IEC 61937 instead of PCM.
 */
@OptIn(UnstableApi::class)
class TrueHdMatOutput(
	private val directSupport: (sampleRate: Int) -> Boolean = ::isIec61937Supported,
) {
	@Volatile
	var packing = false

	private val supported = HashMap<Int, Boolean>()

	fun supports(sampleRate: Int) = supported.getOrPut(sampleRate) { directSupport(sampleRate) }

	fun isBurstTrack(encoding: Int, sampleRate: Int, channelMask: Int) = packing &&
		encoding == C.ENCODING_PCM_16BIT &&
		channelMask == CHANNEL_MASK &&
		(sampleRate == 192_000 || sampleRate == 176_400)

	fun provider(context: Context): AudioOutputProvider = AudioTrackAudioOutputProvider.Builder(context)
		.setAudioTrackBuilderModifier { builder, config ->
			if (isBurstTrack(config.encoding, config.sampleRate, config.channelMask)) {
				builder.setAudioFormat(iecFormat(config.sampleRate))
				Log.i(TAG, "TrueHD goes out as IEC 61937 MAT at ${config.sampleRate} Hz, buffer ${config.bufferSize}")
			}
		}
		.build()

	companion object {
		private const val TAG = "TrueHdMatAudioSink"
		const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND

		private fun iecFormat(sampleRate: Int) = AudioFormat.Builder()
			.setEncoding(AudioFormat.ENCODING_IEC61937)
			.setSampleRate(sampleRate)
			.setChannelMask(CHANNEL_MASK)
			.build()

		/** Test build (task 195): only Amlogic boxes, whose own MAT encoder fails at TrueHD splices. */
		fun isAmlogic(hardware: String = Build.HARDWARE, sdk: Int = Build.VERSION.SDK_INT) =
			sdk >= Build.VERSION_CODES.Q && hardware.equals("amlogic", ignoreCase = true)

		fun isIec61937Supported(sampleRate: Int): Boolean {
			if (!isAmlogic()) return false
			val attributes = AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_MEDIA)
				.setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
				.build()
			@Suppress("DEPRECATION")
			val supported = runCatching { AudioTrack.isDirectPlaybackSupported(iecFormat(sampleRate), attributes) }.getOrDefault(false)
			Log.i(TAG, "IEC 61937 at $sampleRate Hz 7.1 direct: $supported")
			return supported
		}
	}
}

/**
 * Sends TrueHD as MAT frames packed here ([TrueHdMatPacker]) instead of handing the raw stream to the device.
 *
 * Amlogic's HAL packs raw TrueHD itself, and its MAT encoder drops out wherever the stream's input timing jumps
 * (joins between the clips of a disc). The bursts go to the real sink as 16 bit, 8 channel, 192 kHz "PCM" so its frame
 * counting keeps the position, on a track that [TrueHdMatOutput] opens as IEC 61937.
 */
@OptIn(UnstableApi::class)
class TrueHdMatAudioSink(
	sink: AudioSink,
	private val output: TrueHdMatOutput,
	private val packer: TrueHdMatPacker = TrueHdMatPacker(),
) : ForwardingAudioSink(sink) {
	private var packing = false
	private var burstUs = 0L
	private var unitUs = 0.0
	private var pendingBuffer: ByteBuffer? = null
	private val bursts = ArrayDeque<ByteBuffer>()
	private var burst: ByteBuffer? = null
	private var nextBurstUs = C.TIME_UNSET
	private var resync = false
	private var dropped = false
	private var requestedParameters: PlaybackParameters? = null
	private var requestedSkipSilence = false

	override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
		val format = audioSinkConfig.format
		val burstRate = if (format.sampleRate > 0 && format.sampleRate % 44_100 == 0) 176_400 else 192_000
		val pack = format.sampleMimeType == MimeTypes.AUDIO_TRUEHD && output.supports(burstRate)
		clear()
		val was = packing
		packing = pack
		output.packing = pack
		if (!pack) {
			if (was) {
				requestedParameters?.let { super.setPlaybackParameters(it) }
				super.setSkipSilenceEnabled(requestedSkipSilence)
			}
			super.configure(audioSinkConfig)
			return
		}

		burstUs = BURST_FRAMES * 1_000_000L / burstRate
		unitUs = 1_000_000.0 * UNIT_SAMPLES * 4 / burstRate
		// Speed change or silence skipping would rewrite the bursts.
		if (!was) {
			requestedParameters = super.getPlaybackParameters()
			requestedSkipSilence = super.getSkipSilenceEnabled()
		}
		super.setPlaybackParameters(PlaybackParameters.DEFAULT)
		super.setSkipSilenceEnabled(false)
		super.configure(
			AudioSink.AudioSinkConfig.Builder(
				Format.Builder()
					.setSampleMimeType(MimeTypes.AUDIO_RAW)
					.setPcmEncoding(C.ENCODING_PCM_16BIT)
					.setSampleRate(burstRate)
					.setChannelCount(8)
					.build()
			)
				.setPreferredBufferSizeOverride(TrueHdMatPacker.MAT_SIZE * BUFFER_BURSTS)
				.setTimeline(audioSinkConfig.timeline)
				.setMediaPeriodId(audioSinkConfig.mediaPeriodId)
				.build()
		)
	}

	override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
		if (!packing) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

		// Called again with the same buffer until it was taken; the bursts made from it wait in the queue meanwhile.
		if (pendingBuffer !== buffer) {
			pendingBuffer = buffer
			packBuffer(buffer, presentationTimeUs)
		}
		if (!drain()) return false
		buffer.position(buffer.limit())
		pendingBuffer = null
		return true
	}

	private fun packBuffer(buffer: ByteBuffer, presentationTimeUs: Long) {
		val data = ByteArray(buffer.remaining())
		buffer.duplicate().get(data)
		var pos = 0
		var index = 0
		while (pos + 4 <= data.size) {
			val size = (((data[pos].toInt() and 0xF) shl 8) or (data[pos + 1].toInt() and 0xFF)) * 2
			if (size < 8 || pos + size > data.size) break
			if (!packer.pack(data, pos, size)) {
				dropped = true
			} else {
				val unitTimeUs = presentationTimeUs + (index * unitUs).toLong()
				if (nextBurstUs == C.TIME_UNSET) {
					// The sink ramps the volume of a track's first 20 ms: let it ramp silence, not a burst.
					nextBurstUs = unitTimeUs - burstUs
					bursts.add(ByteBuffer.allocateDirect(TrueHdMatPacker.MAT_SIZE).order(ByteOrder.LITTLE_ENDIAN))
					resync = unitTimeUs < burstUs
				} else {
					// A stretch that was dropped here or missing in the stream leaves a hole the sink must know about.
					// Units sit up to about 80 ms around their time in the bursts, so small offsets are normal.
					val packedToUs = nextBurstUs + bursts.size * burstUs
					if (dropped || abs(packedToUs - unitTimeUs) > MAX_DRIFT_US) {
						nextBurstUs += unitTimeUs - packedToUs
						super.handleDiscontinuity()
					}
				}
				dropped = false
				while (true) {
					val next = packer.pollBurst() ?: break
					bursts.add(ByteBuffer.allocateDirect(next.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(next); flip() })
				}
			}
			pos += size
			index++
		}
	}

	private fun drain(): Boolean {
		while (true) {
			val next = burst ?: bursts.removeFirstOrNull()?.also { burst = it } ?: return true
			if (!super.handleBuffer(next, nextBurstUs, 1)) return false
			burst = null
			nextBurstUs += burstUs
			if (resync) {
				resync = false
				super.handleDiscontinuity()
			}
		}
	}

	override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
		requestedParameters = playbackParameters
		super.setPlaybackParameters(if (packing) PlaybackParameters.DEFAULT else playbackParameters)
	}

	override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
		requestedSkipSilence = skipSilenceEnabled
		super.setSkipSilenceEnabled(skipSilenceEnabled && !packing)
	}

	override fun hasPendingData(): Boolean = burst != null || bursts.isNotEmpty() || super.hasPendingData()

	override fun flush() {
		clear()
		super.flush()
	}

	override fun reset() {
		clear()
		super.reset()
	}

	private fun clear() {
		packer.reset()
		bursts.clear()
		burst = null
		pendingBuffer = null
		nextBurstUs = C.TIME_UNSET
		resync = false
		dropped = false
	}

	private companion object {
		const val BURST_FRAMES = TrueHdMatPacker.MAT_SIZE / 16
		const val UNIT_SAMPLES = 40
		const val BUFFER_BURSTS = 12
		const val MAX_DRIFT_US = 150_000L
	}
}

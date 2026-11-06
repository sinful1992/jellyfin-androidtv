package org.jellyfin.playback.media3.exoplayer

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.ThrowingRunnable
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

/**
 * Wraps codec adapters so a Dolby Vision profile 7 decoder gets its enhancement layer only from the
 * layer's own start point after the codec is created (see [DolbyVisionElJoin]). A flush (a seek
 * while playing) does not hold it back again: the decoder already has the EL's setup by then, and
 * seeking while playing never crashed. Every other codec is handed back unwrapped. Encrypted input
 * (queueSecureInputBuffer) is passed as it is.
 */
@OptIn(UnstableApi::class)
class DolbyVisionElJoinAdapterFactory(
	private val inner: MediaCodecAdapter.Factory,
) : MediaCodecAdapter.Factory {
	override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
		val adapter = inner.createAdapter(configuration)
		return if (DolbyVisionElJoin.appliesTo(configuration.format.codecs)) DolbyVisionElJoinAdapter(adapter)
		else adapter
	}
}

@OptIn(UnstableApi::class)
internal class DolbyVisionElJoinAdapter(
	private val inner: MediaCodecAdapter,
) : MediaCodecAdapter by inner {
	private val join = DolbyVisionElJoin()

	override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
		var queuedSize = size
		if (join.waiting && size > 0) {
			val data = inner.getInputBuffer(index)
			if (data != null && !data.isReadOnly) {
				data.limit(offset + size)
				data.position(offset)
				join.filter(data)
				queuedSize = data.limit() - offset
			}
		}
		inner.queueInputBuffer(index, offset, queuedSize, presentationTimeUs, flags)
	}

	override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener): Boolean =
		inner.registerOnBufferAvailableListener(listener)

	override fun <E : Exception> useBuffer(runnable: ThrowingRunnable<E>) =
		inner.useBuffer(runnable)

	override fun setOnFrameRenderedListener(listener: MediaCodecAdapter.OnFrameRenderedListener, handler: Handler) =
		inner.setOnFrameRenderedListener(listener, handler)
}

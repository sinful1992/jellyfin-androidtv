package org.jellyfin.playback.media3.exoplayer

/**
 * How DTS-HD is sent to an audio system that takes DTS as a bitstream.
 */
enum class DtsHdOutput {
	/**
	 * In full, until the audio clock is seen falling behind while it plays. Then as its DTS core,
	 * from then on for this device and audio output.
	 */
	AUTO,

	/**
	 * Always in full: lossless 7.1 and DTS:X.
	 */
	FULL,

	/**
	 * Always as its DTS core: 5.1 at up to 1.5 Mbps.
	 */
	CORE,
}

package org.jellyfin.androidtv.ui.itemdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.R
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType

private val names = mapOf(
	R.string.dolby_vision to "Dolby Vision",
	R.string.dolby_vision_with_el to "Dolby Vision with EL",
	R.string.hdr10 to "HDR10",
	R.string.hdr10_plus to "HDR10+",
	R.string.hlg to "HLG",
	R.string.truehd to "TrueHD",
	R.string.truehd_atmos to "TrueHD Atmos",
	R.string.eac3_atmos to "DD+ Atmos",
	R.string.dolby_atmos to "Dolby Atmos",
	R.string.dts_x to "DTS:X",
	R.string.dts_hd to "DTS-HD",
)
private val label: (Int) -> String = { names.getValue(it) }

private fun video(width: Int = 1920, height: Int = 1080, range: VideoRangeType? = VideoRangeType.SDR) = mockk<MediaStream> {
	every { type } returns MediaStreamType.VIDEO
	every { this@mockk.width } returns width
	every { this@mockk.height } returns height
	every { videoRangeType } returns range
	every { index } returns 0
}

private fun audio(codec: String?, profile: String? = null, index: Int = 1) = mockk<MediaStream> {
	every { type } returns MediaStreamType.AUDIO
	every { this@mockk.codec } returns codec
	every { this@mockk.profile } returns profile
	every { this@mockk.index } returns index
}

private fun source(vararg streams: MediaStream, defaultAudio: Int? = null) = mockk<MediaSourceInfo> {
	every { mediaStreams } returns streams.toList()
	every { defaultAudioStreamIndex } returns defaultAudio
}

class DetailsFormatsTests : FunSpec({
	test("4K Dolby Vision with TrueHD Atmos shows all three, in order") {
		detailsPremiumFormats(
			source(video(3840, 2160, VideoRangeType.DOVI_WITH_HDR10), audio("truehd", "Dolby TrueHD + Dolby Atmos")),
			label,
		) shouldBe listOf("4K", "Dolby Vision", "TrueHD Atmos")
	}

	test("a 1080p SDR file with AC3 shows nothing") {
		detailsPremiumFormats(source(video(), audio("ac3")), label).shouldBeEmpty()
	}

	test("plain TrueHD without Atmos") {
		detailsPremiumFormats(source(video(), audio("truehd")), label) shouldBe listOf("TrueHD")
	}

	test("EAC3 Atmos and DTS:X and DTS-HD") {
		detailsPremiumFormats(source(video(), audio("eac3", "Dolby Digital Plus + Dolby Atmos")), label) shouldBe listOf("DD+ Atmos")
		detailsPremiumFormats(source(video(), audio("dts", "DTS:X")), label) shouldBe listOf("DTS:X")
		detailsPremiumFormats(source(video(), audio("dts", "DTS-HD MA")), label) shouldBe listOf("DTS-HD")
	}

	test("plain DTS core and EAC3 without Atmos are ordinary") {
		detailsPremiumFormats(source(video(), audio("dts", "DTS")), label).shouldBeEmpty()
		detailsPremiumFormats(source(video(), audio("eac3")), label).shouldBeEmpty()
	}

	test("Dolby Vision with an enhancement layer is named as such, one tag only") {
		detailsPremiumFormats(source(video(range = VideoRangeType.DOVI_WITH_ELHDR10_PLUS)), label) shouldBe listOf("Dolby Vision with EL")
	}

	test("HDR10, HDR10+ and HLG") {
		detailsPremiumFormats(source(video(range = VideoRangeType.HDR10)), label) shouldBe listOf("HDR10")
		detailsPremiumFormats(source(video(range = VideoRangeType.HDR10_PLUS)), label) shouldBe listOf("HDR10+")
		detailsPremiumFormats(source(video(range = VideoRangeType.HLG)), label) shouldBe listOf("HLG")
	}

	test("the default audio track is the one described, not the first") {
		detailsPremiumFormats(
			source(video(), audio("ac3", index = 1), audio("truehd", "Dolby TrueHD + Dolby Atmos", index = 2), defaultAudio = 2),
			label,
		) shouldBe listOf("TrueHD Atmos")
	}

	test("a default index that is not an audio track falls back to the first audio") {
		detailsPremiumFormats(source(video(), audio("truehd", index = 1), defaultAudio = 0), label) shouldBe listOf("TrueHD")
	}

	test("no version, no streams, or a vertical 4K phone clip") {
		detailsPremiumFormats(null, label).shouldBeEmpty()
		detailsPremiumFormats(source(), label).shouldBeEmpty()
		detailsPremiumFormats(source(video(2160, 3840, null)), label) shouldBe listOf("4K")
	}

	test("8K") {
		detailsPremiumFormats(source(video(7680, 4320, null)), label) shouldBe listOf("8K")
	}
})

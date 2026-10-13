package org.jellyfin.playback.media3.exoplayer

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jellyfin.playback.media3.exoplayer.PassthroughFirstTrackSelector.Companion.decodeOnlyTracks

private fun audio(mimeType: String, channels: Int, language: String? = "en") = Format.Builder()
	.setSampleMimeType(mimeType)
	.setChannelCount(channels)
	.setSampleRate(48000)
	.setLanguage(language)
	.build()

// The Bravia: everything Dolby and DTS passes through except TrueHD.
private val bravia: (Format) -> Boolean = { it.sampleMimeType != MimeTypes.AUDIO_TRUEHD && it.sampleMimeType != MimeTypes.AUDIO_AAC }

class PassthroughFirstTrackSelectorTests : FunSpec({
	test("TrueHD next to an AC3 5.1 track in the same language is demoted") {
		// Deadpool / Cars: TrueHD 7.1 in group 0, AC3 5.1 in group 1.
		val groups = listOf(listOf(audio(MimeTypes.AUDIO_TRUEHD, 8)), listOf(audio(MimeTypes.AUDIO_AC3, 6)))
		decodeOnlyTracks(groups, bravia) shouldBe setOf(0 to 0)
	}

	test("TrueHD stays when the only passthrough track is in another language") {
		// Warfare: TrueHD eng, E-AC3 fra.
		val groups = listOf(listOf(audio(MimeTypes.AUDIO_TRUEHD, 8, "en")), listOf(audio(MimeTypes.AUDIO_E_AC3, 6, "fr")))
		decodeOnlyTracks(groups, bravia).shouldBeEmpty()
	}

	test("a stereo passthrough track does not demote a surround one") {
		val groups = listOf(listOf(audio(MimeTypes.AUDIO_TRUEHD, 8)), listOf(audio(MimeTypes.AUDIO_AC3, 2)))
		decodeOnlyTracks(groups, bravia).shouldBeEmpty()
	}

	test("nothing is demoted when every track passes through") {
		// Hulk: DTS 7.1 and E-AC3 5.1, both default.
		val groups = listOf(listOf(audio(MimeTypes.AUDIO_DTS, 8)), listOf(audio(MimeTypes.AUDIO_E_AC3, 6)))
		decodeOnlyTracks(groups, bravia).shouldBeEmpty()
	}

	test("nothing is demoted where TrueHD passes through") {
		val groups = listOf(listOf(audio(MimeTypes.AUDIO_TRUEHD, 8)), listOf(audio(MimeTypes.AUDIO_AC3, 6)))
		decodeOnlyTracks(groups) { true }.shouldBeEmpty()
	}

	test("TrueHD only is left alone") {
		decodeOnlyTracks(listOf(listOf(audio(MimeTypes.AUDIO_TRUEHD, 8))), bravia).shouldBeEmpty()
	}

	test("every decode-only track in the language goes, others stay") {
		// TrueHD en, AAC en stereo, DTS-HD en, AC3 ro stereo.
		val groups = listOf(
			listOf(audio(MimeTypes.AUDIO_TRUEHD, 8)),
			listOf(audio(MimeTypes.AUDIO_AAC, 2)),
			listOf(audio(MimeTypes.AUDIO_DTS_HD, 8)),
			listOf(audio(MimeTypes.AUDIO_AC3, 2, "ro")),
		)
		decodeOnlyTracks(groups, bravia) shouldBe setOf(0 to 0, 1 to 0)
	}

	test("no language and undetermined match each other") {
		val groups = listOf(listOf(audio(MimeTypes.AUDIO_TRUEHD, 8, null)), listOf(audio(MimeTypes.AUDIO_AC3, 6, "und")))
		decodeOnlyTracks(groups, bravia) shouldBe setOf(0 to 0)
	}

	test("Frozen: TrueHD goes, DTS-HD and AC3 stay for the default flag to decide") {
		val groups = listOf(
			listOf(audio(MimeTypes.AUDIO_TRUEHD, 8)),
			listOf(audio(MimeTypes.AUDIO_DTS_HD, 8)),
			listOf(audio(MimeTypes.AUDIO_AC3, 6)),
		)
		decodeOnlyTracks(groups, bravia) shouldBe setOf(0 to 0)
	}
})

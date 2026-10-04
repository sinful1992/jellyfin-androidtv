package org.jellyfin.playback.media3.exoplayer

import android.text.TextUtils
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic
import org.jellyfin.playback.core.mediastream.MediaStreamAudioTrack
import org.jellyfin.playback.core.mediastream.MediaStreamSubtitleTrack

private fun format(language: String?, mimeType: String, label: String? = null) = Format.Builder()
	.setLanguage(language)
	.setSampleMimeType(mimeType)
	.setLabel(label)
	.build()

private fun subtitle(index: Int, name: String?) =
	MediaStreamSubtitleTrack(index = index, codec = "ass", name = name, language = "eng")

private fun audio(index: Int, name: String?) =
	MediaStreamAudioTrack(index = index, codec = "eac3", name = name, language = "eng", bitrate = 0, channels = 2, sampleRate = 48000)

private val sameCodec: TrackDiscriminator<MediaStreamSubtitleTrack> = { _, _ -> true }

class TrackMatchingTests : FunSpec({
	// Format normalises its language through media3's Util, which calls into the Android framework.
	mockkStatic(TextUtils::class)
	every { TextUtils.isEmpty(any()) } answers { (firstArg() as CharSequence?).isNullOrEmpty() }

	// Married With Children S04: sub 7 eng ass untitled, sub 8 eng ass "SDH", no flags on either.
	val marriedWithChildren = listOf(
		format("ar", MimeTypes.TEXT_SSA),
		format("en", MimeTypes.TEXT_SSA),
		format("en", MimeTypes.TEXT_SSA, label = "SDH"),
	)

	test("an untitled track is told apart from an SDH one of the same language and codec by its title") {
		matchTrackFormat(subtitle(7, null), marriedWithChildren, listOf(sameCodec, sameTitle())) shouldBe 1
	}

	test("the SDH track is told apart from the untitled one by its title") {
		matchTrackFormat(subtitle(8, "SDH"), marriedWithChildren, listOf(sameCodec, sameTitle())) shouldBe 2
		matchTrackFormat(subtitle(8, " sdh "), marriedWithChildren, listOf(sameCodec, sameTitle())) shouldBe 2
	}

	test("without the title the two stay ambiguous") {
		matchTrackFormat(subtitle(7, null), marriedWithChildren, listOf(sameCodec)) shouldBe null
	}

	test("identical untitled tracks stay ambiguous rather than picking one") {
		// Dragon Ball Z: four eng eac3 tracks with no title.
		val formats = List(4) { format("en", MimeTypes.AUDIO_E_AC3) }

		matchTrackFormat(audio(1, null), formats, listOf(sameTitle())) shouldBe null
	}

	test("a commentary track is told apart from the main one") {
		// Banshee 3x7: main and commentary, both eng ac3.
		val formats = listOf(
			format("en", MimeTypes.AUDIO_AC3),
			format("en", MimeTypes.AUDIO_AC3, label = "Commentary"),
		)

		matchTrackFormat(audio(1, null), formats, listOf(sameTitle())) shouldBe 0
		matchTrackFormat(audio(2, "Commentary"), formats, listOf(sameTitle())) shouldBe 1
	}

	test("a title the container does not carry rules nothing out") {
		val formats = listOf(
			format("en", MimeTypes.AUDIO_AC3, label = "Main"),
			format("en", MimeTypes.AUDIO_AC3, label = "Commentary"),
		)

		matchTrackFormat(audio(1, "English"), formats, listOf(sameTitle())) shouldBe null
	}

	test("a language alone settles a track that is the only one in it") {
		matchTrackFormat(subtitle(3, "anything"), marriedWithChildren.take(2), listOf(sameCodec, sameTitle())) shouldBe 1
	}
})

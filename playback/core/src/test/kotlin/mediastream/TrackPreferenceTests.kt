package org.jellyfin.playback.core.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun audio(index: Int, language: String?, name: String? = null) = MediaStreamAudioTrack(
	index = index,
	codec = "ac3",
	title = listOfNotNull(name, language, "AC3").joinToString(" - "),
	name = name,
	language = language,
	bitrate = 0,
	channels = 2,
	sampleRate = 48000,
)

private fun sub(index: Int, language: String?, name: String? = null, forced: Boolean = false, default: Boolean = false) =
	MediaStreamSubtitleTrack(
		index = index,
		codec = "subrip",
		// The server's display title, which moves with the flags: what [name] exists to avoid.
		title = listOfNotNull(name, language, "Default".takeIf { default }, "Forced".takeIf { forced }, "SUBRIP")
			.joinToString(" - "),
		name = name,
		language = language,
		isDefault = default,
		isForced = forced,
	)

// Tom and Jerry Kids Show S03E49-51 "Stunt Cat" and S03E52-54 "Scrapheap", as the server lists them.
private val stuntCatAudio = listOf(audio(1, "zho", "Taiwan"), audio(2, "eng"), audio(3, "tha"))
private val stuntCatSubs = listOf(sub(4, "eng", "SDH"), sub(5, "zho", "Traditional, Taiwan"))
private val scrapheapAudio = listOf(audio(1, "eng"), audio(2, "spa", "Latin American"), audio(3, "por", "Brazilian"))
private val scrapheapSubs = listOf(
	sub(4, "spa", forced = true),
	sub(5, "por", forced = true),
	sub(6, "eng", "SDH"),
	sub(7, "spa"),
	sub(8, "por"),
)

class TrackPreferenceTests : FunSpec({
	test("eng SDH picked in Stunt Cat lands on index 6 in Scrapheap") {
		TrackPreference.of(stuntCatSubs[0]).pick(scrapheapSubs) shouldBe 6
	}

	test("subtitles off stay off") {
		TrackPreference.OFF.pick(scrapheapSubs) shouldBe MediaStreamSubtitleTrack.INDEX_NONE
	}

	test("a forced track carries to the forced track, a full one to the full one") {
		TrackPreference.of(scrapheapSubs[0]).pick(scrapheapSubs) shouldBe 4
		TrackPreference.of(scrapheapSubs[3]).pick(scrapheapSubs) shouldBe 7
	}

	test("with only the other kind in that language, the language still wins, non-forced first") {
		val onlyForced = listOf(sub(3, "eng"), sub(4, "spa", forced = true))
		TrackPreference.of(scrapheapSubs[3]).pick(onlyForced) shouldBe 4

		val both = listOf(sub(3, "spa", forced = true), sub(4, "spa", "Castilian"))
		TrackPreference(language = "spa", forced = false, title = "Latin").pick(both) shouldBe 4
	}

	test("a language the next file lacks leaves the server default") {
		TrackPreference.of(stuntCatSubs[1]).pick(scrapheapSubs) shouldBe null
		TrackPreference.of(stuntCatAudio[0]).pick(scrapheapAudio) shouldBe null
	}

	test("audio carries by language") {
		TrackPreference.of(stuntCatAudio[1]).pick(scrapheapAudio) shouldBe 1
		TrackPreference.of(scrapheapAudio[0]).pick(stuntCatAudio) shouldBe 2
	}

	test("audio sharing a language is told apart by title") {
		val next = listOf(audio(1, "eng"), audio(2, "eng", "Commentary"), audio(3, "eng", "Director's Commentary"))
		TrackPreference.of(audio(5, "eng", "Commentary")).pick(next) shouldBe 2
		TrackPreference.of(audio(5, "eng", "Commentary by the director")).pick(next) shouldBe 3
		TrackPreference.of(audio(5, "eng")).pick(next) shouldBe 1
	}

	test("the default flag moving between files does not break the title match") {
		val next = listOf(sub(2, "eng"), sub(3, "eng", "SDH", default = true))
		TrackPreference.of(sub(9, "eng", "SDH")).pick(next) shouldBe 3
	}

	test("SDH and plain stay apart when the titles say nothing else") {
		val next = listOf(sub(2, "eng", "English SDH"), sub(3, "eng", "English"))
		TrackPreference.of(sub(9, "eng")).pick(next) shouldBe 3
		TrackPreference.of(sub(9, "eng", "SDH")).pick(next) shouldBe 2
		TrackPreference.of(sub(9, "eng", "Hearing impaired")).pick(next) shouldBe 2
	}

	test("languages compare without case") {
		TrackPreference(language = "ENG", title = "sdh").pick(scrapheapSubs) shouldBe 6
	}

	test("an untagged track carries by title only") {
		val next = listOf(sub(2, null, "Signs"), sub(3, null, "Full"))
		TrackPreference.of(sub(9, "und", "Full")).pick(next) shouldBe 3
		TrackPreference.of(sub(9, "", "full")).pick(next) shouldBe 3
		TrackPreference.of(sub(9, null)).pick(next) shouldBe null
		TrackPreference.of(sub(9, null, "Other")).pick(next) shouldBe null
	}

	test("an untagged track in the next file does not match a tagged choice") {
		TrackPreference.of(sub(9, "eng", "SDH")).pick(listOf(sub(2, "und", "SDH"))) shouldBe null
	}
})

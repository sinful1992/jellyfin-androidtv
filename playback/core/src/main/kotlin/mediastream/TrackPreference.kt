package org.jellyfin.playback.core.mediastream

/**
 * A track choice carried from one entry to the next. Indices differ between the files of one
 * series - a forced track can sit in front of the full one in one episode and not the next - so the
 * choice is kept as what the track is rather than where it sits.
 */
data class TrackPreference(
	/** Subtitles were turned off. Only meaningful for subtitles. */
	val off: Boolean = false,
	val language: String? = null,
	/** The container title of the track, see [MediaStreamTrack.name]. */
	val title: String? = null,
	val forced: Boolean = false,
	val codec: String? = null,
) {
	companion object {
		val OFF = TrackPreference(off = true)

		fun of(track: MediaStreamTrack) = TrackPreference(
			language = track.normalizedLanguage,
			title = track.name?.takeIf { it.isNotBlank() },
			forced = track.isForced,
			codec = track.codec,
		)
	}
}

/**
 * The index of the track in [tracks] that best fits this preference, or null when none does and the
 * server default should stand. Subtitles turned off resolve to [MediaStreamSubtitleTrack.INDEX_NONE].
 *
 * Only tracks of the type the preference was taken from should be passed in.
 */
fun TrackPreference.pick(tracks: Collection<MediaStreamTrack>): Int? {
	if (off) return MediaStreamSubtitleTrack.INDEX_NONE

	// Without a language the title is all there is to go on, and a loose title match on an
	// untagged file is as likely to be wrong as right.
	if (language == null) {
		if (title == null) return null
		return tracks.firstOrNull { it.name.equals(title, ignoreCase = true) }?.index
	}

	val sameLanguage = tracks.filter { it.normalizedLanguage.equals(language, ignoreCase = true) }
	val sameForced = sameLanguage.filter { it.isForced == forced }

	sameForced.firstOrNull { it.name.orEmpty().equals(title.orEmpty(), ignoreCase = true) }
		?.let { return it.index }

	if (sameForced.isNotEmpty()) {
		val wanted = title.titleWords()
		val wantsSdh = title.isSdh()
		// maxWithOrNull keeps the first of equal candidates, so the file's own order breaks ties.
		return sameForced.maxWithOrNull(
			compareBy<MediaStreamTrack>(
				{ track -> track.name.titleWords().count { it in wanted } },
				{ track -> track.name.isSdh() == wantsSdh },
				{ track -> track.codec.equals(codec, ignoreCase = true) },
			)
		)?.index
	}

	return sameLanguage.sortedBy { it.isForced }.firstOrNull()?.index
}

private val MediaStreamTrack.isForced
	get() = this is MediaStreamSubtitleTrack && isForced

/** Old rips often tag a track with a blank or undetermined language, which says nothing. */
private val MediaStreamTrack.normalizedLanguage
	get() = language?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }

private fun String?.titleWords() = orEmpty()
	.lowercase()
	.split(Regex("[^\\p{L}\\p{N}]+"))
	.filter { it.isNotEmpty() }
	.toSet()

private fun String?.isSdh() = titleWords().let { words -> "sdh" in words || "cc" in words || "hearing" in words }

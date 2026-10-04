package org.jellyfin.playback.core.mediastream

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Where the track choices carried per [trackCarryGroup] are kept between app runs.
 */
interface TrackCarryStore {
	enum class Kind { AUDIO, SUBTITLE }

	fun read(group: String, kind: Kind): TrackPreference?
	fun write(group: String, kind: Kind, preference: TrackPreference?)
}

/**
 * A [TrackCarryStore] in the app's shared preferences, one entry per group and kind.
 */
class SharedPreferencesTrackCarryStore(context: Context) : TrackCarryStore {
	private val preferences = context.getSharedPreferences("track_carry", Context.MODE_PRIVATE)

	private fun key(group: String, kind: TrackCarryStore.Kind) = "$group/${kind.name}"

	override fun read(group: String, kind: TrackCarryStore.Kind): TrackPreference? {
		val json = preferences.getString(key(group, kind), null) ?: return null
		return runCatching {
			JSONObject(json).run {
				TrackPreference(
					off = optBoolean("off"),
					language = optString("language").takeIf { has("language") },
					title = optString("title").takeIf { has("title") },
					forced = optBoolean("forced"),
					sdh = optBoolean("sdh"),
					codec = optString("codec").takeIf { has("codec") },
				)
			}
		}.getOrNull()
	}

	override fun write(group: String, kind: TrackCarryStore.Kind, preference: TrackPreference?) {
		preferences.edit {
			if (preference == null) {
				remove(key(group, kind))
			} else {
				putString(key(group, kind), JSONObject().apply {
					put("off", preference.off)
					preference.language?.let { put("language", it) }
					preference.title?.let { put("title", it) }
					put("forced", preference.forced)
					put("sdh", preference.sdh)
					preference.codec?.let { put("codec", it) }
				}.toString())
			}
		}
	}
}

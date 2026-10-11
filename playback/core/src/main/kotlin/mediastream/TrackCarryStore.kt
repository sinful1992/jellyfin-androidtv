package org.jellyfin.playback.core.mediastream

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * Where the track choices carried per [trackCarryGroup] are kept between app runs, per user: one
 * person picking the dub on a shared TV must not switch it for everyone else.
 */
interface TrackCarryStore {
	enum class Kind { AUDIO, SUBTITLE }

	fun read(user: String, group: String, kind: Kind): TrackPreference?
	fun write(user: String, group: String, kind: Kind, preference: TrackPreference?)

	/** The choices of [user] in [group] were just applied, so they count as used from now. */
	fun touch(user: String, group: String)

	/** Forget every choice [user] made. */
	fun clear(user: String)
}

/**
 * A [TrackCarryStore] in the app's shared preferences, one entry per user, group and kind. An
 * entry not used for [maxAge] reads as no choice and is dropped, so a pick made once for one
 * evening does not follow the series for good.
 *
 * Entries stored before choices were per user have no user in their key and are never read: there
 * is no telling whose they were.
 */
class SharedPreferencesTrackCarryStore(
	context: Context,
	private val maxAge: Duration = 60.days,
	private val now: () -> Long = System::currentTimeMillis,
) : TrackCarryStore {
	private val preferences = context.getSharedPreferences("track_carry", Context.MODE_PRIVATE)

	init {
		// Entries from before choices were per user ("group/KIND") are never read again.
		val unowned = preferences.all.keys.filter { it.count { char -> char == '/' } < 2 }
		if (unowned.isNotEmpty()) preferences.edit { unowned.forEach(::remove) }
	}

	private fun key(user: String, group: String, kind: TrackCarryStore.Kind) = "$user/$group/${kind.name}"

	override fun read(user: String, group: String, kind: TrackCarryStore.Kind): TrackPreference? {
		val key = key(user, group, kind)
		val json = preferences.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null

		if (now() - json.optLong("savedAt") > maxAge.inWholeMilliseconds) {
			preferences.edit { remove(key) }
			return null
		}

		return json.run {
			TrackPreference(
				off = optBoolean("off"),
				language = optString("language").takeIf { has("language") },
				title = optString("title").takeIf { has("title") },
				forced = optBoolean("forced"),
				sdh = optBoolean("sdh"),
				codec = optString("codec").takeIf { has("codec") },
			)
		}
	}

	override fun write(user: String, group: String, kind: TrackCarryStore.Kind, preference: TrackPreference?) {
		preferences.edit {
			if (preference == null) {
				remove(key(user, group, kind))
			} else {
				putString(key(user, group, kind), JSONObject().apply {
					put("off", preference.off)
					preference.language?.let { put("language", it) }
					preference.title?.let { put("title", it) }
					put("forced", preference.forced)
					put("sdh", preference.sdh)
					preference.codec?.let { put("codec", it) }
					put("savedAt", now())
				}.toString())
			}
		}
	}

	override fun touch(user: String, group: String) {
		preferences.edit {
			for (kind in TrackCarryStore.Kind.entries) {
				val key = key(user, group, kind)
				val json = preferences.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
				putString(key, json.put("savedAt", now()).toString())
			}
		}
	}

	override fun clear(user: String) {
		preferences.edit {
			preferences.all.keys.filter { it.startsWith("$user/") }.forEach(::remove)
		}
	}
}

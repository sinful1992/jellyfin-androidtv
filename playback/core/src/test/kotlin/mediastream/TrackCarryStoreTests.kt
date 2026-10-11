package org.jellyfin.playback.core.mediastream

import android.content.Context
import android.content.SharedPreferences
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Duration.Companion.days

/** Just enough of [SharedPreferences] for the store: strings, removal and listing the keys. */
private class MemorySharedPreferences : SharedPreferences {
	val values = mutableMapOf<String, Any?>()

	override fun getAll(): Map<String, *> = values.toMap()
	override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
	override fun getStringSet(key: String, defValues: Set<String>?) = throw NotImplementedError()
	override fun getInt(key: String, defValue: Int) = throw NotImplementedError()
	override fun getLong(key: String, defValue: Long) = throw NotImplementedError()
	override fun getFloat(key: String, defValue: Float) = throw NotImplementedError()
	override fun getBoolean(key: String, defValue: Boolean) = throw NotImplementedError()
	override fun contains(key: String) = key in values
	override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
	override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

	override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
		private val changes = mutableListOf<(MutableMap<String, Any?>) -> Unit>()

		override fun putString(key: String, value: String?) = apply { changes += { it[key] = value } }
		override fun remove(key: String) = apply { changes += { it.remove(key) } }
		override fun putStringSet(key: String, values: Set<String>?) = throw NotImplementedError()
		override fun putInt(key: String, value: Int) = throw NotImplementedError()
		override fun putLong(key: String, value: Long) = throw NotImplementedError()
		override fun putFloat(key: String, value: Float) = throw NotImplementedError()
		override fun putBoolean(key: String, value: Boolean) = throw NotImplementedError()
		override fun clear() = apply { changes += { it.clear() } }
		override fun commit(): Boolean {
			apply()
			return true
		}

		override fun apply() = changes.forEach { it(values) }
	}
}

private class Store(val preferences: MemorySharedPreferences = MemorySharedPreferences()) {
	var now = 1_000_000_000_000L
	val store = SharedPreferencesTrackCarryStore(
		context = mockk<Context> { every { getSharedPreferences("track_carry", any()) } returns preferences },
		maxAge = 60.days,
		now = { now },
	)
}

private val dub = TrackPreference(language = "jpn", codec = "aac")

class TrackCarryStoreTests : FunSpec({
	test("a choice belongs to the user who made it") {
		val store = Store().store
		store.write("alice", "series-a", TrackCarryStore.Kind.AUDIO, dub)

		store.read("alice", "series-a", TrackCarryStore.Kind.AUDIO) shouldBe dub
		store.read("bob", "series-a", TrackCarryStore.Kind.AUDIO) shouldBe null
	}

	test("a choice made before choices were per user is not read for anyone") {
		val store = Store()
		store.preferences.values["series-a/AUDIO"] = """{"off":false,"language":"jpn"}"""

		store.store.read("alice", "series-a", TrackCarryStore.Kind.AUDIO) shouldBe null
	}

	test("a choice left unused for 60 days is gone") {
		val store = Store()
		store.store.write("alice", "series-a", TrackCarryStore.Kind.AUDIO, dub)

		store.now += 59.days.inWholeMilliseconds
		store.store.read("alice", "series-a", TrackCarryStore.Kind.AUDIO) shouldBe dub

		store.now += 2.days.inWholeMilliseconds
		store.store.read("alice", "series-a", TrackCarryStore.Kind.AUDIO) shouldBe null
		store.preferences.values shouldBe emptyMap()
	}

	test("a choice that keeps being used does not expire") {
		val store = Store()
		store.store.write("alice", "series-a", TrackCarryStore.Kind.AUDIO, dub)

		repeat(4) {
			store.now += 30.days.inWholeMilliseconds
			store.store.touch("alice", "series-a")
		}

		store.store.read("alice", "series-a", TrackCarryStore.Kind.AUDIO) shouldBe dub
	}

	test("touching a group with no choice stores nothing") {
		val store = Store()
		store.store.touch("alice", "series-a")

		store.preferences.values shouldBe emptyMap()
	}

	test("forgetting clears that user's choices only") {
		val store = Store().store
		store.write("alice", "series-a", TrackCarryStore.Kind.AUDIO, dub)
		store.write("alice", "series-b", TrackCarryStore.Kind.SUBTITLE, TrackPreference.OFF)
		store.write("bob", "series-a", TrackCarryStore.Kind.AUDIO, dub)

		store.clear("alice")

		store.read("alice", "series-a", TrackCarryStore.Kind.AUDIO) shouldBe null
		store.read("alice", "series-b", TrackCarryStore.Kind.SUBTITLE) shouldBe null
		store.read("bob", "series-a", TrackCarryStore.Kind.AUDIO) shouldBe dub
	}

	test("a user whose id starts like another's keeps their choices") {
		val store = Store().store
		store.write("al", "series-a", TrackCarryStore.Kind.AUDIO, dub)
		store.write("alice", "series-a", TrackCarryStore.Kind.AUDIO, dub)

		store.clear("al")

		store.read("alice", "series-a", TrackCarryStore.Kind.AUDIO) shouldBe dub
	}
})

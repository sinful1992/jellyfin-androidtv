package org.jellyfin.playback.core.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.jellyfin.playback.core.queue.QueueEntry
import kotlin.time.Duration

private fun entry(group: String?) = QueueEntry().apply { trackCarryGroup = group }

private class MemoryTrackCarryStore : TrackCarryStore {
	val values = mutableMapOf<Triple<String, String, TrackCarryStore.Kind>, TrackPreference>()
	val touched = mutableListOf<Pair<String, String>>()

	override fun read(user: String, group: String, kind: TrackCarryStore.Kind) = values[Triple(user, group, kind)]

	override fun write(user: String, group: String, kind: TrackCarryStore.Kind, preference: TrackPreference?) {
		val key = Triple(user, group, kind)
		if (preference == null) values.remove(key) else values[key] = preference
	}

	override fun touch(user: String, group: String) {
		touched += user to group
	}

	override fun clear(user: String) {
		values.keys.removeAll { it.first == user }
	}
}

class CarriedTracksTests : FunSpec({
	test("episodes of one series share a choice, whichever entry made it") {
		val service = MediaStreamService(emptyList(), Duration.ZERO)
		service.carriedTracks(entry("series-a")).subtitle = TrackPreference.OFF

		service.carriedTracks(entry("series-a")).subtitle shouldBe TrackPreference.OFF
	}

	test("another series or an entry in no series does not inherit it") {
		val service = MediaStreamService(emptyList(), Duration.ZERO)
		service.carriedTracks(entry("series-a")).subtitle = TrackPreference.OFF

		service.carriedTracks(entry("series-b")).subtitle shouldBe null
		service.carriedTracks(entry(null)).subtitle shouldBe null
	}

	test("entries in no series share the queue's choice") {
		val service = MediaStreamService(emptyList(), Duration.ZERO)

		service.carriedTracks(entry(null)) shouldBeSameInstanceAs service.carriedTracks(entry(null))
		service.carriedTracks(entry(null)) shouldNotBeSameInstanceAs service.carriedTracks(entry("series-a"))
	}

	test("a series choice outlives the service, as it does the app") {
		val store = MemoryTrackCarryStore()
		val sdh = TrackPreference(language = "eng", title = "SDH", sdh = true)
		MediaStreamService(emptyList(), Duration.ZERO, store) { "alice" }.carriedTracks(entry("series-a")).subtitle = sdh

		val restarted = MediaStreamService(emptyList(), Duration.ZERO, store) { "alice" }
		restarted.carriedTracks(entry("series-a")).subtitle shouldBe sdh
		restarted.carriedTracks(entry("series-a")).audio shouldBe null
	}

	test("a choice outside any series is never stored") {
		val store = MemoryTrackCarryStore()
		MediaStreamService(emptyList(), Duration.ZERO, store) { "alice" }.carriedTracks(entry(null)).subtitle = TrackPreference.OFF

		store.values shouldBe emptyMap()
	}

	test("another user on the same TV does not get the choice, even without an app restart") {
		val store = MemoryTrackCarryStore()
		var user = "alice"
		val service = MediaStreamService(emptyList(), Duration.ZERO, store) { user }
		val dub = TrackPreference(language = "jpn")
		service.carriedTracks(entry("series-a")).audio = dub

		user = "bob"
		service.carriedTracks(entry("series-a")).audio shouldBe null

		user = "alice"
		service.carriedTracks(entry("series-a")).audio shouldBe dub
	}

	test("with no user signed in a choice is kept in memory only") {
		val store = MemoryTrackCarryStore()
		val service = MediaStreamService(emptyList(), Duration.ZERO, store) { null }
		service.carriedTracks(entry("series-a")).subtitle = TrackPreference.OFF

		service.carriedTracks(entry("series-a")).subtitle shouldBe TrackPreference.OFF
		store.values shouldBe emptyMap()
	}

	test("forgetting drops the current user's choices, saved and in memory, and no one else's") {
		val store = MemoryTrackCarryStore()
		var user = "alice"
		val service = MediaStreamService(emptyList(), Duration.ZERO, store) { user }
		service.carriedTracks(entry("series-a")).subtitle = TrackPreference.OFF
		user = "bob"
		service.carriedTracks(entry("series-a")).subtitle = TrackPreference.OFF

		user = "alice"
		service.forgetCarriedTracks()

		service.carriedTracks(entry("series-a")).subtitle shouldBe null
		MediaStreamService(emptyList(), Duration.ZERO, store) { "alice" }.carriedTracks(entry("series-a")).subtitle shouldBe null
		MediaStreamService(emptyList(), Duration.ZERO, store) { "bob" }.carriedTracks(entry("series-a")).subtitle shouldBe TrackPreference.OFF
	}
})

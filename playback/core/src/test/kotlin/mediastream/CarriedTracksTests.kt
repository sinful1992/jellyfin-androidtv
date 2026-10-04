package org.jellyfin.playback.core.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.jellyfin.playback.core.queue.QueueEntry
import kotlin.time.Duration

private fun entry(group: String?) = QueueEntry().apply { trackCarryGroup = group }

private class MemoryTrackCarryStore : TrackCarryStore {
	val values = mutableMapOf<Pair<String, TrackCarryStore.Kind>, TrackPreference>()

	override fun read(group: String, kind: TrackCarryStore.Kind) = values[group to kind]

	override fun write(group: String, kind: TrackCarryStore.Kind, preference: TrackPreference?) {
		if (preference == null) values.remove(group to kind) else values[group to kind] = preference
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
		MediaStreamService(emptyList(), Duration.ZERO, store).carriedTracks(entry("series-a")).subtitle = sdh

		val restarted = MediaStreamService(emptyList(), Duration.ZERO, store)
		restarted.carriedTracks(entry("series-a")).subtitle shouldBe sdh
		restarted.carriedTracks(entry("series-a")).audio shouldBe null
	}

	test("a choice outside any series is never stored") {
		val store = MemoryTrackCarryStore()
		MediaStreamService(emptyList(), Duration.ZERO, store).carriedTracks(entry(null)).subtitle = TrackPreference.OFF

		store.values shouldBe emptyMap()
	}
})

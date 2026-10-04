package org.jellyfin.playback.core.mediastream

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.jellyfin.playback.core.queue.QueueEntry
import kotlin.time.Duration

private fun entry(group: String?) = QueueEntry().apply { trackCarryGroup = group }

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
})

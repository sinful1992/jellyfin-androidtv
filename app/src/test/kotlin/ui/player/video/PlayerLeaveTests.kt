package org.jellyfin.androidtv.ui.player.video

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jellyfin.androidtv.ui.browsing.backFades
import org.jellyfin.androidtv.ui.home.HomeFragment
import org.jellyfin.androidtv.ui.itemdetail.FullDetailsFragment
import java.io.File

// Task 176 (USER #12973): Back from a film left the screen black for ~7 s on the Shield. The removed
// player stayed on screen until its fade-out ended, and the display mode was reset only in onDestroy.
private val player = File("src/main/java/org/jellyfin/androidtv/ui/player/video/VideoPlayerFragment.kt").readText()
private val host = File("src/main/java/org/jellyfin/androidtv/ui/browsing/DestinationFragmentView.kt").readText()

private fun body(source: String, signature: String): String {
	val start = source.indexOf(signature)
	check(start >= 0) { "$signature not found" }
	var depth = 0
	var i = source.indexOf('{', start)
	val open = i
	while (true) {
		when (source[i]) {
			'{' -> depth++
			'}' -> if (--depth == 0) return source.substring(open, i + 1)
		}
		i++
	}
}

class PlayerLeaveTests : FunSpec({
	test("Back from the player does not fade, so its video surface leaves at once") {
		backFades(VideoPlayerFragment::class.java) shouldBe false
	}

	test("Back from other pages still fades") {
		backFades(FullDetailsFragment::class.java) shouldBe true
		backFades(HomeFragment::class.java) shouldBe true
	}

	test("goBack passes the leaving page to the fade decision") {
		body(host, "fun goBack()") shouldContain "animate = backFades(currentEntry.name)"
		body(host, "private fun activateHistoryEntry(") shouldContain "if (animate) setCustomAnimations("
	}

	test("the player hands the display mode back when it is removed, in onStop, not only in onDestroy") {
		body(player, "override fun onStop()") shouldContain "if (isRemoving) displayModeSwitcher?.reset()"
	}

	test("onDestroy still resets, for an activity that finishes with the player on top") {
		body(player, "override fun onDestroy()") shouldContain "displayModeSwitcher?.reset()"
	}
})

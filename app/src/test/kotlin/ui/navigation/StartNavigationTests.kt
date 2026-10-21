package org.jellyfin.androidtv.ui.navigation

import androidx.fragment.app.Fragment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk

// Bundle is a stub in JVM tests, so every destination carries a mocked one.
private class HomePage : Fragment()
private class DetailsPage : Fragment()
private class PlayerPage : Fragment()

private val home = Destination.Fragment(HomePage::class, mockk(relaxed = true))
private val details = Destination.Fragment(DetailsPage::class, mockk(relaxed = true))
private val player = Destination.Fragment(PlayerPage::class, mockk(relaxed = true))

private fun NavigationRepository.start(recreated: Boolean) =
	startNavigation(currentAction.replayCache.lastOrNull(), canGoBack, recreated)

class StartNavigationTests : FunSpec({
	lateinit var repository: NavigationRepositoryImpl

	beforeTest { repository = NavigationRepositoryImpl(home) }

	test("cold launch replays StartupActivity's home") {
		repository.reset(clearHistory = true)

		repository.start(recreated = false) shouldBe StartNavigation.REPLAY
	}

	test("deep link replays StartupActivity's destination, not home") {
		repository.reset(details, clearHistory = true)

		repository.start(recreated = false) shouldBe StartNavigation.REPLAY
	}

	test("fresh process resumed without StartupActivity starts over on home") {
		repository.start(recreated = true) shouldBe StartNavigation.RESET_CURRENT
		repository.currentDestination shouldBe null
	}

	test("process death restore after StartupActivity replays its home (task 72)") {
		// The 2026-10-21 Bravia black screen: StartupActivity reset to home in the new process,
		// then the old MainActivity was recreated from its saved state.
		repository.reset(clearHistory = true)

		repository.start(recreated = true) shouldBe StartNavigation.REPLAY
	}

	test("process death restore of a deep link still lands on the deep link") {
		repository.reset(details, clearHistory = true)

		repository.start(recreated = true) shouldBe StartNavigation.REPLAY
	}

	test("recreation after a push starts over on the pushed page") {
		repository.reset(clearHistory = true)
		repository.navigate(details)

		repository.start(recreated = true) shouldBe StartNavigation.RESET_CURRENT
		repository.currentDestination shouldBe details
	}

	test("recreation after Back starts over where Back landed, it does not go back again") {
		repository.reset(clearHistory = true)
		repository.navigate(details)
		repository.navigate(player)
		repository.goBack()

		repository.start(recreated = true) shouldBe StartNavigation.RESET_CURRENT
		repository.currentDestination shouldBe details
	}

	test("recreation after Back to the bottom starts over on home") {
		repository.reset(clearHistory = true)
		repository.navigate(details)
		repository.goBack()

		repository.start(recreated = true) shouldBe StartNavigation.RESET_CURRENT
		repository.currentDestination shouldBe null
	}

	test("a new MainActivity over a stale back stack goes home") {
		repository.reset(clearHistory = true)
		repository.navigate(details)

		repository.start(recreated = false) shouldBe StartNavigation.RESET_HOME
	}
})

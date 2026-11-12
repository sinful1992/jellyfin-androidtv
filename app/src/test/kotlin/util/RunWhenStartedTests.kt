package org.jellyfin.androidtv.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

private class Page : LifecycleOwner {
	val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
	override val lifecycle: Lifecycle get() = registry
}

class RunWhenStartedTests : FunSpec({
	test("work arriving while the page is started runs at once") {
		val page = Page().apply { registry.currentState = Lifecycle.State.RESUMED }
		val ran = mutableListOf<String>()
		val gate = RunWhenStarted(page.lifecycle)
		gate.run { ran += "item" }
		ran shouldBe listOf("item")
		gate.isHeld shouldBe false
	}

	test("work arriving while the page was built in a stopped app is kept and runs on the next start (task 148)") {
		val page = Page().apply { registry.currentState = Lifecycle.State.CREATED }
		val ran = mutableListOf<String>()
		val gate = RunWhenStarted(page.lifecycle)
		gate.run { ran += "item" }
		ran.shouldBeEmpty()
		gate.isHeld shouldBe true
		page.registry.currentState = Lifecycle.State.STARTED
		ran shouldBe listOf("item")
		gate.isHeld shouldBe false
	}

	test("held work sees the page as started when it runs, so a nested run does not hold it again") {
		val page = Page().apply { registry.currentState = Lifecycle.State.CREATED }
		val ran = mutableListOf<String>()
		val gate = RunWhenStarted(page.lifecycle)
		gate.run { gate.run { ran += "row" } }
		page.registry.currentState = Lifecycle.State.RESUMED
		ran shouldBe listOf("row")
		gate.isHeld shouldBe false
	}

	test("only the newest held work runs, once") {
		val page = Page().apply { registry.currentState = Lifecycle.State.CREATED }
		val ran = mutableListOf<String>()
		val gate = RunWhenStarted(page.lifecycle)
		gate.run { ran += "old" }
		gate.run { ran += "new" }
		page.registry.currentState = Lifecycle.State.RESUMED
		page.registry.currentState = Lifecycle.State.CREATED
		page.registry.currentState = Lifecycle.State.RESUMED
		ran shouldBe listOf("new")
	}

	test("work arriving after the page stopped again waits for the following start") {
		val page = Page().apply { registry.currentState = Lifecycle.State.RESUMED }
		val ran = mutableListOf<String>()
		val gate = RunWhenStarted(page.lifecycle)
		page.registry.currentState = Lifecycle.State.CREATED
		gate.run { ran += "item" }
		ran.shouldBeEmpty()
		page.registry.currentState = Lifecycle.State.STARTED
		ran shouldBe listOf("item")
	}

	test("clear forgets held work, for a page that builds its view again") {
		val page = Page().apply { registry.currentState = Lifecycle.State.CREATED }
		val ran = mutableListOf<String>()
		val gate = RunWhenStarted(page.lifecycle)
		gate.run { ran += "stale" }
		gate.clear()
		gate.isHeld shouldBe false
		page.registry.currentState = Lifecycle.State.RESUMED
		ran.shouldBeEmpty()
	}

	test("a page destroyed before it started never runs the held work") {
		val page = Page().apply { registry.currentState = Lifecycle.State.CREATED }
		val ran = mutableListOf<String>()
		val gate = RunWhenStarted(page.lifecycle)
		gate.run { ran += "item" }
		page.registry.currentState = Lifecycle.State.DESTROYED
		gate.isHeld shouldBe false
		gate.run { ran += "late" }
		gate.isHeld shouldBe false
		ran.shouldBeEmpty()
	}

	test("work from a page that is still being created (INITIALIZED) is held too") {
		val page = Page()
		val ran = mutableListOf<String>()
		val gate = RunWhenStarted(page.lifecycle)
		gate.run { ran += "item" }
		page.registry.currentState = Lifecycle.State.CREATED
		ran.shouldBeEmpty()
		page.registry.currentState = Lifecycle.State.STARTED
		ran shouldBe listOf("item")
	}
})

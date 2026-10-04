package org.jellyfin.androidtv.ui.navigation.focus

import android.view.View
import android.view.ViewGroup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

private fun group(parent: ViewGroup? = null): ViewGroup = mockk(relaxed = true) {
	every { this@mockk.parent } returns parent
	every { focusedChild } returns null
}

class StaleFocusTests : FunSpec({
	test("intact chain down to a focused leaf has no stale owner") {
		val root = group()
		val grid = group(root)
		val card = mockk<View>(relaxed = true) { every { parent } returns grid }
		every { root.focusedChild } returns grid
		every { grid.focusedChild } returns card

		findStaleFocusOwner(root).shouldBeNull()
		repairStaleFocus(root).shouldBeNull()
		verify(exactly = 0) { grid.clearChildFocus(any()) }
	}

	test("nothing focused has no stale owner") {
		findStaleFocusOwner(group()).shouldBeNull()
	}

	test("detached container under an attached grid is found") {
		val root = group()
		val grid = group(root)
		// The holder was detached from the grid, but still holds the focused card.
		val holder = group(parent = null)
		val card = mockk<View>(relaxed = true) { every { parent } returns holder }
		every { root.focusedChild } returns grid
		every { grid.focusedChild } returns holder
		every { holder.focusedChild } returns card

		findStaleFocusOwner(root) shouldBe grid
	}

	test("a focused child re-parented elsewhere is stale") {
		val root = group()
		val other = group(root)
		val child = group(other)
		every { root.focusedChild } returns child

		findStaleFocusOwner(root) shouldBe root
	}

	test("repair clears the stale child and refocuses the owner") {
		val root = group()
		val grid = group(root)
		val holder = group(parent = null)
		every { root.focusedChild } returns grid
		every { grid.focusedChild } returns holder

		repairStaleFocus(root) shouldBe grid
		verify { grid.clearChildFocus(holder) }
		verify { grid.requestFocus() }
	}
})

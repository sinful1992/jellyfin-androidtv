package org.jellyfin.androidtv.ui.navigation.layout

import android.view.View
import android.view.ViewGroup
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

// Relaxed mocks answer 0 for width, height and visibility, and 0 is VISIBLE, so every node states all three.
private fun view(width: Int, height: Int, visibility: Int = View.VISIBLE): View = mockk(relaxed = true) {
	every { this@mockk.width } returns width
	every { this@mockk.height } returns height
	every { this@mockk.visibility } returns visibility
	every { parent } returns null
}

private fun group(width: Int, height: Int, vararg children: View, visibility: Int = View.VISIBLE): ViewGroup =
	mockk(relaxed = true) {
		every { this@mockk.width } returns width
		every { this@mockk.height } returns height
		every { this@mockk.visibility } returns visibility
		every { parent } returns null
		every { childCount } returns children.size
		every { getChildAt(any()) } answers { children.getOrNull(firstArg()) }
	}

class CollapsedFragmentTests : FunSpec({
	val hosts = mutableSetOf<ViewGroup>()
	val isHost: (ViewGroup) -> Boolean = { it in hosts }

	beforeTest { hosts.clear() }

	test("healthy tree has no hit") {
		val grid = view(1920, 890)
		val inner = group(1920, 890, grid).also(hosts::add)
		val page = group(1920, 1080, inner)
		val container = group(1920, 1080, page).also(hosts::add)
		val root = group(1920, 1080, container)

		findCollapsedFragmentView(root, isHost).shouldBeNull()
	}

	test("0x0 views off the fragment levels never match") {
		// The gone action mode stub and hidden leanback row headers of a healthy screen.
		val stub = view(0, 0, View.GONE)
		val header = view(0, 0)
		val row = group(1920, 300, header)
		val page = group(1920, 1080, row)
		val container = group(1920, 1080, page).also(hosts::add)
		val root = group(1920, 1080, stub, container)

		findCollapsedFragmentView(root, isHost).shouldBeNull()
	}

	test("fragment root at 0x0 inside a sized container is a hit") {
		val page = view(0, 0)
		val container = group(1920, 1080, page).also(hosts::add)
		val root = group(1920, 1080, container)

		val hit = findCollapsedFragmentView(root, isHost).shouldNotBeNull()
		hit.view shouldBe page
		hit.parent shouldBe container
		hit.isHost shouldBe false
	}

	test("nested fragment root at 0x0 is a hit") {
		// The 09-20 shape: the home rows grid unmeasured in a correctly sized inner container.
		val grid = view(0, 0)
		val inner = group(1920, 890, grid).also(hosts::add)
		val page = group(1920, 1080, inner)
		val container = group(1920, 1080, page).also(hosts::add)
		val root = group(1920, 1080, container)

		findCollapsedFragmentView(root, isHost).shouldNotBeNull().view shouldBe grid
	}

	test("container at 0x0 inside a sized parent is a hit") {
		val container = group(0, 0).also(hosts::add)
		val root = group(1920, 1080, container)

		val hit = findCollapsedFragmentView(root, isHost).shouldNotBeNull()
		hit.view shouldBe container
		hit.isHost shouldBe true
	}

	test("only the top collapsed level is reported") {
		// The 09-21 shape: everything below HomeFragment's own view is 0x0 too.
		val grid = view(0, 0)
		val inner = group(0, 0, grid).also(hosts::add)
		val page = group(0, 0, inner)
		val container = group(1920, 1080, page).also(hosts::add)
		val root = group(1920, 1080, container)

		findCollapsedFragmentView(root, isHost).shouldNotBeNull().view shouldBe page
	}

	test("a gone fragment root at 0x0 is not a hit") {
		val page = view(0, 0, View.GONE)
		val container = group(1920, 1080, page).also(hosts::add)
		val root = group(1920, 1080, container)

		findCollapsedFragmentView(root, isHost).shouldBeNull()
	}

	test("nothing under an invisible group is checked") {
		val page = view(0, 0)
		val container = group(1920, 1080, page).also(hosts::add)
		val hidden = group(1920, 1080, container, visibility = View.INVISIBLE)
		val root = group(1920, 1080, hidden)

		findCollapsedFragmentView(root, isHost).shouldBeNull()
	}

	test("layout is requested on every level up to the window") {
		val root = group(1920, 1080)
		val container = group(1920, 1080)
		val page = view(0, 0)
		every { page.parent } returns container
		every { container.parent } returns root

		requestLayoutToWindow(page)

		verify { page.requestLayout() }
		verify { container.requestLayout() }
		verify { root.requestLayout() }
	}
})

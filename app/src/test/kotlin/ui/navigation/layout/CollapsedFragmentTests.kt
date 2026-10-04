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

	// The home screen as the healthy 09-21 capture dumps it, from content down to the rows grid:
	// content > ComposeView > z6 > tc > lx5 > tw0 > FragmentContainerView app:id/container >
	// ComposeView (HomeFragment) > z6 > tc > lx5 (0,190) > FragmentContainerView > VerticalGridView
	// container_list (HomeRowsFragment's own view, no scale frame in between) > row with a 1920x0 header dock.
	fun homeScreen(pageSize: Pair<Int, Int>, innerSize: Pair<Int, Int>, gridSize: Pair<Int, Int>): ViewGroup {
		val headerDock = view(1920, 0)
		val row = group(1920, 576, headerDock)
		val grid = group(gridSize.first, gridSize.second, row)
		val inner = group(innerSize.first, innerSize.second, grid).also(hosts::add)
		val composeInner = group(innerSize.first, innerSize.second, inner)
		val page = group(pageSize.first, pageSize.second, group(pageSize.first, pageSize.second, group(pageSize.first, pageSize.second, composeInner)))
		val container = group(1920, 1080, page).also(hosts::add)
		val host = group(1920, 1080, group(1920, 1080, group(1920, 1080, group(1920, 1080, container))))
		val stub = view(0, 0, View.GONE)
		return group(1920, 1080, stub, group(1920, 1080, host))
	}

	test("healthy home capture has no hit") {
		findCollapsedFragmentView(homeScreen(1920 to 1080, 1920 to 890, 1920 to 890), isHost).shouldBeNull()
	}

	test("09-20 dump: rows grid 0x0 in a 1920x890 container is a hit") {
		val root = homeScreen(1920 to 1080, 1920 to 890, 0 to 0)

		val hit = findCollapsedFragmentView(root, isHost).shouldNotBeNull()
		hit.isHost shouldBe false
		hit.parent.width shouldBe 1920
		hit.parent.height shouldBe 890
	}

	test("09-21 dump: HomeFragment's ComposeView 0x0 in the 1920x1080 container is the hit") {
		val root = homeScreen(0 to 0, 0 to 0, 0 to 0)

		val hit = findCollapsedFragmentView(root, isHost).shouldNotBeNull()
		hit.isHost shouldBe false
		hit.parent.width shouldBe 1920
		hit.parent.height shouldBe 1080
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

package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

private fun film(name: String) = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE, name = name)

class HomeHeroRowTests : FunSpec({
	test("a new row offers Play, without the focus, and counts as selected") {
		val row = HomeHeroRow(listOf(film("A"), film("B")))
		row.focus.value shouldBe HomeHeroFocus.Play
		row.focused.value shouldBe false
		row.selected.value shouldBe true
	}

	test("focus state is the row's, so the home screen reads what the keys set") {
		val row = HomeHeroRow(listOf(film("A"), film("B")))
		row.focus.value = HomeHeroFocus.Dots
		row.focused.value = true
		row.focus.value shouldBe HomeHeroFocus.Dots
		row.focused.value shouldBe true
	}

	test("deselecting the row is what lets the top of the screen follow the cards") {
		val row = HomeHeroRow(listOf(film("A")))
		row.setSelected(false)
		row.selected.value shouldBe false
	}

	test("stepping wraps and keeps the direction for the strip") {
		val row = HomeHeroRow(listOf(film("A"), film("B"), film("C")))
		row.step(-1) shouldBe true
		row.selection.value.index shouldBe 2
		row.selection.value.forward shouldBe false
		row.step(1) shouldBe true
		row.selection.value.index shouldBe 0
	}

	test("a shortlist of one has nowhere to step") {
		val row = HomeHeroRow(listOf(film("A")))
		row.step(1) shouldBe false
		row.selection.value.index shouldBe 0
	}

	test("a refreshed shortlist keeps the title on show") {
		val a = film("A")
		val b = film("B")
		val row = HomeHeroRow(listOf(a, b))
		row.step(1)
		row.replace(listOf(film("C"), b, a))
		row.selection.value.item.id shouldBe b.id
	}
})

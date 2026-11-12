package org.jellyfin.androidtv.ui.itemdetail

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

// FullDetailsFragment needs Koin, an activity and the server, so this checks its wiring in the source: the
// item and its rows must go through RunWhenStarted, not be dropped by a bare STARTED guard (task 148).
private val source = File("src/main/java/org/jellyfin/androidtv/ui/itemdetail/FullDetailsFragment.java").readText()

private fun body(signature: String): String {
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

private const val DROP_GUARD = "isAtLeast(Lifecycle.State.STARTED)) return;"

class FullDetailsHeldUntilStartedTests : FunSpec({
	test("the page holds its gate on its own lifecycle") {
		source shouldContain "new RunWhenStarted(getLifecycle())"
	}

	test("an item that arrives while the page is not started is held, not dropped") {
		val setBaseItem = body("public void setBaseItem(BaseItemDto item)")
		setBaseItem shouldContain "mWhenStarted.run("
		setBaseItem shouldNotContain DROP_GUARD
	}

	test("rows built while the page is not started are held, not dropped") {
		val onPostExecute = body("protected void onPostExecute(MyDetailsOverviewRow detailsOverviewRow)")
		onPostExecute shouldContain "mWhenStarted.run("
		onPostExecute shouldContain "showDetailsRow(detailsOverviewRow)"
		onPostExecute shouldNotContain DROP_GUARD
	}

	test("a page that builds its view again forgets work held for the old view before loading") {
		val onCreateView = body("public View onCreateView(")
		val clear = onCreateView.indexOf("mWhenStarted.clear();")
		(clear in 0 until onCreateView.indexOf("loadItem(mItemId);")).shouldBeTrue()
	}

	test("the refresh after playback does not read an item that has not arrived yet") {
		source shouldContain "if (mBaseItem != null && lastPlaybackTime != null"
	}
})

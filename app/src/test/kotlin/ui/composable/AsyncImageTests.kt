package org.jellyfin.androidtv.ui.composable

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Task 198: an image inside a card must not be an Android View hosted in Compose. Every layout of a
 * hosted view makes the whole window redo every ComposeView's screen position, which made the
 * Movies grid spend 33 ms per frame on the SK4 Pro.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AsyncImageTests {
	@Before
	fun koin() {
		val app = RuntimeEnvironment.getApplication()
		startKoin { modules(module { single { ImageLoader(app) } }) }
	}

	@After
	fun stop() = stopKoin()

	private fun View.descendants(): Sequence<View> = sequence {
		if (this@descendants is ViewGroup) for (i in 0 until childCount) {
			val child = getChildAt(i)
			yield(child)
			yieldAll(child.descendants())
		}
	}

	private fun hostedViews(content: @androidx.compose.runtime.Composable () -> Unit): List<View> {
		val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
		activity.setContent(content = content)
		org.robolectric.shadows.ShadowLooper.idleMainLooper()
		val root = activity.window.decorView
		return root.descendants().filter { it is ImageView }.toList()
	}

	@Test
	fun `a loading image hosts no ImageView`() {
		val views = hostedViews {
			AsyncImage(
				url = "http://localhost:1/Items/x/Images/Primary?maxHeight=300&quality=60",
				blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
				aspectRatio = 2f / 3f,
				modifier = Modifier.size(100.dp, 150.dp),
			)
		}
		assertTrue("ImageViews in the tree: $views", views.isEmpty())
	}

	@Test
	fun `a grid of cards hosts no ImageView`() {
		val views = hostedViews {
			Column {
				repeat(12) { i ->
					AsyncImage(url = "http://localhost:1/Items/$i/Images/Primary", modifier = Modifier.size(50.dp))
				}
			}
		}
		assertTrue("ImageViews in the tree: $views", views.isEmpty())
	}

	@Test
	fun `no url and no placeholder draws nothing and hosts no ImageView`() {
		assertTrue(hostedViews { AsyncImage(url = null) }.isEmpty())
	}

	@Test
	fun `scale types draw like the ImageView did`() {
		val expected = mapOf(
			ImageView.ScaleType.CENTER_CROP to (ContentScale.Crop to Alignment.Center),
			ImageView.ScaleType.CENTER_INSIDE to (ContentScale.Inside to Alignment.Center),
			ImageView.ScaleType.CENTER to (ContentScale.None to Alignment.Center),
			ImageView.ScaleType.FIT_XY to (ContentScale.FillBounds to Alignment.Center),
			ImageView.ScaleType.FIT_START to (ContentScale.Fit to Alignment.TopStart),
			ImageView.ScaleType.FIT_END to (ContentScale.Fit to Alignment.BottomEnd),
			ImageView.ScaleType.FIT_CENTER to (ContentScale.Fit to Alignment.Center),
			ImageView.ScaleType.MATRIX to (ContentScale.Fit to Alignment.Center),
		)
		for ((type, compose) in expected) assertEquals(type.name, compose, type.toCompose())
		assertEquals(ContentScale.Fit to Alignment.Center, (null as ImageView.ScaleType?).toCompose())
	}

	@Test
	fun `blurhash size keeps the short side at the resolution`() {
		assertEquals(IntSize(32, 48), blurHashSize(2f / 3f, 32))
		assertEquals(IntSize(57, 32), blurHashSize(16f / 9f, 32))
		assertEquals(IntSize(32, 32), blurHashSize(1f, 32))
		assertEquals(IntSize(32, 3200), blurHashSize(0.01f, 32))
	}

	@Test
	fun `quality upgrade is the same image, another picture is not`() {
		val preview = "http://s/Items/a/Images/Primary?maxHeight=300&quality=60"
		val full = "http://s/Items/a/Images/Primary?maxHeight=300&quality=85"
		assertTrue(isSameImage(preview, full))
		assertTrue(!isSameImage(preview, preview))
		assertTrue(!isSameImage(preview, "http://s/Items/b/Images/Primary?maxHeight=300&quality=85"))
		assertTrue(!isSameImage(preview, null))
	}
}

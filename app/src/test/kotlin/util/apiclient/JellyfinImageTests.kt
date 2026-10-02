package org.jellyfin.androidtv.util.apiclient

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.ui.composable.isSameImage
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.operations.Api
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID
import kotlin.reflect.KClass

/** An ApiClient whose URLs are "<path>?<query>", with the query in a stable order. */
private fun fakeApi(): ApiClient = mockk<ApiClient>().also { api ->
	every { api.getOrCreateApi(any<KClass<Api>>(), any()) } answers {
		@Suppress("UNCHECKED_CAST")
		(secondArg<(ApiClient) -> Api>()).invoke(api)
	}
	every { api.createUrl(any(), any(), any(), any()) } answers {
		val query = thirdArg<Map<String, Any?>>()
			.filterValues { it != null }
			.toSortedMap()
			.entries
			.joinToString("&") { (key, value) -> "$key=$value" }
		"${firstArg<String>()}?$query"
	}
}

private fun image(source: JellyfinImageSource = JellyfinImageSource.ITEM) = JellyfinImage(
	item = UUID.fromString("a616f531-c7d8-927b-4c5c-0d84a183d714"),
	source = source,
	type = ImageType.PRIMARY,
	tag = "t",
	blurHash = null,
	aspectRatio = 2f / 3f,
	index = null,
)

class JellyfinImageTests : FunSpec({
	test("heights round up to their bucket, never down") {
		bucketImageSize(200, 300) shouldBe (200 to 300)
		bucketImageSize(100, 150) shouldBe (134 to 200)
		bucketImageSize(173, 260) shouldBe (200 to 300)
		bucketImageSize(200, 301) shouldBe (300 to 450)
		bucketImageSize(1920, 1080) shouldBe (1920 to 1080)
	}

	test("every height up to the largest bucket lands on a bucket at least as tall") {
		for (height in 1..1080) {
			val (_, bucket) = bucketImageSize(null, height)
			(bucket in imageHeightBuckets.toList()) shouldBe true
			(bucket!! >= height) shouldBe true
		}
	}

	test("the bucket keeps the requested shape, rounding the width up") {
		val (width, height) = bucketImageSize(213, 320)
		height shouldBe 450
		width shouldBe 300 // 213 * 450 / 320 = 299.5
	}

	test("heights past the largest bucket, width-only and empty requests are left as asked") {
		bucketImageSize(3840, 2160) shouldBe (3840 to 2160)
		bucketImageSize(400, null) shouldBe (400 to null)
		bucketImageSize(null, null) shouldBe (null to null)
	}

	test("getUrl always asks for a quality, full by default") {
		val url = image().getUrl(fakeApi(), maxWidth = 200, maxHeight = 300)
		url shouldContain "quality=${ImageQuality.FULL}"
	}

	test("getUrl passes the preview quality and the bucketed size") {
		val url = image().getUrl(fakeApi(), maxWidth = 173, maxHeight = 260, quality = ImageQuality.PREVIEW)
		url shouldContain "quality=${ImageQuality.PREVIEW}"
		url shouldContain "maxHeight=300"
		url shouldContain "maxWidth=200"
	}

	test("the hero size is unchanged") {
		val url = image().getUrl(fakeApi(), maxWidth = 1920, maxHeight = 1080)
		url shouldContain "maxHeight=1080"
		url shouldContain "maxWidth=1920"
	}

	test("fill sizes are not bucketed") {
		val url = image().getUrl(fakeApi(), fillWidth = 213, fillHeight = 320)
		url shouldContain "fillHeight=320"
		url shouldContain "fillWidth=213"
	}

	test("user images are not item images and carry no size or quality") {
		val url = image(JellyfinImageSource.USER).getUrl(fakeApi(), maxWidth = 200, maxHeight = 300)
		url shouldNotContain "quality"
	}

	test("isSameImage is true only for URLs that differ in quality alone") {
		val base = "http://s/Items/1/Images/Primary"
		isSameImage("$base?maxHeight=300&quality=60&tag=t", "$base?maxHeight=300&quality=85&tag=t") shouldBe true
		isSameImage("$base?maxHeight=300&tag=t&quality=60", "$base?maxHeight=300&tag=t&quality=85") shouldBe true
		isSameImage("$base?quality=60", "$base?quality=85") shouldBe true
		isSameImage("$base?quality=60&tag=t", "$base?quality=60&tag=t") shouldBe false
		isSameImage("$base?quality=60&tag=t", "$base?quality=85&tag=u") shouldBe false
		isSameImage("$base?quality=60", "http://s/Items/2/Images/Primary?quality=85") shouldBe false
		isSameImage("$base?quality=60", null) shouldBe false
	}
})

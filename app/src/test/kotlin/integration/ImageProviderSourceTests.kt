package org.jellyfin.androidtv.integration.provider

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ImageProviderSourceTests : FunSpec({
	test("Jellyfin server pictures are allowed") {
		val src = "http://192.168.1.15:8096/Items/0b1c/Images/Primary?maxHeight=480&tag=abc"
		allowedImageSource(src) shouldBe src
		allowedImageSource("https://jf.example.org/Items/1/Images/Thumb") shouldBe "https://jf.example.org/Items/1/Images/Thumb"
	}

	test("the bundled placeholder is allowed") {
		val src = "android.resource://org.jellyfin.androidtv/drawable/placeholder_icon"
		allowedImageSource(src) shouldBe src
	}

	test("unusual characters in the path or query still load") {
		val src = "http://host:8096/Items/1/Images/Primary?tag=a|b&name=a b[1]"
		allowedImageSource(src) shouldBe src
	}

	test("scheme case does not matter") {
		allowedImageSource("HTTP://host/a.png") shouldBe "HTTP://host/a.png"
	}

	test("addresses that read local storage are refused") {
		allowedImageSource("file:///data/data/org.jellyfin.androidtv/shared_prefs/x.xml") shouldBe null
		allowedImageSource("content://com.android.providers.media.documents/document/1") shouldBe null
		allowedImageSource("/data/data/org.jellyfin.androidtv/files/a.png") shouldBe null
		allowedImageSource("jar:file:///x.jar!/a.png") shouldBe null
	}

	test("missing, blank, hostless and broken addresses are refused") {
		allowedImageSource(null) shouldBe null
		allowedImageSource("") shouldBe null
		allowedImageSource("   ") shouldBe null
		allowedImageSource("http:///Items/1") shouldBe null
		allowedImageSource("http:relative") shouldBe null
		allowedImageSource("http://exa mple.org/a") shouldBe null
		allowedImageSource("://host/a") shouldBe null
		allowedImageSource("javascript://host/a") shouldBe null
		allowedImageSource("ftp://host/a.png") shouldBe null
	}
})

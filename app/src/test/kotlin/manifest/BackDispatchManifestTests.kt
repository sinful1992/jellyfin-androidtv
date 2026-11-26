package org.jellyfin.androidtv.manifest

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/**
 * Task 198: with targetSdk 36, Android 16+ turns predictive back on unless the manifest says no, and then
 * KEYCODE_BACK never reaches the views (ViewRootImpl NativePreImeInputStage takes it first). The player
 * overlay, MainActivity's key forwarding and Leanback's onKey all act on that key, so the app opts out.
 */
class BackDispatchManifestTests : FunSpec({
	val document = DocumentBuilderFactory.newInstance()
		.apply { isNamespaceAware = true }
		.newDocumentBuilder()
		.parse(File("src/main/AndroidManifest.xml"))

	fun Element.backAttr(): String? =
		if (hasAttributeNS(ANDROID_NS, "enableOnBackInvokedCallback")) getAttributeNS(ANDROID_NS, "enableOnBackInvokedCallback")
		else null

	test("the application opts out of predictive back, so Back stays a key event on Android 16") {
		val application = document.getElementsByTagName("application").item(0) as Element
		application.backAttr() shouldBe "false"
	}

	test("no activity turns predictive back back on (an activity value wins over the application's)") {
		val activities = document.getElementsByTagName("activity")
		for (i in 0 until activities.length) {
			val activity = activities.item(i) as Element
			val value = activity.backAttr()
			(value == null || value == "false") shouldBe true
		}
	}
})

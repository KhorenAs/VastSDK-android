package com.kinodaran.vast.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [VastAd.isUiHidden] is read straight out of the response, so it is tested the
 * way a response arrives: as XML through the parser, not as a hand-built model.
 */
class VastUiHiddenTest {

    // MARK: - Reading the key

    @Test
    fun theKeyIsReadFromTheResponse() {
        assertTrue(ad("<UiHidden>1</UiHidden>").isUiHidden)
    }

    /** The live AdFox tag sends `UiHideable`; `UiHidden` is the other spelling in the same family. */
    @Test
    fun bothSpellingsAreRead() {
        for (key in listOf("UiHidden", "UiHideable")) {
            assertTrue(ad("<$key>1</$key>").isUiHidden, "$key was not read")
        }
    }

    /** Presence is the request the vendor intends, so an element carrying no text still counts. */
    @Test
    fun anEmptyElementStillAsksForIt() {
        assertTrue(ad("<UiHidden/>").isUiHidden)
        assertTrue(ad("<UiHidden></UiHidden>").isUiHidden)
    }

    @Test
    fun aCdataValueIsRead() {
        assertTrue(ad("<UiHidden><![CDATA[1]]></UiHidden>").isUiHidden)
        assertFalse(ad("<UiHidden><![CDATA[0]]></UiHidden>").isUiHidden)
    }

    /** A server sending `0` means "keep your UI", and inverting that would be worse than not reading the key at all. */
    @Test
    fun anExplicitlyNegativeValueIsHonoured() {
        for (value in listOf("0", "false", "NO")) {
            assertFalse(ad("<UiHidden>$value</UiHidden>").isUiHidden, "$value was read as yes")
        }
    }

    // MARK: - What it must not read

    @Test
    fun aResponseWithoutTheExtensionAsksForNothing() {
        assertFalse(firstAd(response(extensions = null)).isUiHidden)
    }

    /** Another vendor's extension is not this one, whatever it happens to contain. */
    @Test
    fun theKeyIsIgnoredOutsideUiSettings() {
        assertFalse(firstAd(response("""<Extension type="somethingElse"><UiHidden>1</UiHidden></Extension>""")).isUiHidden)
    }

    /** The raw XML is still handed over untouched (§3.18) — interpreting one key must not consume the extension. */
    @Test
    fun theExtensionIsStillHandedOverRaw() {
        val vendor = ad("<UiHideable>1</UiHideable>").extensions.first { it.type == "uiSettings" }
        assertTrue(vendor.xml.contains("UiHideable"))
        assertEquals("1", vendor.value("UiHideable"))
    }

    // MARK: - Fixtures

    private fun ad(uiSettings: String): VastAd = firstAd(response("""<Extension type="uiSettings">$uiSettings</Extension>"""))

    private fun response(extensions: String?): String {
        val block = extensions?.let { "<Extensions>$it</Extensions>" }.orEmpty()
        return """
            <VAST version="4.3"><Ad id="a"><InLine>
              <AdSystem>test</AdSystem>
              <Impression><![CDATA[https://ads.test/impression]]></Impression>
              <Creatives><Creative><Linear>
                <Duration>00:00:20</Duration>
                <MediaFiles>
                  <MediaFile delivery="progressive" type="video/mp4"><![CDATA[https://ads.test/v.mp4]]></MediaFile>
                </MediaFiles>
              </Linear></Creative></Creatives>
              $block
            </InLine></Ad></VAST>
        """.trimIndent()
    }
}

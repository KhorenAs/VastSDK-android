package com.kinodaran.vast.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VastParserTest {

    private val parser = VastParser()

    // MARK: - InLine

    @Test
    fun parsesInLineLinear() {
        val document = parser.parse(fixture("inline-linear"))
        assertEquals("4.3", document.version)
        assertEquals(1, document.entries.size)

        val ad = inLine(document.entries[0])
        assertEquals("20001", ad.id)
        assertEquals(16.0, ad.linear.duration)
        assertEquals(1, ad.linear.mediaFiles.size)
        assertEquals(1, ad.impressions.size)
        assertEquals(
            setOf(
                VastTrackingEvent.START, VastTrackingEvent.FIRST_QUARTILE, VastTrackingEvent.MIDPOINT,
                VastTrackingEvent.THIRD_QUARTILE, VastTrackingEvent.COMPLETE,
            ),
            ad.linear.trackingEvents.keys,
        )
    }

    /**
     * The same document writes some URIs bare and others in CDATA. Both paths
     * arrive through different callbacks and must produce equal URLs.
     */
    @Test
    fun readsUrisFromBothPlainTextAndCdata() {
        val ad = inLine(parser.parse(fixture("inline-linear")).entries[0])

        // <Error>http://example.com/error</Error> — no CDATA
        assertEquals("http://example.com/error", ad.errors.first())
        // <MediaFile><![CDATA[ ... ]]></MediaFile> — CDATA, padded with newlines
        val media = ad.linear.mediaFiles.first()
        assertTrue(media.url.startsWith("https://"))
        assertFalse(media.url.contains("\n"))
    }

    /** VAST booleans appear as `1`/`0` in practice, not only `true`/`false`. */
    @Test
    fun parsesNumericBooleanAttributes() {
        val media = inLine(parser.parse(fixture("inline-linear")).entries[0]).linear.mediaFiles.first()

        assertTrue(media.scalable) // scalable="1"
        assertTrue(media.maintainAspectRatio) // maintainAspectRatio="1"
        assertEquals(500, media.bitrate)
        assertEquals(360, media.minBitrate)
        assertEquals(1080, media.maxBitrate)
        assertEquals(VastAd.Delivery.PROGRESSIVE, media.delivery)
    }

    @Test
    fun capturesExtensionsWithoutInterpretingThem() {
        val ad = inLine(parser.parse(fixture("inline-linear")).entries[0])
        assertTrue(ad.extensions.isNotEmpty())
        assertTrue(ad.extensions.first().xml.isNotEmpty())
    }

    /**
     * Reading a vendor key should not mean writing a string scanner in every host
     * — but the SDK still assigns the value no meaning.
     */
    @Test
    fun extensionValueReadsAChildElement() {
        assertEquals("1", VastAd.Extension("uiSettings", "<UiHideable>1</UiHideable>").value("UiHideable"))
        assertEquals(
            "0",
            VastAd.Extension("uiSettings", "<UiHideable><![CDATA[0]]></UiHideable>").value("UiHideable"),
            "servers wrap in CDATA about as often as not",
        )
        assertEquals(
            "1",
            VastAd.Extension("uiSettings", """<UiHideable mode="strict"> 1 </UiHideable>""").value("UiHideable"),
        )
    }

    /** Absent and present-but-empty are different facts, and a host acting on the key needs to tell them apart. */
    @Test
    fun extensionValueDistinguishesAbsentFromEmpty() {
        val extension = VastAd.Extension("uiSettings", "<UiHideable></UiHideable><Skin/>")
        assertEquals("", extension.value("UiHideable"))
        assertEquals("", extension.value("Skin"), "self-closing carries no text")
        assertNull(extension.value("Missing"))
    }

    // MARK: - Wrapper

    @Test
    fun parsesWrapperAndItsAttributes() {
        val wrapper = wrapper(parser.parse(fixture("wrapper-a")).entries[0])

        assertEquals("wrapper-b.xml", wrapper.tagUri)
        assertTrue(wrapper.followAdditionalWrappers, "defaults to true per §3.19")
        assertFalse(wrapper.allowMultipleAds, "defaults to false per §3.19")
        assertEquals(1, wrapper.impressions.size)
        assertEquals(1, wrapper.errors.size)
    }

    @Test
    fun wrapperAdSequenceIsPreserved() {
        val document = parser.parse(fixture("wrapper-multiple-ads"))

        assertEquals(listOf(null, 1, 2, null), document.entries.map { it.sequence })
        assertTrue(wrapper(document.entries[0]).allowMultipleAds)
    }

    // MARK: - No ad

    @Test
    fun emptyResponseIsNoAdWithRootError() {
        val document = parser.parse(fixture("empty-no-ad"))

        assertTrue(document.isNoAd)
        assertEquals("http://example.com/empty-no-ad", document.noAdError)
    }

    // MARK: - Errors

    /**
     * A VAST 1.0 document is well-formed XML under `<VideoAdServingTemplate>`.
     * Reporting it as a parse error would send the ad server the wrong code.
     */
    @Test
    fun vast1DocumentReportsVersionNotSupported() {
        val error = assertFailsWith<VastException> { parser.parse(fixture("outdated-vast")) }.error
        assertEquals(VastError.VERSION_NOT_SUPPORTED, error)
        assertEquals(102, error.code)
    }

    @Test
    fun malformedDocumentReportsParseError() {
        val error = assertFailsWith<VastException> { parser.parse(fixture("invalid-xmlfile")) }.error
        assertEquals(VastError.XML_PARSING, error)
        assertEquals(100, error.code)
    }

    // MARK: - Offsets

    @Test
    fun parsesSkipOffsetAsTimestamp() {
        val ad = firstAd(linear(attributes = """skipoffset="00:00:07.500""""))

        assertEquals(VastAd.SkipOffset.Time(7.5), ad.linear.skipOffset)
        assertEquals(7.5, ad.linear.resolvedSkipOffset())
        assertTrue(ad.isSkippable)
    }

    @Test
    fun parsesSkipOffsetAsPercentage() {
        val ad = firstAd(linear(attributes = """skipoffset="25%""""))

        assertEquals(VastAd.SkipOffset.Percent(25.0), ad.linear.skipOffset)
        assertEquals(5.0, ad.linear.resolvedSkipOffset()!!, 0.001)
    }

    @Test
    fun adWithoutSkipOffsetIsNotSkippable() {
        val ad = firstAd(linear())
        assertNull(ad.linear.skipOffset)
        assertFalse(ad.isSkippable)
    }

    /** `progress` carries an offset and is modelled apart from the quartiles. */
    @Test
    fun progressEventIsSeparatedFromQuartileEvents() {
        val ad = firstAd(
            linear(
                tracking = """
                <Tracking event="start">https://t.test/start</Tracking>
                <Tracking event="progress" offset="00:00:12">https://t.test/p12</Tracking>
                <Tracking event="progress" offset="50%">https://t.test/p50</Tracking>
                """,
            ),
        )

        assertEquals(setOf(VastTrackingEvent.START), ad.linear.trackingEvents.keys)
        assertEquals(
            listOf(VastAd.SkipOffset.Time(12.0), VastAd.SkipOffset.Percent(50.0)),
            ad.linear.progressEvents.map { it.offset },
        )
    }

    @Test
    fun unknownTrackingEventIsIgnoredRatherThanFatal() {
        val ad = firstAd(
            linear(
                tracking = """
                <Tracking event="start">https://t.test/start</Tracking>
                <Tracking event="somethingTheSpecNeverDefined">https://t.test/x</Tracking>
                """,
            ),
        )

        assertEquals(setOf(VastTrackingEvent.START), ad.linear.trackingEvents.keys)
    }

    // MARK: - Stored URI form

    /**
     * A macro's brackets are stored encoded, as Foundation's `URL(string:)` stores
     * them, so both SDKs hold the same string and the expander finds the macro
     * either way.
     */
    @Test
    fun bracketsInAUriAreStoredEncoded() {
        val ad = firstAd(
            linear(tracking = """<Tracking event="start"><![CDATA[https://t.test/s?e=[ERRORCODE]&x=%3A]]></Tracking>"""),
        )
        assertEquals(
            "https://t.test/s?e=%5BERRORCODE%5D&x=%3A",
            ad.linear.trackingEvents[VastTrackingEvent.START]?.single(),
            "an existing escape is left alone",
        )
    }

    // MARK: - Synthetic document

    private fun linear(attributes: String = "", tracking: String = ""): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <VAST version="4.3">
          <Ad id="synthetic">
            <InLine>
              <AdSystem>test</AdSystem>
              <Impression>https://t.test/impression</Impression>
              <Creatives><Creative id="1"><Linear $attributes>
                <Duration>00:00:20</Duration>
                <TrackingEvents>$tracking</TrackingEvents>
                <MediaFiles>
                  <MediaFile delivery="progressive" type="video/mp4">https://t.test/v.mp4</MediaFile>
                </MediaFiles>
              </Linear></Creative></Creatives>
            </InLine>
          </Ad>
        </VAST>
    """.trimIndent()
}

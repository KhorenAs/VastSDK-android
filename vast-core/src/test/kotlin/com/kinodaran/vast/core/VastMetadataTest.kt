package com.kinodaran.vast.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The VAST 4 elements a reporting pipeline needs. Two of them the specification
 * makes required, and skipping those would leave the most useful fields in a
 * response on the floor.
 */
class VastMetadataTest {

    // MARK: - Identity

    @Test
    fun theIdentifiersBothSidesQuoteAreParsed() {
        val ad = ad(
            inLine = "<AdServingId>c8b7d3a1-2f4e</AdServingId><Advertiser>Kinodaran</Advertiser>",
            creative = """<UniversalAdId idRegistry="Ad-ID">CNPA0484000H</UniversalAdId>""",
        )

        assertEquals("c8b7d3a1-2f4e", ad.adServingId)
        assertEquals("Kinodaran", ad.advertiser)
        assertEquals(listOf(VastAd.UniversalAdId("Ad-ID", "CNPA0484000H")), ad.universalAdIds)
    }

    /** `unknown` is a registry some servers really send, and it is a different fact from having sent nothing. */
    @Test
    fun anUnknownRegistryIsStillARegistry() {
        val ad = ad(creative = """<UniversalAdId idRegistry="unknown">abc123</UniversalAdId>""")
        assertEquals("unknown", ad.universalAdIds.first().registry)
    }

    @Test
    fun pricingAndCategoriesAreParsed() {
        val ad = ad(
            inLine = """
            <Pricing model="CPM" currency="USD">12.50</Pricing>
            <Category authority="https://iabtechlab.com/categories">IAB1-1</Category>
            <Expires>3600</Expires>
            """,
        )

        assertEquals(VastAd.Pricing("CPM", "USD", 12.5), ad.pricing)
        assertEquals(listOf(VastAd.Category("https://iabtechlab.com/categories", "IAB1-1")), ad.categories)
        // Plain seconds, unlike <Duration>'s timecode.
        assertEquals(3600.0, ad.expires)
    }

    // MARK: - Viewability

    @Test
    fun allThreeViewabilityOutcomesAreParsed() {
        val ad = ad(
            inLine = """
            <ViewableImpression id="vi-1">
              <Viewable><![CDATA[https://ads.test/viewable]]></Viewable>
              <NotViewable><![CDATA[https://ads.test/not-viewable]]></NotViewable>
              <ViewUndetermined><![CDATA[https://ads.test/undetermined]]></ViewUndetermined>
            </ViewableImpression>
            """,
        )

        val asked = assertNotNull(ad.viewableImpression)
        assertEquals("vi-1", asked.id)
        assertEquals(listOf("viewable"), asked.viewable.map { it.lastPathComponent })
        assertEquals(listOf("not-viewable"), asked.notViewable.map { it.lastPathComponent })
        assertEquals(listOf("undetermined"), asked.viewUndetermined.map { it.lastPathComponent })
    }

    /** An element that asked for nothing is nothing. */
    @Test
    fun anEmptyViewableImpressionIsDropped() {
        assertNull(ad(inLine = """<ViewableImpression id="vi-1"></ViewableImpression>""").viewableImpression)
    }

    /** The only outcome this player can honestly claim, and it goes out with the impression. */
    @Test
    fun viewUndeterminedIsReportedWhenNothingCanMeasure() {
        val ad = ad(
            inLine = """
            <ViewableImpression>
              <Viewable><![CDATA[https://ads.test/viewable]]></Viewable>
              <ViewUndetermined><![CDATA[https://ads.test/undetermined]]></ViewUndetermined>
            </ViewableImpression>
            """,
        )
        val fired = VastTrackingEngine(ad, duration = 20.0, measurementWillRun = false)
            .advance(VastTick(adTime = 0.0, duration = 20.0, rate = 1f, wallClock = 0.0))

        assertTrue(fired.any { it.kind == VastBeacon.Kind.ViewUndetermined }, "the response asked about viewability and heard nothing back")
        assertFalse(fired.any { it.url.lastPathComponent == "viewable" }, "a player that cannot measure viewability must not claim it")
    }

    /** With measurement in place the answer is the adapter's to give, not the engine's to pre-empt. */
    @Test
    fun viewUndeterminedIsSilentWhenSomethingWillMeasure() {
        val ad = ad(inLine = "<ViewableImpression><ViewUndetermined><![CDATA[https://ads.test/undetermined]]></ViewUndetermined></ViewableImpression>")
        val fired = VastTrackingEngine(ad, duration = 20.0, measurementWillRun = true)
            .advance(VastTick(adTime = 0.0, duration = 20.0, rate = 1f, wallClock = 0.0))

        assertFalse(fired.any { it.kind == VastBeacon.Kind.ViewUndetermined })
    }

    // MARK: - Icons

    /** From the live AdFox tag, which is where the empty `width=""` comes from — an empty attribute is not a zero. */
    @Test
    fun theIconFromARealTagIsParsed() {
        val ad = inLine(VastParser().parse(fixture("legacy-events")).entries.first())

        val icon = ad.icons.first()
        assertEquals("IconTopLeft", icon.program)
        assertEquals("left", icon.xPosition)
        assertEquals("top", icon.yPosition)
        assertEquals(0.0, icon.offset)
        assertNull(icon.width, """width="" is not a width of zero""")
        assertEquals("icon.png", icon.staticResource?.lastPathComponent)
        assertEquals("image/png", icon.staticResourceType)
        assertEquals("icon-landing", icon.clickThrough?.lastPathComponent)
    }

    /** An icon with no image is dropped: a host cannot draw an absent resource. */
    @Test
    fun anIconWithNothingToDrawIsDropped() {
        assertTrue(ad(creative = """<Icons><Icon program="AdChoices" xPosition="right" yPosition="top"/></Icons>""").icons.isEmpty())
    }

    @Test
    fun anIconDoesNotDisturbTheCreativeAroundIt() {
        val ad = ad(
            creative = """
            <Icons><Icon program="AdChoices">
              <StaticResource creativeType="image/png"><![CDATA[https://ads.test/i.png]]></StaticResource>
            </Icon></Icons>
            """,
        )
        assertEquals(1, ad.linear.mediaFiles.size)
        assertEquals(20.0, ad.linear.duration)
        assertEquals(1, ad.icons.size)
    }

    // MARK: - Custom clicks

    /** `<CustomClick>` is not `<ClickTracking>`: one accompanies a destination and the other opens nothing. */
    @Test
    fun customClicksAreKeptApartFromClickTracking() {
        val ad = ad(
            creative = """
            <VideoClicks>
              <ClickThrough><![CDATA[https://ads.test/landing]]></ClickThrough>
              <ClickTracking><![CDATA[https://ads.test/click]]></ClickTracking>
              <CustomClick><![CDATA[https://ads.test/custom]]></CustomClick>
            </VideoClicks>
            """,
        )

        assertEquals(listOf("click"), ad.linear.clickTracking.map { it.lastPathComponent })
        assertEquals(listOf("custom"), ad.linear.customClicks.map { it.lastPathComponent })
    }

    @Test
    fun reportingACustomClickFiresOnlyItsOwnUris() {
        val ad = ad(
            creative = """
            <VideoClicks>
              <ClickTracking><![CDATA[https://ads.test/click]]></ClickTracking>
              <CustomClick><![CDATA[https://ads.test/custom]]></CustomClick>
            </VideoClicks>
            """,
        )
        val engine = VastTrackingEngine(ad, duration = 20.0)
        engine.advance(VastTick(adTime = 0.0, duration = 20.0, rate = 1f, wallClock = 0.0))

        val fired = engine.reportCustomClick()
        assertEquals(listOf("custom"), fired.map { it.url.lastPathComponent })
        assertEquals(VastBeacon.Kind.CustomClick, fired.first().kind)
    }

    // MARK: - Fixtures

    private fun ad(inLine: String = "", creative: String = ""): VastAd = firstAd(
        """
        <VAST version="4.3"><Ad id="a"><InLine>
          <AdSystem>test</AdSystem>
          <Impression><![CDATA[https://ads.test/impression]]></Impression>
          $inLine
          <Creatives><Creative>
            $creative
            <Linear>
              <Duration>00:00:20</Duration>
              <MediaFiles>
                <MediaFile delivery="progressive" type="video/mp4"><![CDATA[https://ads.test/v.mp4]]></MediaFile>
              </MediaFiles>
            </Linear>
          </Creative></Creatives>
        </InLine></Ad></VAST>
        """.trimIndent(),
    )
}

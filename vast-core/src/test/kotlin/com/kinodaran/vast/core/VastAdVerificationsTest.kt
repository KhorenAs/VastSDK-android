package com.kinodaran.vast.core

import com.kinodaran.vast.core.VastVerification.NotExecutedReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `<AdVerifications>` moved between VAST 3 and VAST 4, and ad servers still send
 * both shapes. A host asks for `adVerifications` and should not have to know
 * which version answered.
 */
class VastAdVerificationsTest {

    /** The body of an InLine, with whatever verification markup a test supplies. */
    private fun inLine(version: String = "4.3", verifications: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <VAST version="$version">
          <Ad id="a1"><InLine>
            <AdSystem>test</AdSystem>
            <Impression><![CDATA[https://ads.test/impression]]></Impression>
            <Creatives><Creative><Linear>
              <Duration>00:00:30</Duration>
              <TrackingEvents>
                <Tracking event="start"><![CDATA[https://ads.test/start]]></Tracking>
                <Tracking event="complete"><![CDATA[https://ads.test/complete]]></Tracking>
              </TrackingEvents>
              <MediaFiles>
                <MediaFile delivery="progressive" type="video/mp4"><![CDATA[https://ads.test/v.mp4]]></MediaFile>
              </MediaFiles>
            </Linear></Creative></Creatives>
            $verifications
          </InLine></Ad>
        </VAST>
    """.trimIndent()

    // MARK: - VAST 4

    @Test
    fun parsesVast4AdVerifications() {
        val ad = firstAd(
            inLine(
                verifications = """
                <AdVerifications>
                  <Verification vendor="measure.com-omid">
                    <JavaScriptResource apiFramework="omid" browserOptional="true">
                      <![CDATA[https://measure.com/omid.js]]>
                    </JavaScriptResource>
                    <VerificationParameters><![CDATA[{"key":"abc123"}]]></VerificationParameters>
                    <TrackingEvents>
                      <Tracking event="verificationNotExecuted"><![CDATA[https://measure.com/not-executed]]></Tracking>
                    </TrackingEvents>
                  </Verification>
                </AdVerifications>
                """,
            ),
        )

        val verification = ad.adVerifications.single()
        assertEquals("measure.com-omid", verification.vendor)
        assertEquals("""{"key":"abc123"}""", verification.parameters)
        assertEquals(listOf("https://measure.com/not-executed"), verification.notExecutedTrackers)

        val resource = assertNotNull(verification.omidResource)
        assertEquals("https://measure.com/omid.js", resource.url)
        assertEquals(VastVerification.Resource.Kind.JAVA_SCRIPT, resource.kind)
        assertTrue(resource.browserOptional)
    }

    // MARK: - VAST 3

    /**
     * VAST 3 had no element for this, so vendors shipped the same content inside
     * `<Extension type="AdVerifications">`. It must normalise to the same model.
     */
    @Test
    fun parsesVast3ExtensionShapeIdentically() {
        val ad = firstAd(
            inLine(
                version = "3.0",
                verifications = """
                <Extensions>
                  <Extension type="AdVerifications">
                    <AdVerifications>
                      <Verification vendor="measure.com-omid">
                        <JavaScriptResource apiFramework="omid" browserOptional="true">
                          <![CDATA[https://measure.com/omid.js]]>
                        </JavaScriptResource>
                        <VerificationParameters><![CDATA[{"key":"abc123"}]]></VerificationParameters>
                        <TrackingEvents>
                          <Tracking event="verificationNotExecuted"><![CDATA[https://measure.com/not-executed]]></Tracking>
                        </TrackingEvents>
                      </Verification>
                    </AdVerifications>
                  </Extension>
                </Extensions>
                """,
            ),
        )

        val verification = ad.adVerifications.first()
        assertEquals("measure.com-omid", verification.vendor)
        assertEquals("https://measure.com/omid.js", verification.omidResource?.url)
        assertEquals("""{"key":"abc123"}""", verification.parameters)
        assertEquals(1, verification.notExecutedTrackers.size)
    }

    /** Some servers omit the inner `<AdVerifications>` and put `<Verification>` straight into the extension. */
    @Test
    fun parsesVast3ExtensionWithoutTheInnerWrapper() {
        val ad = firstAd(
            inLine(
                version = "3.0",
                verifications = """
                <Extensions>
                  <Extension type="adverifications">
                    <Verification vendor="terse.com">
                      <JavaScriptResource apiFramework="omid"><![CDATA[https://terse.com/v.js]]></JavaScriptResource>
                    </Verification>
                  </Extension>
                </Extensions>
                """,
            ),
        )

        assertEquals("terse.com", ad.adVerifications.first().vendor, "the type attribute's case is not ours to police")
    }

    /** Normalised, not duplicated: the same content must not also surface as a raw extension. */
    @Test
    fun verificationExtensionIsNotAlsoReportedRaw() {
        val ad = firstAd(
            inLine(
                version = "3.0",
                verifications = """
                <Extensions>
                  <Extension type="AdVerifications">
                    <AdVerifications>
                      <Verification vendor="measure.com">
                        <JavaScriptResource apiFramework="omid"><![CDATA[https://measure.com/v.js]]></JavaScriptResource>
                      </Verification>
                    </AdVerifications>
                  </Extension>
                  <Extension type="uiSettings"><UiHideable>1</UiHideable></Extension>
                </Extensions>
                """,
            ),
        )

        assertEquals(1, ad.adVerifications.size)
        assertEquals(listOf("uiSettings"), ad.extensions.mapNotNull { it.type }, "other extensions are untouched")
    }

    // MARK: - Scope

    /**
     * `<TrackingEvents>` lives under both `<Linear>` and `<Verification>`. Filing a
     * verification's tracker as a creative event loses it twice over.
     */
    @Test
    fun verificationTrackerDoesNotLeakIntoCreativeEvents() {
        val ad = firstAd(
            inLine(
                verifications = """
                <AdVerifications>
                  <Verification vendor="measure.com">
                    <JavaScriptResource apiFramework="omid"><![CDATA[https://measure.com/v.js]]></JavaScriptResource>
                    <TrackingEvents>
                      <Tracking event="verificationNotExecuted"><![CDATA[https://measure.com/not-executed]]></Tracking>
                    </TrackingEvents>
                  </Verification>
                </AdVerifications>
                """,
            ),
        )

        assertEquals(1, ad.linear.trackingEvents[VastTrackingEvent.START]?.size)
        assertEquals(1, ad.linear.trackingEvents[VastTrackingEvent.COMPLETE]?.size)
        assertEquals(2, ad.linear.trackingEvents.size, "no third event arrived from the verification")
        assertEquals(1, ad.adVerifications.first().notExecutedTrackers.size)
    }

    // MARK: - Resources

    /** An executable resource cannot run here, but the vendor still expects to hear why. */
    @Test
    fun executableOnlyVerificationIsKeptButUnusable() {
        val ad = firstAd(
            inLine(
                verifications = """
                <AdVerifications>
                  <Verification vendor="native.com">
                    <ExecutableResource apiFramework="native" type="binary">
                      <![CDATA[https://native.com/lib.bin]]>
                    </ExecutableResource>
                    <TrackingEvents>
                      <Tracking event="verificationNotExecuted"><![CDATA[https://native.com/not-executed]]></Tracking>
                    </TrackingEvents>
                  </Verification>
                </AdVerifications>
                """,
            ),
        )

        val verification = ad.adVerifications.first()
        assertNull(verification.omidResource, "reason 1, not reason 3")
        assertEquals(VastVerification.Resource.Kind.EXECUTABLE, verification.resources.first().kind)
        assertEquals("binary", verification.resources.first().type)
        assertEquals(1, verification.notExecutedTrackers.size)
    }

    /** A JavaScript resource for someone else's framework is not an OMID resource. */
    @Test
    fun nonOmidJavaScriptIsNotOfferedAsOmid() {
        val ad = firstAd(
            inLine(
                verifications = """
                <AdVerifications>
                  <Verification vendor="other.com">
                    <JavaScriptResource apiFramework="spatial"><![CDATA[https://other.com/v.js]]></JavaScriptResource>
                  </Verification>
                </AdVerifications>
                """,
            ),
        )

        val verification = ad.adVerifications.first()
        assertNull(verification.omidResource)
        assertEquals(1, verification.resources.size)
    }

    /** `browserOptional` defaults to false (§3.16), and `omid` arrives in mixed case often enough. */
    @Test
    fun defaultsAndCaseInsensitiveFramework() {
        val ad = firstAd(
            inLine(
                verifications = """
                <AdVerifications>
                  <Verification vendor="mixed.com">
                    <JavaScriptResource apiFramework="OMID"><![CDATA[https://mixed.com/v.js]]></JavaScriptResource>
                  </Verification>
                </AdVerifications>
                """,
            ),
        )

        assertFalse(assertNotNull(ad.adVerifications.first().omidResource).browserOptional)
    }

    /** A `<Verification>` asking for nothing is not something to report on. */
    @Test
    fun verificationWithoutResourcesIsDropped() {
        val ad = firstAd(inLine(verifications = """<AdVerifications><Verification vendor="empty.com"></Verification></AdVerifications>"""))
        assertTrue(ad.adVerifications.isEmpty())
    }

    /** Several vendors on one ad is the ordinary case, not an edge case. */
    @Test
    fun multipleVendorsAreAllKept() {
        val ad = firstAd(
            inLine(
                verifications = """
                <AdVerifications>
                  <Verification vendor="one.com">
                    <JavaScriptResource apiFramework="omid"><![CDATA[https://one.com/v.js]]></JavaScriptResource>
                  </Verification>
                  <Verification vendor="two.com">
                    <JavaScriptResource apiFramework="omid"><![CDATA[https://two.com/v.js]]></JavaScriptResource>
                  </Verification>
                </AdVerifications>
                """,
            ),
        )

        assertEquals(listOf("one.com", "two.com"), ad.adVerifications.map { it.vendor })
    }

    @Test
    fun adWithoutVerificationsHasAnEmptyList() {
        assertTrue(firstAd(inLine(verifications = "")).adVerifications.isEmpty())
    }

    // MARK: - Reporting

    private fun beacons(ad: VastAd, measurementWillRun: Boolean = false): List<VastBeacon> =
        VastTrackingEngine(ad, measurementWillRun = measurementWillRun)
            .advance(VastTick(adTime = 0.0, duration = 30.0, rate = 1f, wallClock = 0.0))

    private fun reasons(beacons: List<VastBeacon>) =
        beacons.mapNotNull { (it.kind as? VastBeacon.Kind.VerificationNotExecuted)?.reason }

    /** Reason 3: there was a usable resource and nothing was asked to run it. */
    @Test
    fun usableResourceWithNoMeasurementReportsNotExecuted() {
        val ad = firstAd(
            inLine(
                verifications = """
                <AdVerifications>
                  <Verification vendor="measure.com">
                    <JavaScriptResource apiFramework="omid"><![CDATA[https://measure.com/v.js]]></JavaScriptResource>
                    <TrackingEvents>
                      <Tracking event="verificationNotExecuted"><![CDATA[https://measure.com/ne?r=[REASON]]]></Tracking>
                    </TrackingEvents>
                  </Verification>
                </AdVerifications>
                """,
            ),
        )

        assertEquals(listOf(NotExecutedReason.NOT_EXECUTED), reasons(beacons(ad)))
    }

    /** Reason 1: nothing here could ever have run, measurement or not. */
    @Test
    fun executableOnlyResourceReportsResourceNotSupported() {
        val ad = firstAd(
            inLine(
                verifications = """
                <AdVerifications>
                  <Verification vendor="native.com">
                    <ExecutableResource apiFramework="native"><![CDATA[https://native.com/lib.bin]]></ExecutableResource>
                    <TrackingEvents>
                      <Tracking event="verificationNotExecuted"><![CDATA[https://native.com/ne]]></Tracking>
                    </TrackingEvents>
                  </Verification>
                </AdVerifications>
                """,
            ),
        )

        assertEquals(listOf(NotExecutedReason.RESOURCE_NOT_SUPPORTED), reasons(beacons(ad)))
    }

    /** A host that does run them owes nothing — reporting anyway would contradict the vendor's own data. */
    @Test
    fun nothingIsReportedWhenMeasurementWillRun() {
        val ad = firstAd(
            inLine(
                verifications = """
                <AdVerifications>
                  <Verification vendor="measure.com">
                    <JavaScriptResource apiFramework="omid"><![CDATA[https://measure.com/v.js]]></JavaScriptResource>
                    <TrackingEvents>
                      <Tracking event="verificationNotExecuted"><![CDATA[https://measure.com/ne]]></Tracking>
                    </TrackingEvents>
                  </Verification>
                </AdVerifications>
                """,
            ),
        )

        assertTrue(reasons(beacons(ad, measurementWillRun = true)).isEmpty())
    }

    /** `[REASON]` is what tells the vendor *why*; sent literally it says nothing. */
    @Test
    fun reasonMacroExpandsToTheReportedCode() {
        val expanded = VastMacroExpander().expandUrl(
            "https://measure.com/ne?r=%5BREASON%5D",
            VastMacroExpander.Context(verificationNotExecutedReason = NotExecutedReason.NOT_EXECUTED),
        )
        assertEquals("https://measure.com/ne?r=3", expanded)
    }

    @Test
    fun reasonMacroIsUnknownOnOtherBeacons() {
        assertEquals("https://ads.test/e?r=-1", VastMacroExpander().expandUrl("https://ads.test/e?r=%5BREASON%5D", VastMacroExpander.Context()))
    }

    @Test
    fun verificationVendorsMacroListsTheVendors() {
        val expanded = VastMacroExpander().expandUrl(
            "https://ads.test/t?v=%5BVERIFICATIONVENDORS%5D",
            VastMacroExpander.Context(verificationVendors = listOf("one.com-omid", "two.com")),
        )
        assertEquals("https://ads.test/t?v=one.com-omid%2Ctwo.com", expanded)
    }

    @Test
    fun verificationVendorsMacroIsUnknownWhenNoneWereNamed() {
        assertEquals("https://ads.test/t?v=-1", VastMacroExpander().expandUrl("https://ads.test/t?v=%5BVERIFICATIONVENDORS%5D", VastMacroExpander.Context()))
    }

    @Test
    fun omidPartnerMacro() {
        val url = "https://ads.test/t?p=%5BOMIDPARTNER%5D"
        assertEquals("https://ads.test/t?p=Kinodaran%2F1.0", VastMacroExpander().expandUrl(url, VastMacroExpander.Context(omidPartner = "Kinodaran/1.0")))
        assertEquals("https://ads.test/t?p=-1", VastMacroExpander().expandUrl(url, VastMacroExpander.Context()))
    }
}

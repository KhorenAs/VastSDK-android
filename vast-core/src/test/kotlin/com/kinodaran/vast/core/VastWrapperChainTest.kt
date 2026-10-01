package com.kinodaran.vast.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A loader backed by a map, so the whole redirection chain — depth limits,
 * tracker accumulation, error reporting — runs with no network.
 */
internal class FixtureLoader(
    private val documents: Map<String, String>,
    /** URLs that should behave as unreachable. */
    private val unreachable: Set<String> = emptySet(),
) : VastResourceLoader {
    override suspend fun loadVast(url: String, timeoutSeconds: Double): String {
        if (url in unreachable) throw VastException(VastError.WRAPPER_TIMEOUT)
        return documents[url] ?: throw VastException(VastError.WRAPPER_TIMEOUT)
    }
}

class VastWrapperChainTest {

    private val base = "https://ads.test/tag.xml"

    // MARK: - Document builders

    private fun wrapper(
        to: String,
        error: String,
        impression: String,
        clickThrough: String? = null,
        follow: Boolean = true,
        allowMultipleAds: Boolean = false,
        adId: String = "w",
        verificationVendor: String? = null,
    ): String {
        val click = clickThrough?.let { "<VideoClicks><ClickThrough><![CDATA[$it]]></ClickThrough></VideoClicks>" } ?: ""
        val verifications = verificationVendor?.let {
            """
            <AdVerifications><Verification vendor="$it">
              <JavaScriptResource apiFramework="omid"><![CDATA[https://$it/omid.js]]></JavaScriptResource>
            </Verification></AdVerifications>
            """
        } ?: ""
        return """
            <VAST version="4.3"><Ad id="$adId"><Wrapper followAdditionalWrappers="$follow" allowMultipleAds="$allowMultipleAds">
              <VASTAdTagURI><![CDATA[$to]]></VASTAdTagURI>
              <Error><![CDATA[$error]]></Error>
              <Impression><![CDATA[$impression]]></Impression>
              $verifications
              <Creatives><Creative><Linear>
                <TrackingEvents><Tracking event="start"><![CDATA[https://ads.test/w-start]]></Tracking></TrackingEvents>
                $click
              </Linear></Creative></Creatives>
            </Wrapper></Ad></VAST>
        """.trimIndent()
    }

    private fun inLine(clickThrough: String? = null): String {
        val click = clickThrough?.let { "<VideoClicks><ClickThrough><![CDATA[$it]]></ClickThrough></VideoClicks>" } ?: ""
        return """
            <VAST version="4.3"><Ad id="inline"><InLine>
              <AdSystem>test</AdSystem>
              <Error><![CDATA[https://ads.test/inline-error]]></Error>
              <Impression><![CDATA[https://ads.test/inline-impression]]></Impression>
              <Creatives><Creative><Linear>
                <Duration>00:00:20</Duration>
                <TrackingEvents><Tracking event="start"><![CDATA[https://ads.test/inline-start]]></Tracking></TrackingEvents>
                $click
                <MediaFiles><MediaFile delivery="progressive" type="video/mp4"><![CDATA[https://ads.test/v.mp4]]></MediaFile></MediaFiles>
              </Linear></Creative></Creatives>
            </InLine></Ad></VAST>
        """.trimIndent()
    }

    private fun resolve(documents: Map<String, String>, unreachable: Set<String> = emptySet()) =
        runBlocking { VastTagResolver(FixtureLoader(documents, unreachable)).resolveTag(base) }

    private fun failure(documents: Map<String, String>, unreachable: Set<String> = emptySet()) =
        assertFailsWith<VastTagResolver.Failure> { resolve(documents, unreachable) }

    // MARK: - Happy path

    @Test
    fun resolvesThroughTwoWrappersToInLine() {
        val resolution = resolve(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/w2.xml", "https://ads.test/e1", "https://ads.test/i1"),
                "https://ads.test/w2.xml" to wrapper("https://ads.test/inline.xml", "https://ads.test/e2", "https://ads.test/i2"),
                "https://ads.test/inline.xml" to inLine(),
            ),
        )

        assertEquals("inline", resolution.ads.first().id)
        assertEquals(2, resolution.chain.depth)
    }

    /** §2.3.5: trackers from every Wrapper in the chain accompany the InLine's own. */
    @Test
    fun trackersFromEveryWrapperAreAccumulated() {
        val ad = resolve(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/w2.xml", "https://ads.test/e1", "https://ads.test/i1"),
                "https://ads.test/w2.xml" to wrapper("https://ads.test/inline.xml", "https://ads.test/e2", "https://ads.test/i2"),
                "https://ads.test/inline.xml" to inLine(),
            ),
        ).ads.first()

        assertEquals(3, ad.impressions.size, "inline + two wrappers")
        assertEquals(3, ad.errors.size)
        assertEquals(3, ad.linear.trackingEvents[VastTrackingEvent.START]?.size)
    }

    /** Flattening the chain must not lose which intermediaries served the ad. */
    @Test
    fun wrapperAdIdsAreCollectedOutermostFirst() {
        val ad = resolve(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/w2.xml", "https://ads.test/e1", "https://ads.test/i1", adId = "dsp-outer"),
                "https://ads.test/w2.xml" to wrapper("https://ads.test/inline.xml", "https://ads.test/e2", "https://ads.test/i2", adId = "ssp-inner"),
                "https://ads.test/inline.xml" to inLine(),
            ),
        ).ads.first()

        assertEquals(listOf("dsp-outer", "ssp-inner"), ad.wrapperAdIds)
        assertEquals("inline", ad.id, "the ad's own id is not one of its wrappers")
    }

    /** A direct response crossed no wrapper, so the list is empty rather than carrying the ad's own id. */
    @Test
    fun directInLineHasNoWrapperAdIds() {
        assertEquals(emptyList(), resolve(mapOf("https://ads.test/tag.xml" to inLine())).ads.first().wrapperAdIds)
    }

    // MARK: - AdVerifications

    /** Measurement vendors are injected by intermediaries far more often than by the advertiser. */
    @Test
    fun verificationsFromWrappersReachTheInLineAd() {
        val ad = resolve(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/inline.xml", "https://ads.test/e1", "https://ads.test/i1", verificationVendor = "dsp-vendor"),
                "https://ads.test/inline.xml" to inLine(),
            ),
        ).ads.first()

        assertEquals(listOf("dsp-vendor"), ad.adVerifications.map { it.vendor })
        assertNotNull(ad.adVerifications.first().omidResource)
    }

    /** A duplicate verification is a second measurement session for a vendor that asked for one. */
    @Test
    fun theSameVendorInjectedTwiceIsKeptOnce() {
        val ad = resolve(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/w2.xml", "https://ads.test/e1", "https://ads.test/i1", adId = "w1", verificationVendor = "shared-vendor"),
                "https://ads.test/w2.xml" to wrapper("https://ads.test/inline.xml", "https://ads.test/e2", "https://ads.test/i2", adId = "w2", verificationVendor = "shared-vendor"),
                "https://ads.test/inline.xml" to inLine(),
            ),
        ).ads.first()

        assertEquals(1, ad.adVerifications.size)
    }

    @Test
    fun differentVendorsAccumulateAcrossTheChain() {
        val ad = resolve(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/w2.xml", "https://ads.test/e1", "https://ads.test/i1", adId = "w1", verificationVendor = "vendor-a"),
                "https://ads.test/w2.xml" to wrapper("https://ads.test/inline.xml", "https://ads.test/e2", "https://ads.test/i2", adId = "w2", verificationVendor = "vendor-b"),
                "https://ads.test/inline.xml" to inLine(),
            ),
        ).ads.first()

        assertEquals(listOf("vendor-a", "vendor-b"), ad.adVerifications.map { it.vendor })
    }

    // MARK: - Linearity

    /** A filled slot the player cannot use is not "no fill": §2.3.6 gives it 201. */
    @Test
    fun nonLinearOnlyResponseIsReportedAsUnexpectedLinearity() {
        val nonLinearOnly = """
            <VAST version="4.3"><Ad id="overlay-1"><InLine>
              <AdSystem>test</AdSystem>
              <Error><![CDATA[https://ads.test/overlay-error]]></Error>
              <Impression><![CDATA[https://ads.test/overlay-impression]]></Impression>
              <Creatives><Creative><NonLinearAds>
                <NonLinear width="300" height="50">
                  <StaticResource creativeType="image/png"><![CDATA[https://ads.test/banner.png]]></StaticResource>
                </NonLinear>
              </NonLinearAds></Creative></Creatives>
            </InLine></Ad></VAST>
        """.trimIndent()

        val failure = failure(mapOf("https://ads.test/tag.xml" to nonLinearOnly))
        assertEquals(VastError.UNEXPECTED_LINEARITY, failure.error)
        assertEquals(201, VastError.UNEXPECTED_LINEARITY.code)
        assertEquals(listOf("https://ads.test/overlay-error"), failure.beacons.map { it.url }, "the server hears about it on the URI it supplied")
    }

    /** An empty response is still 303: nothing was returned to misreport. */
    @Test
    fun trulyEmptyResponseIsStillNoFill() {
        val empty = """<VAST version="4.3"><Error><![CDATA[https://ads.test/no-ad]]></Error></VAST>"""
        assertEquals(VastError.NO_VAST_RESPONSE_AFTER_WRAPPERS, failure(mapOf("https://ads.test/tag.xml" to empty)).error)
    }

    /** A relative `VASTAdTagURI` is what real responses send. */
    @Test
    fun relativeTagUriIsResolvedAgainstTheFetchLocation() {
        val resolution = resolve(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("next.xml", "https://ads.test/e1", "https://ads.test/i1"),
                "https://ads.test/next.xml" to inLine(),
            ),
        )
        assertEquals("inline", resolution.ads.first().id)
    }

    /** RFC 3986 §5.2.3: a base with an empty path still gets the slash before the reference. */
    @Test
    fun relativeTagUriAgainstAHostWithNoPath() {
        assertEquals("https://ads.test/next.xml", VastWrapperChain.resolve("next.xml", "https://ads.test"))
        assertEquals("https://other.test/a.xml", VastWrapperChain.resolve("https://other.test/a.xml", "https://ads.test/x/"))
    }

    // MARK: - ClickThrough precedence

    /** §2.3.5.2: the InLine's ClickThrough must be favoured over the Wrappers'. */
    @Test
    fun inLineClickThroughWinsOverWrappers() {
        val ad = resolve(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/inline.xml", "https://ads.test/e1", "https://ads.test/i1", clickThrough = "https://ads.test/wrapper-landing"),
                "https://ads.test/inline.xml" to inLine(clickThrough = "https://ads.test/inline-landing"),
            ),
        ).ads.first()

        assertEquals("https://ads.test/inline-landing", ad.linear.clickThrough)
    }

    /** "the ClickThrough element closest to the InLine response must be favored" */
    @Test
    fun deepestWrapperClickThroughWinsWhenInLineHasNone() {
        val ad = resolve(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/w2.xml", "https://ads.test/e1", "https://ads.test/i1", clickThrough = "https://ads.test/outer"),
                "https://ads.test/w2.xml" to wrapper("https://ads.test/inline.xml", "https://ads.test/e2", "https://ads.test/i2", clickThrough = "https://ads.test/inner"),
                "https://ads.test/inline.xml" to inLine(),
            ),
        ).ads.first()

        assertEquals("https://ads.test/inner", ad.linear.clickThrough)
    }

    // MARK: - Limits and failures

    /** §3.19.1 requires accepting five wrappers; the sixth is error 302. */
    @Test
    fun wrapperLimitIsReportedAs302() {
        val documents = mutableMapOf<String, String>()
        for (index in 0..8) {
            documents["https://ads.test/w$index.xml"] = wrapper("https://ads.test/w${index + 1}.xml", "https://ads.test/e$index", "https://ads.test/i$index")
        }
        documents["https://ads.test/tag.xml"] = documents.getValue("https://ads.test/w0.xml")

        val failure = failure(documents)
        assertEquals(VastError.WRAPPER_LIMIT_REACHED, failure.error)
        assertEquals(302, failure.error.code)
        assertEquals(6, failure.beacons.size, "every wrapper traversed is told")
    }

    /** §2.3.5.1: "Error codes should be sent for all wrappers in the chain." */
    @Test
    fun unreachableRedirectFiresEveryWrapperError() {
        val failure = failure(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/w2.xml", "https://ads.test/e1", "https://ads.test/i1"),
                "https://ads.test/w2.xml" to wrapper("https://ads.test/dead.xml", "https://ads.test/e2", "https://ads.test/i2"),
            ),
            unreachable = setOf("https://ads.test/dead.xml"),
        )
        assertEquals(VastError.WRAPPER_TIMEOUT, failure.error)
        assertEquals(301, failure.error.code)
        assertEquals(setOf("https://ads.test/e1", "https://ads.test/e2"), failure.beacons.map { it.url }.toSet())
    }

    /** `followAdditionalWrappers="false"` means a Wrapper response must be ignored. */
    @Test
    fun followAdditionalWrappersFalseStopsTheChain() {
        val failure = failure(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/w2.xml", "https://ads.test/e1", "https://ads.test/i1"),
                "https://ads.test/w2.xml" to wrapper("https://ads.test/w3.xml", "https://ads.test/e2", "https://ads.test/i2", follow = false),
                "https://ads.test/w3.xml" to inLine(),
            ),
        )
        assertEquals(VastError.NO_VAST_RESPONSE_AFTER_WRAPPERS, failure.error)
    }

    /** §2.3.6.4: an empty response behind a wrapper is a 303, and the wrapper's own error URI still fires. */
    @Test
    fun emptyResponseBehindWrapperIsReportedAs303() {
        val failure = failure(
            mapOf(
                "https://ads.test/tag.xml" to wrapper("https://ads.test/empty.xml", "https://ads.test/e1", "https://ads.test/i1"),
                "https://ads.test/empty.xml" to """<VAST version="4.3"><Error><![CDATA[https://ads.test/no-ad]]></Error></VAST>""",
            ),
        )
        assertEquals(VastError.NO_VAST_RESPONSE_AFTER_WRAPPERS, failure.error)
        assertEquals(303, failure.error.code)
        assertTrue(failure.beacons.any { it.url == "https://ads.test/e1" })
    }

    /** §3.3.1: a pod behind a wrapper gets the wrapper's trackers on every member. */
    @Test
    fun podBehindWrapperGivesEveryAdTheWrapperTrackers() {
        val resolution = resolve(
            mapOf(
                // §3.19: a wrapper must permit multiple ads for a pod to come through
                // it at all. Without this the response is reduced to one ad.
                "https://ads.test/tag.xml" to wrapper("https://ads.test/pod.xml", "https://ads.test/e1", "https://ads.test/wrapper-impression", allowMultipleAds = true),
                "https://ads.test/pod.xml" to podResponse(3),
            ),
        )

        assertEquals(3, resolution.ads.size)
        for (ad in resolution.ads) {
            assertTrue("https://ads.test/wrapper-impression" in ad.impressions, "${ad.id} is missing the wrapper impression")
        }
    }

    // MARK: - allowMultipleAds (§3.19)

    private fun podResponse(count: Int, includeStandAlone: Boolean = false): String {
        val sequenced = (1..count).joinToString("") { index ->
            """
            <Ad id="pod-$index" sequence="$index"><InLine>
              <AdSystem>test</AdSystem>
              <Impression><![CDATA[https://ads.test/i-pod-$index]]></Impression>
              <Creatives><Creative><Linear><Duration>00:00:10</Duration>
                <MediaFiles><MediaFile delivery="progressive" type="video/mp4"><![CDATA[https://ads.test/v.mp4]]></MediaFile></MediaFiles>
              </Linear></Creative></Creatives>
            </InLine></Ad>
            """
        }
        val spare = if (!includeStandAlone) "" else """
            <Ad id="spare"><InLine>
              <AdSystem>test</AdSystem>
              <Impression><![CDATA[https://ads.test/i-spare]]></Impression>
              <Creatives><Creative><Linear><Duration>00:00:10</Duration>
                <MediaFiles><MediaFile delivery="progressive" type="video/mp4"><![CDATA[https://ads.test/v.mp4]]></MediaFile></MediaFiles>
              </Linear></Creative></Creatives>
            </InLine></Ad>
        """
        return """<VAST version="4.3">$sequenced$spare</VAST>"""
    }

    private fun bareWrapper(to: String, allowMultiple: Boolean?): String {
        val attribute = allowMultiple?.let { """ allowMultipleAds="$it"""" } ?: ""
        return """
            <VAST version="4.3"><Ad id="w"><Wrapper$attribute>
              <VASTAdTagURI><![CDATA[$to]]></VASTAdTagURI>
              <Error><![CDATA[https://ads.test/e]]></Error>
              <Impression><![CDATA[https://ads.test/i]]></Impression>
            </Wrapper></Ad></VAST>
        """.trimIndent()
    }

    @Test
    fun wrapperPermittingMultipleAdsYieldsTheWholePod() {
        val resolution = resolve(
            mapOf(
                "https://ads.test/tag.xml" to bareWrapper("https://ads.test/pod.xml", allowMultiple = true),
                "https://ads.test/pod.xml" to podResponse(3),
            ),
        )
        assertEquals(3, resolution.ads.size)
    }

    /** Omitted means `false` (§3.19), so the same response yields one ad. */
    @Test
    fun wrapperWithoutTheAttributeAllowsOnlyOneAd() {
        val resolution = resolve(
            mapOf(
                "https://ads.test/tag.xml" to bareWrapper("https://ads.test/pod.xml", allowMultiple = null),
                "https://ads.test/pod.xml" to podResponse(3),
            ),
        )
        assertEquals(1, resolution.ads.size, "the default is false, not true")
    }

    /** "only the first stand-alone Ad (with no sequence values) ... is allowed". */
    @Test
    fun standAloneAdIsPreferredWhenMultipleAdsAreNotAllowed() {
        val resolution = resolve(
            mapOf(
                "https://ads.test/tag.xml" to bareWrapper("https://ads.test/pod.xml", allowMultiple = false),
                "https://ads.test/pod.xml" to podResponse(2, includeStandAlone = true),
            ),
        )
        assertEquals(listOf("spare"), resolution.ads.map { it.id })
    }

    /** A direct response has no wrapper imposing the constraint. */
    @Test
    fun directResponseIsNotConstrained() {
        assertEquals(3, resolve(mapOf("https://ads.test/tag.xml" to podResponse(3))).ads.size, "there is no wrapper at depth zero")
    }
}

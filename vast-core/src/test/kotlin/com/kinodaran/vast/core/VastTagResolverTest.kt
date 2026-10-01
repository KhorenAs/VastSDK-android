package com.kinodaran.vast.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Both ways in have to behave the same. Starting the tail of the chain over in
 * `resolveXml` would quietly lose everything the document it was handed had
 * collected — and a host holding XML rather than a URL would have no way to know.
 */
class VastTagResolverTest {

    private val String.host: String? get() = URI(this).host

    // MARK: - The chain carries on

    /** The same wrapper, down each path, has to yield the same trackers. */
    @Test
    fun handingInAWrapperCollectsAsMuchAsFetchingIt() = runBlocking {
        val resolver = resolver()

        val fromFetch = resolver.resolveTag(OUTER_URL).ads.first()
        val fromHandIn = resolver.resolveXml(WRAPPER, OUTER_URL).ads.first()

        assertEquals(fromFetch.impressions.map { it.host }.toSet(), fromHandIn.impressions.map { it.host }.toSet(), "the wrapper's own <Impression> was dropped")
        assertEquals(fromFetch.errors.map { it.host }.toSet(), fromHandIn.errors.map { it.host }.toSet(), "the wrapper's own <Error> was dropped, so a failure would go unreported")
        assertEquals(fromFetch.wrapperAdIds, fromHandIn.wrapperAdIds, "the intermediary that served the ad was forgotten")
    }

    @Test
    fun theOuterWrapperContributesItsOwnTrackers() = runBlocking {
        val ad = resolver().resolveXml(WRAPPER, OUTER_URL).ads.first()

        assertTrue(ad.impressions.any { it.host == "outer.example" })
        assertTrue(ad.errors.any { it.host == "outer.example" })
        assertEquals(listOf("outer"), ad.wrapperAdIds)
    }

    /** Starting the tail over would make the real limit twice `maxDepth`. */
    @Test
    fun depthIsNotRestartedForTheTail() = runBlocking {
        assertEquals(1, resolver().resolveXml(WRAPPER, OUTER_URL).chain.depth, "the hop this document itself made was forgotten")
    }

    // MARK: - What the chain must not lose

    /** These are the fields a reporting pipeline needs, so losing them is invisible until a report is empty. */
    @Test
    fun theChainCarriesTheInLinesOwnMetadata() = runBlocking {
        val ad = resolver(RICH_IN_LINE).resolveTag(OUTER_URL).ads.first()

        assertEquals("serving-1", ad.adServingId)
        assertEquals("CNPA0484000H", ad.universalAdIds.first().value)
        assertEquals("Kinodaran", ad.advertiser)
        assertEquals(1, ad.icons.size)
        assertEquals(listOf("custom"), ad.linear.customClicks.map { it.lastPathComponent })
    }

    /** A Wrapper may ask about viewability on its own account (§3.6), and both sets of URIs survive. */
    @Test
    fun bothTheWrapperAndTheInLineAreHeardAboutViewability() = runBlocking {
        val asked = assertNotNull(resolver(RICH_IN_LINE).resolveTag(OUTER_URL).ads.first().viewableImpression)
        assertEquals(setOf("inner.example", "outer.example"), asked.viewUndetermined.map { it.host }.toSet())
    }

    // MARK: - Cancellation

    /** Leaving is not a failed redirect: a cancelled resolution reports nothing to anyone. */
    @Test
    fun cancellationIsNotReportedAsATimeout() {
        val cancelling = object : VastResourceLoader {
            override suspend fun loadVast(url: String, timeoutSeconds: Double): String = throw CancellationException("left")
        }
        assertFailsWith<CancellationException> { runBlocking { VastTagResolver(cancelling).resolveTag(OUTER_URL) } }
    }

    // MARK: - Fixtures

    private fun resolver(inLine: String? = null) =
        VastTagResolver(FixtureLoader(mapOf(OUTER_URL to WRAPPER, INNER_URL to (inLine ?: IN_LINE))))

    private companion object {
        const val OUTER_URL = "https://outer.example/vast.xml"
        const val INNER_URL = "https://inner.example/vast.xml"

        val WRAPPER = """
            <VAST version="4.0"><Ad id="outer"><Wrapper>
              <AdSystem>outer</AdSystem>
              <Impression><![CDATA[https://outer.example/impression]]></Impression>
              <Error><![CDATA[https://outer.example/error]]></Error>
              <VASTAdTagURI><![CDATA[https://inner.example/vast.xml]]></VASTAdTagURI>
              <ViewableImpression>
                <ViewUndetermined><![CDATA[https://outer.example/undetermined]]></ViewUndetermined>
              </ViewableImpression>
              <Creatives><Creative><Linear><TrackingEvents>
                <Tracking event="start"><![CDATA[https://outer.example/start]]></Tracking>
              </TrackingEvents></Linear></Creative></Creatives>
            </Wrapper></Ad></VAST>
        """.trimIndent()

        val IN_LINE = """
            <VAST version="4.0"><Ad id="inner"><InLine>
              <AdSystem>inner</AdSystem>
              <Impression><![CDATA[https://inner.example/impression]]></Impression>
              <Creatives><Creative><Linear>
                <Duration>00:00:15</Duration>
                <MediaFiles>
                  <MediaFile delivery="progressive" type="video/mp4"><![CDATA[https://inner.example/a.mp4]]></MediaFile>
                </MediaFiles>
              </Linear></Creative></Creatives>
            </InLine></Ad></VAST>
        """.trimIndent()

        /** An InLine carrying the VAST 4 fields a reporting pipeline reads. */
        val RICH_IN_LINE = """
            <VAST version="4.3"><Ad id="inner"><InLine>
              <AdSystem>inner</AdSystem>
              <AdServingId>serving-1</AdServingId>
              <Advertiser>Kinodaran</Advertiser>
              <Impression><![CDATA[https://inner.example/impression]]></Impression>
              <ViewableImpression>
                <ViewUndetermined><![CDATA[https://inner.example/undetermined]]></ViewUndetermined>
              </ViewableImpression>
              <Creatives><Creative>
                <UniversalAdId idRegistry="Ad-ID">CNPA0484000H</UniversalAdId>
                <Icons><Icon program="AdChoices">
                  <StaticResource creativeType="image/png"><![CDATA[https://inner.example/i.png]]></StaticResource>
                </Icon></Icons>
                <Linear>
                  <Duration>00:00:15</Duration>
                  <VideoClicks>
                    <CustomClick><![CDATA[https://inner.example/custom]]></CustomClick>
                  </VideoClicks>
                  <MediaFiles>
                    <MediaFile delivery="progressive" type="video/mp4"><![CDATA[https://inner.example/a.mp4]]></MediaFile>
                  </MediaFiles>
                </Linear>
              </Creative></Creatives>
            </InLine></Ad></VAST>
        """.trimIndent()
    }
}

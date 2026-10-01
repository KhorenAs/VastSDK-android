package com.kinodaran.vast.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour learned from a live ad server rather than from the specification.
 * Every case here corresponds to something a real tag did that spec-shaped
 * fixtures do not.
 */
class VastRealWorldTagTest {

    private fun adFoxAd(): VastAd = inLine(VastParser().parse(fixture("legacy-events")).entries.first())

    /** The tag sends VAST 2.0/3.0 names. Dropping them loses tracking the server is waiting for. */
    @Test
    fun legacyEventNamesAreParsed() {
        val parsed = adFoxAd().linear.trackingEvents.keys

        for (legacy in listOf(
            VastTrackingEvent.FULLSCREEN, VastTrackingEvent.EXPAND, VastTrackingEvent.COLLAPSE,
            VastTrackingEvent.ACCEPT_INVITATION, VastTrackingEvent.CLOSE,
        )) {
            assertTrue(legacy in parsed, "${legacy.vastName} was dropped")
        }
        assertEquals(16, parsed.size, "16 of the tag's 17 events are well-formed")
    }

    /** `progress` requires an offset (§3.14.1). This server sends `offset=""`, so it is discarded rather than guessed at. */
    @Test
    fun progressEventWithEmptyOffsetIsDiscarded() {
        assertTrue(adFoxAd().linear.progressEvents.isEmpty())
    }

    /** §3.14.1 says `playerExpand` replaces `fullscreen`, but servers still send the old name. */
    @Test
    fun firingPlayerExpandAlsoFiresLegacyFullscreenUri() {
        val engine = VastTrackingEngine(adFoxAd())
        engine.advance(VastTick(adTime = 0.0, duration = 24.0, rate = 1f, wallClock = 0.0))
        engine.advance(VastTick(adTime = 1.0, duration = 24.0, rate = 1f, wallClock = 1.0))

        val fired = engine.report(VastTrackingEvent.PLAYER_EXPAND).map { it.url.lastPathComponent }
        assertEquals(setOf("fullscreen", "expand"), fired.toSet())
    }

    @Test
    fun skipOffsetAndDurationFromLiveTag() {
        val ad = adFoxAd()
        assertEquals(24.0, ad.linear.duration)
        assertEquals(VastAd.SkipOffset.Time(15.0), ad.linear.skipOffset)
        assertTrue(ad.isSkippable)
    }

    /** `<Icons>` must not disturb the Linear creative around it. */
    @Test
    fun iconsElementDoesNotCorruptSurroundingCreative() {
        val ad = adFoxAd()
        assertEquals(1, ad.linear.mediaFiles.size)
        assertEquals(1, ad.linear.clickTracking.size)
        assertNotNull(ad.linear.clickThrough)
    }

    /** The vendor extension is handed over verbatim; the SDK assigns it no meaning. */
    @Test
    fun vendorExtensionIsExposedRaw() {
        val uiSettings = adFoxAd().extensions.first { it.type == "uiSettings" }
        assertTrue(uiSettings.xml.contains("UiHideable"))
        assertEquals("1", uiSettings.value("UiHideable"), "read without scanning the string by hand")
    }
}

/**
 * The tag KinodaranAds' own ad server returns, which is the one this SDK was
 * written to read. Here for the day the server changes its VAST output and the
 * SDK stops understanding it without anybody noticing, because the demo would
 * simply show no ad.
 */
class VastKinodaranAdServerTagTest {

    private val document = VastParser().parse(fixture("kinodaran-adserver"))
    private val ad = inLine(document.entries.first())

    /** 3.0 from a server whose editor speaks 4.x, and the whole creative has to come through intact. */
    @Test
    fun theServersOwnDocumentParses() {
        assertEquals("3.0", document.version)
        assertEquals(1, document.entries.size)
        assertEquals("KinodaranAds", ad.adSystem)
        assertEquals("TestVast", ad.title)
        assertEquals(30.0, ad.linear.duration)
        assertEquals(VastAd.SkipOffset.Time(5.0), ad.linear.skipOffset)
        assertTrue(ad.isSkippable, "the skip control is what the creative was sold with")
    }

    /** Six events, one impression and one error URI: the whole of what the ad server counts by. */
    @Test
    fun everythingTheServerCountsByIsParsed() {
        assertEquals(
            setOf(
                VastTrackingEvent.START, VastTrackingEvent.FIRST_QUARTILE, VastTrackingEvent.MIDPOINT,
                VastTrackingEvent.THIRD_QUARTILE, VastTrackingEvent.COMPLETE, VastTrackingEvent.SKIP,
            ),
            ad.linear.trackingEvents.keys,
        )
        assertEquals(1, ad.impressions.size)
        assertEquals(1, ad.errors.size)

        // Long query strings inside CDATA, with raw ampersands: every parameter has
        // to survive, because the signature covers them.
        val query = assertNotNull(ad.impressions.first().query)
        assertTrue(query.contains("event_type=impression"))
        assertTrue(query.contains("impression_id="))
        assertTrue(query.contains("sig="))
    }

    /** A linear ad with nowhere to click is still a linear ad. */
    @Test
    fun aCreativeWithNowhereToClickIsStillPlayable() {
        assertNull(ad.linear.clickThrough)
        assertTrue(ad.linear.clickTracking.isEmpty())
        assertTrue(ad.linear.customClicks.isEmpty())

        val file = ad.linear.mediaFiles.single()
        assertEquals("video/mp4", file.mimeType)
        assertEquals(1280, file.width)
        assertEquals(720, file.height)
        assertEquals(VastAd.Delivery.PROGRESSIVE, file.delivery)
    }

    /** The server sends the `uiSettings` extension that asks the *host* to draw the ad UI. */
    @Test
    fun theServerAsksForHostDrawnUi() {
        assertTrue(ad.isUiHidden)
        assertEquals("1", ad.extensions.first { it.type == "uiSettings" }.value("UiHideable"))
    }
}

package com.kinodaran.vast.demo

/**
 * The scenarios offered on the demo's first screen — the same ones as the
 * VastSDK-apple demos. Each exercises a different branch of the SDK, so picking
 * through the list is a manual pass over the behaviour the unit tests cover.
 */
data class DemoScenario(
    val id: String,
    val title: String,
    val detail: String,
    val source: Source,
    /**
     * The screen takes the whole ad UI over, and with it the §2.3 skip obligation:
     * it sets `isHiddenUi` and `skipPresentation = HOST`. Per scenario rather than
     * per app, so one list shows both halves of the rule — the same response hides
     * nothing on a screen that never opted in.
     */
    val hostDrawsUi: Boolean = false,
) {
    sealed interface Source {
        /** Fetched from an ad server, Wrapper chain and all. */
        data class Tag(val url: String) : Source

        /** A response already in hand. */
        data class Xml(val xml: String) : Source
    }
}

object DemoCatalog {

    /** Content the viewer is "watching". The break plays in front of it and it follows. */
    const val CONTENT_STREAM = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"

    /**
     * Stamps a live tag with a fresh `correlator` before requesting it.
     *
     * Google's ad server dedupes on that parameter: sent empty, the first request
     * is filled and every one after it returns an empty VAST — which the SDK
     * correctly reports as error 303, and which looks exactly like a bug in the
     * SDK. IMA generates this value itself, so a host driving its own player has
     * to do the same. Substituted textually, so nothing else in the URL is re-encoded.
     */
    fun requestReady(tag: String): String {
        if (!tag.endsWith("correlator=") && !tag.contains("correlator=&")) return tag
        return tag.replace("correlator=", "correlator=${System.currentTimeMillis()}")
    }

    val scenarios = listOf(
        DemoScenario(
            id = "live-linear",
            title = "Live tag — single inline linear",
            detail = "Google's public IMA sample tag. VAST 3.0 from a real ad server.",
            source = DemoScenario.Source.Tag(
                "https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_ad_samples&sz=640x480" +
                    "&cust_params=sample_ct%3Dlinear&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast" +
                    "&unviewed_position_start=1&env=vp&correlator=",
            ),
        ),
        DemoScenario(
            id = "live-skippable",
            title = "Live tag — skippable",
            detail = "Same server, skipoffset 00:00:05 — the skip control is real.",
            source = DemoScenario.Source.Tag(
                "https://pubads.g.doubleclick.net/gampad/ads?iu=/21775744923/external/single_preroll_skippable&sz=640x480" +
                    "&ciu_szs=300x250%2C728x90&gdfp_req=1&output=vast&unviewed_position_start=1&env=vp&correlator=",
            ),
        ),
        DemoScenario(
            id = "skippable",
            title = "Skippable InLine",
            detail = "52s creative, skip unlocks at 5s, 15s progress beacon.",
            source = DemoScenario.Source.Xml(DemoVast.inLine(skipOffset = "00:00:05")),
        ),
        DemoScenario(
            id = "non-skippable",
            title = "Non-skippable InLine",
            detail = "52s creative, no skipoffset — the skip control never appears.",
            source = DemoScenario.Source.Xml(DemoVast.inLine(skipOffset = null)),
        ),
        DemoScenario(
            id = "pod",
            title = "Ad Pod — 3 ads",
            detail = "Back to back. Skip at 2s, then 4s, then not at all.",
            source = DemoScenario.Source.Xml(DemoVast.pod),
        ),
        DemoScenario(
            id = "no-fill",
            title = "No fill",
            detail = "Empty response. The session reports VAST error 303 promptly.",
            source = DemoScenario.Source.Xml(DemoVast.NO_AD),
        ),
        DemoScenario(
            id = "host-ui",
            title = "Custom UI — uiSettings",
            detail = "Response asks the player to draw nothing. This screen draws its own skip.",
            source = DemoScenario.Source.Xml(DemoVast.inLine(skipOffset = "00:00:05", id = "demo-hostui")),
            hostDrawsUi = true,
        ),
        DemoScenario(
            id = "bad-media",
            title = "Unplayable creative",
            detail = "MediaFile type the player cannot decode — expect error 403.",
            source = DemoScenario.Source.Xml(DemoVast.UNSUPPORTED_MEDIA),
        ),
    )
}

object DemoVast {

    /** A public W3C sample clip, so the offline scenarios need no ad server and no hosting of our own. 854×480, 52.2s. */
    private const val CREATIVE = "https://media.w3.org/2010/05/sintel/trailer.mp4"

    /**
     * `duration` matches the real creative (52.2s). Declaring something else is
     * legal — `<Duration>` is advisory — but it makes the countdown jump the
     * moment the player reports the true length.
     */
    fun inLine(skipOffset: String?, id: String = "demo-1", duration: String = "00:00:52"): String {
        val skip = skipOffset?.let { """ skipoffset="$it"""" }.orEmpty()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <VAST version="4.3">
              <Ad id="$id">
                <InLine>
                  <AdSystem>VASTSDK Demo</AdSystem>
                  <AdTitle>${if (skipOffset == null) "Non-skippable" else "Skippable"} demo</AdTitle>
                  <Error><![CDATA[https://example.com/error?code=[ERRORCODE]]]></Error>
                  <Impression><![CDATA[https://example.com/impression]]></Impression>
                  <Creatives>
                    <Creative id="1">
                      <Linear$skip>
                        <Duration>$duration</Duration>
                        <TrackingEvents>
                          <Tracking event="start"><![CDATA[https://example.com/start]]></Tracking>
                          <Tracking event="firstQuartile"><![CDATA[https://example.com/q1]]></Tracking>
                          <Tracking event="midpoint"><![CDATA[https://example.com/q2]]></Tracking>
                          <Tracking event="thirdQuartile"><![CDATA[https://example.com/q3]]></Tracking>
                          <Tracking event="complete"><![CDATA[https://example.com/complete]]></Tracking>
                          <Tracking event="skip"><![CDATA[https://example.com/skip]]></Tracking>
                          <Tracking event="progress" offset="00:00:15"><![CDATA[https://example.com/p15]]></Tracking>
                        </TrackingEvents>
                        <VideoClicks>
                          <ClickThrough><![CDATA[https://kinodaran.com]]></ClickThrough>
                          <ClickTracking><![CDATA[https://example.com/click]]></ClickTracking>
                        </VideoClicks>
                        <MediaFiles>
                          <MediaFile delivery="progressive" type="video/mp4" width="854" height="480" bitrate="1200">
                            <![CDATA[$CREATIVE]]>
                          </MediaFile>
                        </MediaFiles>
                      </Linear>
                    </Creative>
                  </Creatives>
                  <Extensions>
                    <Extension type="uiSettings"><UiHideable>1</UiHideable></Extension>
                  </Extensions>
                </InLine>
              </Ad>
            </VAST>
        """.trimIndent()
    }

    /**
     * Three sequenced ads with deliberately different skip rules, because a pod
     * where they all behave the same proves nothing: the control resets per
     * creative rather than carrying over. Skippable at 2s, then 4s — the countdown
     * restarts — then not at all, and the control has to disappear again.
     */
    val pod: String = run {
        val skipOffsets = listOf("00:00:02", "00:00:04", null)
        val ads = (1..3).joinToString("\n") { ad("pod-$it", it, skipOffsets[it - 1]) }
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<VAST version=\"4.3\">\n$ads\n</VAST>"
    }

    private fun ad(id: String, sequence: Int, skipOffset: String?): String {
        val skip = skipOffset?.let { """ skipoffset="$it"""" }.orEmpty()
        return """
            <Ad id="$id" sequence="$sequence">
              <InLine>
                <AdSystem>VASTSDK Demo</AdSystem>
                <AdTitle>$id</AdTitle>
                <Error><![CDATA[https://example.com/error?code=[ERRORCODE]]]></Error>
                <Impression><![CDATA[https://example.com/impression/$id]]></Impression>
                <Creatives><Creative><Linear$skip>
                  <Duration>00:00:52</Duration>
                  <TrackingEvents>
                    <Tracking event="start"><![CDATA[https://example.com/$id/start]]></Tracking>
                    <Tracking event="firstQuartile"><![CDATA[https://example.com/$id/q1]]></Tracking>
                    <Tracking event="midpoint"><![CDATA[https://example.com/$id/q2]]></Tracking>
                    <Tracking event="thirdQuartile"><![CDATA[https://example.com/$id/q3]]></Tracking>
                    <Tracking event="complete"><![CDATA[https://example.com/$id/complete]]></Tracking>
                    <Tracking event="skip"><![CDATA[https://example.com/$id/skip]]></Tracking>
                  </TrackingEvents>
                  <MediaFiles>
                    <MediaFile delivery="progressive" type="video/mp4" width="854" height="480" bitrate="1200">
                      <![CDATA[$CREATIVE]]>
                    </MediaFile>
                  </MediaFiles>
                </Linear></Creative></Creatives>
              </InLine>
            </Ad>
        """.trimIndent()
    }

    val NO_AD = """
        <?xml version="1.0" encoding="UTF-8"?>
        <VAST version="4.3">
          <Error><![CDATA[https://example.com/no-ad]]></Error>
        </VAST>
    """.trimIndent()

    val UNSUPPORTED_MEDIA = """
        <?xml version="1.0" encoding="UTF-8"?>
        <VAST version="4.3">
          <Ad id="bad-media">
            <InLine>
              <AdSystem>VASTSDK Demo</AdSystem>
              <Error><![CDATA[https://example.com/error?code=[ERRORCODE]]]></Error>
              <Impression><![CDATA[https://example.com/impression]]></Impression>
              <Creatives><Creative><Linear>
                <Duration>00:00:15</Duration>
                <MediaFiles>
                  <MediaFile delivery="progressive" type="video/x-flv"><![CDATA[https://example.com/creative.flv]]></MediaFile>
                </MediaFiles>
              </Linear></Creative></Creatives>
            </InLine>
          </Ad>
        </VAST>
    """.trimIndent()
}

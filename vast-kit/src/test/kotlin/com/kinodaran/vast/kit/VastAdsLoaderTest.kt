package com.kinodaran.vast.kit

import android.content.Context
import android.net.Uri
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.media3.common.AdViewProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ads.AdsMediaSource
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeMediaSourceFactory
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.kinodaran.vast.core.VastAd
import com.kinodaran.vast.core.VastBeacon
import com.kinodaran.vast.core.VastError
import com.kinodaran.vast.core.VastException
import com.kinodaran.vast.core.VastMacroExpander
import com.kinodaran.vast.core.VastResourceLoader
import com.kinodaran.vast.core.VastTrackingEvent
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A whole break, end to end, in a real ExoPlayer: the response is resolved, the
 * creative is inserted by Media3 in front of the content, plays, and the content
 * follows. Only the media is fake — a timeline and samples instead of a file.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VastAdsLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock(true)
    private lateinit var player: ExoPlayer
    private val reported = mutableListOf<String>()
    private val events = mutableListOf<String>()

    private val recorder = object : VastAdSessionListener {
        override fun onReported(kind: VastBeacon.Kind, ad: VastAd?) {
            reported += label(kind)
        }

        override fun onAdStarted(ad: VastAd, position: VastAdPosition) {
            events += "started ${ad.id} ${position.index}/${position.total}"
        }

        override fun onAdFinished(ad: VastAd, outcome: VastAdOutcome) {
            events += "finished ${ad.id} $outcome"
        }

        override fun onAdFailed(error: VastError, ad: VastAd?) {
            events += "failed ${ad?.id} ${error.code}"
        }

        override fun onAllAdsFinished() {
            events += "all finished"
        }

        override fun onAdProgress(ad: VastAd, timeSeconds: Double, durationSeconds: Double) {
            progress += "%.2f@%.2f".format(timeSeconds, clock.elapsedRealtime() / 1000.0)
        }
    }
    private val progress = mutableListOf<String>()

    @Before
    fun setUp() {
        player = TestExoPlayerBuilder(context).setClock(clock).build()
    }

    @After
    fun tearDown() {
        player.release()
    }

    // MARK: - Playing through

    @Test
    fun aSingleAdPlaysToTheEndAndTheContentFollows() {
        val session = session(mapOf(TAG to inLine("a1", duration = "00:00:10")))
        play(session)

        assertEquals(
            listOf("loaded", "impression", "creativeView", "start", "firstQuartile", "midpoint", "thirdQuartile", "complete"),
            reported,
        )
        assertEquals(listOf("started a1 1/1", "finished a1 Completed", "all finished"), events)
        assertEquals(VastAdState.Finished(VastAdOutcome.Completed), session.state.value)
        assertEquals(Player.STATE_ENDED, player.playbackState, "the content played after the break")
    }

    @Test
    fun aPodPlaysInSequenceOrder() {
        val pod = """<VAST version="4.3">${ad("p2", sequence = 2)}${ad("p1", sequence = 1)}</VAST>"""
        val session = session(mapOf(TAG to pod))
        play(session)

        assertEquals(
            listOf("started p1 1/2", "finished p1 Completed", "started p2 2/2", "finished p2 Completed", "all finished"),
            events,
        )
    }

    // MARK: - No ad

    /** The content must not wait on a response that produced nothing, and the server hears 303 on its own URI. */
    @Test
    fun aNoFillResponsePlaysTheContentAndReportsTheError() {
        val session = session(mapOf(TAG to """<VAST version="4.3"><Error><![CDATA[https://ads.test/no-ad]]></Error></VAST>"""))
        play(session)

        assertEquals(listOf("error 303"), reported)
        assertEquals(listOf("failed null 303"), events)
        assertEquals(VastAdState.Finished(VastAdOutcome.Failed(VastError.NO_VAST_RESPONSE_AFTER_WRAPPERS)), session.state.value)
        assertEquals(Player.STATE_ENDED, player.playbackState)
    }

    @Test
    fun anUnreachableTagPlaysTheContent() {
        val session = session(emptyMap())
        play(session)

        assertEquals(VastAdState.Finished(VastAdOutcome.Failed(VastError.WRAPPER_TIMEOUT)), session.state.value)
        assertEquals(Player.STATE_ENDED, player.playbackState)
    }

    /** A creative this player cannot decode is a 403 before anything plays, and the content still follows. */
    @Test
    fun anAdWithNothingPlayableIsReportedAndSkipped() {
        val session = session(mapOf(TAG to inLine("flash", mimeType = "video/x-flv")))
        play(session)

        assertEquals(listOf("error 403"), reported)
        assertEquals(listOf("failed flash 403", "all finished"), events)
        assertEquals(Player.STATE_ENDED, player.playbackState)
    }

    // MARK: - Skipping

    @Test
    fun skippingEndsTheAdAndReturnsToTheContent() {
        val session = session(mapOf(TAG to inLine("s1", duration = "00:00:10", skipOffset = "00:00:02")))
        start(session)
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_READY)
        runUntil { session.canSkip.value }

        session.skip()
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)

        assertTrue("skip" in reported)
        assertTrue("complete" !in reported, "a skipped ad did not complete")
        assertEquals(listOf("started s1 1/1", "finished s1 Skipped", "all finished"), events)
        assertEquals(VastAdState.Finished(VastAdOutcome.Skipped), session.state.value)
    }

    /** §2.3: a skip the ad has not earned yet is refused out loud, never ignored. */
    @Test
    fun skippingTooEarlyIsRefused() {
        val session = session(mapOf(TAG to inLine("s1", duration = "00:00:10", skipOffset = "00:00:05")))
        start(session)
        runUntil { session.currentAd.value != null }

        val refusal = runCatching { session.skip() }.exceptionOrNull()
        assertTrue(refusal is VastSkipException.NotYetAvailable, "got $refusal")
    }

    /** A host that cannot draw a skip control is told to expect 200, not handed a breach of §2.3. */
    @Test
    fun aSkippableAdIsRefusedWhenNoOneCanDrawTheControl() {
        val session = session(
            mapOf(TAG to inLine("s1", skipOffset = "00:00:02")),
            VastConfiguration(skipPresentation = VastSkipPresentation.UNSUPPORTED),
        )
        play(session)

        assertEquals(listOf("error 200"), reported)
        assertEquals(Player.STATE_ENDED, player.playbackState)
    }

    // MARK: - Pausing

    @Test
    fun aPauseAndResumeAreReported() {
        val session = session(mapOf(TAG to inLine("a1", duration = "00:00:10")))
        start(session)
        runUntil { session.state.value == VastAdState.Playing && "start" in reported }

        session.pause()
        runUntil { session.state.value == VastAdState.Paused }
        session.resume()
        runUntil { session.state.value == VastAdState.Playing }
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)

        assertEquals(1, reported.count { it == "pause" })
        assertEquals(1, reported.count { it == "resume" })
        assertTrue(reported.indexOf("pause") < reported.indexOf("resume"))
    }

    // MARK: - Leaving the app

    /**
     * An ad nobody can see must not earn quartiles. Leaving the app holds it —
     * not reported as a pause, because nobody paused — and coming back resumes it.
     */
    @Test
    fun anAdIsHeldWhileTheAppIsOffScreenAndResumedOnReturn() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            override val lifecycle: Lifecycle get() = registry
        }
        val session = session(mapOf(TAG to inLine("a1")), appLifecycle = owner.registry)
        start(session)
        runUntil { session.state.value == VastAdState.Playing && "start" in reported }

        owner.registry.currentState = Lifecycle.State.CREATED
        runUntil { !player.playWhenReady }
        assertEquals(VastAdState.Playing, session.state.value, "a system hold is not a pause the host should draw")

        owner.registry.currentState = Lifecycle.State.RESUMED
        runUntil { player.playWhenReady }
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)

        assertTrue("pause" !in reported && "resume" !in reported, reported.toString())
        assertTrue("complete" in reported)
    }

    // MARK: - Speed

    /** An ad at 2× is watched in half the time with every quartile still firing; it plays at 1× and the content gets its speed back. */
    @Test
    fun theAdPlaysAtNormalSpeedAndTheContentKeepsItsOwn() {
        val session = session(mapOf(TAG to inLine("a1")))
        player.setPlaybackSpeed(2f)
        start(session)
        runUntil { session.state.value == VastAdState.Playing }

        assertEquals(1f, player.playbackParameters.speed, "the ad played at the content's speed")
        player.setPlaybackSpeed(1.5f)
        runUntil { player.playbackParameters.speed == 1f }

        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)
        assertEquals(2f, player.playbackParameters.speed, "the content lost the speed the viewer chose")
    }

    // MARK: - Measurement

    /** Measurement hears the beacons' own sequence, opened before the impression and closed exactly once. */
    @Test
    fun measurementFollowsTheBeaconsInOrder() {
        val recorded = mutableListOf<String>()
        val session = session(mapOf(TAG to inLine("a1")))
        session.measurement = object : VastMeasurement {
            override fun begin(context: VastMeasurementContext) {
                recorded += "begin ${context.ad.id}"
            }

            override fun record(event: VastMeasurementEvent) {
                recorded += event.toString()
            }

            override fun finish() {
                recorded += "finish"
            }
        }
        play(session)

        assertEquals("begin a1", recorded.first())
        assertEquals("Impression", recorded[1])
        assertTrue(recorded[2].startsWith("Start("), recorded.toString())
        assertEquals(listOf("Quartile(event=FIRST_QUARTILE)", "Quartile(event=MIDPOINT)", "Quartile(event=THIRD_QUARTILE)", "Quartile(event=COMPLETE)", "finish"), recorded.drop(3), progress.toString())
        assertTrue("verificationNotExecuted" !in reported, "a configured measurement owns that report")
    }

    // MARK: - Clicks

    @Test
    fun aClickFiresItsTrackersAndHandsBackTheDestination() {
        val session = session(mapOf(TAG to inLine("a1", clickThrough = "https://advertiser.test/landing")))
        start(session)
        runUntil { session.currentAd.value != null }

        assertEquals("https://advertiser.test/landing", session.click())
        assertTrue("clickTracking" in reported)
    }

    // MARK: - Data tags

    /** A response already in hand goes in as a `data:` tag and is played the same way. */
    @Test
    fun aResponseHandedOverAsADataUriPlays() {
        val session = session(emptyMap())
        play(session, VastAdsLoader.adTagUriForResponse(inLine("inline-data")))

        assertTrue(events.first().startsWith("started inline-data"))
        assertEquals(VastAdState.Finished(VastAdOutcome.Completed), session.state.value)
    }

    // MARK: - Harness

    private fun session(
        documents: Map<String, String>,
        configuration: VastConfiguration = VastConfiguration(),
        appLifecycle: Lifecycle? = null,
    ): VastAdSession {
        val loader = object : VastResourceLoader {
            override suspend fun loadVast(url: String, timeoutSeconds: Double): String =
                documents[url] ?: throw VastException(VastError.WRAPPER_TIMEOUT)
        }
        val transport = object : VastBeaconTransport {
            override suspend fun fire(beacons: List<VastBeacon>) {}
        }
        val environment = VastAdSession.Environment(
            transport = transport,
            loader = loader,
            appBundle = "com.kinodaran.test",
            screenSize = { VastMacroExpander.Size(1280, 720) },
            uptimeSeconds = { clock.elapsedRealtime() / 1000.0 },
            appLifecycle = appLifecycle,
        )
        return VastAdSession(configuration, environment, Dispatchers.Main.immediate).also { it.listener = recorder }
    }

    private fun start(session: VastAdSession, tag: Uri = Uri.parse(TAG)) {
        session.adsLoader.setPlayer(player)
        val content = FakeMediaSource(FakeTimeline(FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(30_000_000).build()))
        val adSource = FakeMediaSourceFactory(FakeTimeline.TimelineWindowDefinition.Builder().setDurationUs(10_000_000))
        val source = AdsMediaSource(content, DataSpec(tag), "ads-1", adSource, session.adsLoader, AdViewProvider { FrameLayout(context) })
        player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    private fun play(session: VastAdSession, tag: Uri = Uri.parse(TAG)) {
        start(session, tag)
        TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED)
    }

    private fun runUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition never held; reported=$reported events=$events" }
            org.robolectric.shadows.ShadowLooper.runMainLooperOneTask()
        }
    }

    private fun label(kind: VastBeacon.Kind): String = when (kind) {
        VastBeacon.Kind.Impression -> "impression"
        is VastBeacon.Kind.Tracking -> kind.event.vastName
        is VastBeacon.Kind.Progress -> "progress"
        VastBeacon.Kind.ClickTracking -> "clickTracking"
        is VastBeacon.Kind.Error -> "error ${kind.error.code}"
        is VastBeacon.Kind.VerificationNotExecuted -> "verificationNotExecuted"
        VastBeacon.Kind.ViewUndetermined -> "viewUndetermined"
        VastBeacon.Kind.CustomClick -> "customClick"
    }

    private companion object {
        const val TAG = "https://ads.test/tag.xml"

        fun ad(
            id: String,
            sequence: Int? = null,
            duration: String = "00:00:10",
            skipOffset: String? = null,
            mimeType: String = "video/mp4",
            clickThrough: String? = null,
        ): String {
            val seq = sequence?.let { """ sequence="$it"""" } ?: ""
            val skip = skipOffset?.let { """ skipoffset="$it"""" } ?: ""
            val click = clickThrough?.let {
                "<VideoClicks><ClickThrough><![CDATA[$it]]></ClickThrough><ClickTracking><![CDATA[https://ads.test/$id/click]]></ClickTracking></VideoClicks>"
            } ?: ""
            val events = listOf("loaded", "creativeView", "start", "firstQuartile", "midpoint", "thirdQuartile", "complete", "skip", "pause", "resume")
                .joinToString("") { """<Tracking event="$it"><![CDATA[https://ads.test/$id/$it]]></Tracking>""" }
            return """
                <Ad id="$id"$seq><InLine>
                  <AdSystem>test</AdSystem>
                  <Error><![CDATA[https://ads.test/$id/error?code=[ERRORCODE]]]></Error>
                  <Impression><![CDATA[https://ads.test/$id/impression]]></Impression>
                  <Creatives><Creative><Linear$skip>
                    <Duration>$duration</Duration>
                    <TrackingEvents>$events</TrackingEvents>
                    $click
                    <MediaFiles><MediaFile delivery="progressive" type="$mimeType" width="1280" height="720"><![CDATA[https://cdn.test/$id.mp4]]></MediaFile></MediaFiles>
                  </Linear></Creative></Creatives>
                </InLine></Ad>
            """.trimIndent()
        }

        fun inLine(
            id: String,
            duration: String = "00:00:10",
            skipOffset: String? = null,
            mimeType: String = "video/mp4",
            clickThrough: String? = null,
        ) = """<VAST version="4.3">${ad(id, null, duration, skipOffset, mimeType, clickThrough)}</VAST>"""
    }
}

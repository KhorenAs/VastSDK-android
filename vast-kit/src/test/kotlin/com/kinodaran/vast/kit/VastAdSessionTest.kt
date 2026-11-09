package com.kinodaran.vast.kit

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.kinodaran.vast.core.VastAd
import com.kinodaran.vast.core.VastBeacon
import com.kinodaran.vast.core.VastDocument
import com.kinodaran.vast.core.VastError
import com.kinodaran.vast.core.VastMacroExpander
import com.kinodaran.vast.core.VastParser
import com.kinodaran.vast.core.VastResourceLoader
import com.kinodaran.vast.core.VastTick
import com.kinodaran.vast.core.VastTrackingEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The session's own decisions, driven the way `VastAdsLoader` drives them —
 * a creative starts, ticks arrive, it fails or stalls — with a scripted player
 * and clock instead of ExoPlayer, so a moment that is hard to provoke in a real
 * player (a creative failing while it is held, a playhead stuck for ten seconds)
 * is a line of test rather than a race.
 *
 * The scripted player is shared across creatives the way Media3's is: a pause
 * the session put on one creative is still on the player when the next begins.
 */
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: the session reacts inside the call that drives it.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VastAdSessionTest {

    private val reported = mutableListOf<String>()
    private val events = mutableListOf<String>()
    private var uptime = 0.0
    private val player = ScriptedPlayer()
    private val app = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val recorder = object : VastAdSessionListener {
        override fun onReported(kind: VastBeacon.Kind, ad: VastAd?) {
            reported += label(kind)
        }

        override fun onAdStarted(ad: VastAd, position: VastAdPosition) {
            events += "started ${ad.id}"
        }

        override fun onAdFinished(ad: VastAd, outcome: VastAdOutcome) {
            events += "finished ${ad.id} ${label(outcome)}"
        }

        override fun onAdFailed(error: VastError, ad: VastAd?) {
            events += "failed ${ad?.id} ${error.code}"
        }

        override fun onAllAdsFinished() {
            events += "all finished"
        }
    }

    // MARK: - Holds

    /**
     * A hold belongs to the creative it was put on, however that creative ended.
     * Left in place after a failure, it made the session believe it still had
     * the player paused: the viewer starting the content again from the
     * notification went unnoticed, and the next break's ad played behind the
     * home screen, earning quartiles from nobody.
     */
    @Test
    fun aHoldDoesNotOutliveACreativeThatFailedWhileHeld() {
        val session = session()
        val first = slot("a1")
        val firstClock = ScriptedClock()
        session.slotStarted(first, 1, firstClock)
        firstClock.play(from = 0.0, to = 2.0)
        leaveApp()
        assertFalse(player.playWhenReady, "the ad was not held off screen")

        session.slotFailed(first, VastError.MEDIA_FILE_TIMEOUT)
        session.breakEnded()
        // Still in the background, the viewer starts the content again from the notification.
        player.setPlayWhenReady(true)

        val next = slot("b1")
        session.slotStarted(next, 1, ScriptedClock())

        assertFalse(player.playWhenReady, "the next ad played to nobody: the failed creative's hold was still on")
        returnToApp()
        assertTrue(player.playWhenReady, "the ad was not resumed when the app came back")
        assertTrue("pause" !in reported && "resume" !in reported, "a hold is not a pause: $reported")
    }

    /**
     * Media3 prepares the creative after the one on screen while it plays, and
     * that one can fail first. Its failure is not the end of the creative the
     * hold is on.
     */
    @Test
    fun aCreativeQueuedBehindFailingDoesNotReleaseTheHoldOnTheOneOnScreen() {
        val session = session()
        val onScreen = slot("a1")
        val clock = ScriptedClock()
        session.slotStarted(onScreen, 2, clock)
        clock.play(from = 0.0, to = 2.0)
        leaveApp()

        session.slotFailed(slot("a2", index = 1), VastError.MEDIA_FILE_NOT_FOUND)
        returnToApp()

        assertTrue(player.playWhenReady, "the hold on the ad on screen was lost, so nothing resumed it")
    }

    /**
     * The other half of the same rule, and the half that is Android's own:
     * Media3's player is shared, so the next creative inherits the pause the
     * failed one was held with. The restart is still owed, and coming back must
     * make it — dropping it along with the hold left the next ad frozen while
     * the session said it was playing.
     */
    @Test
    fun theNextAdInheritingAHeldPauseIsResumedWhenTheAppComesBack() {
        val session = session()
        val first = slot("a1")
        val firstClock = ScriptedClock()
        session.slotStarted(first, 2, firstClock)
        firstClock.play(from = 0.0, to = 2.0)
        leaveApp()
        session.slotFailed(first, VastError.MEDIA_FILE_TIMEOUT)
        session.slotStarted(slot("a2", index = 1), 2, ScriptedClock())
        assertFalse(player.playWhenReady, "the next ad played to nobody")

        returnToApp()

        assertTrue(player.playWhenReady, "the next ad was left paused while the session said ${session.state.value}")
        assertTrue("pause" !in reported && "resume" !in reported, "a hold is not a pause: $reported")
    }

    // MARK: - Stalls

    @Test
    fun aStallIsReportedAs402() {
        val session = session()
        val stalled = slot("a1")
        val clock = ScriptedClock()
        session.slotStarted(stalled, 1, clock)
        clock.play(from = 0.0, to = 3.0)

        clock.stick(at = 3.0, forSeconds = VastAdSession.STALL_TIMEOUT_SECONDS)
        assertTrue("error 402" !in reported, "exactly the timeout is not yet a stall")
        clock.stick(at = 3.0, forSeconds = 1.0)

        assertTrue("error 402" in reported, reported.toString())
        assertEquals(listOf("abandon a1"), player.abandoned)
    }

    /**
     * The ad server was told 402, the listener heard 402, and the outcome used
     * to say 405: the engine had finished the creative by failing it, so the
     * outcome fell through to "finished short of the end".
     */
    @Test
    fun aStalledAdEndsWithTheErrorItReported() {
        val session = session()
        val stalled = slot("a1")
        val clock = ScriptedClock()
        session.slotStarted(stalled, 1, clock)
        clock.play(from = 0.0, to = 3.0)
        clock.stick(at = 3.0, forSeconds = VastAdSession.STALL_TIMEOUT_SECONDS + 1)
        session.breakEnded()

        assertTrue("finished a1 failed 402" in events, events.toString())
        assertEquals(VastAdOutcome.Failed(VastError.MEDIA_FILE_TIMEOUT), stalled.outcome)
        assertEquals(VastAdState.Finished(VastAdOutcome.Failed(VastError.MEDIA_FILE_TIMEOUT)), session.state.value)
    }

    /** A response with no `<Error>` URI has nobody to send 402 to; the host still has to hear the ad failed. */
    @Test
    fun aStallIsAnnouncedEvenWhenTheAdHasNoErrorUri() {
        val session = session()
        val stalled = slot("a1", errorUri = false)
        val clock = ScriptedClock()
        session.slotStarted(stalled, 1, clock)
        clock.play(from = 0.0, to = 3.0)
        clock.stick(at = 3.0, forSeconds = VastAdSession.STALL_TIMEOUT_SECONDS + 1)

        assertTrue(reported.none { it.startsWith("error") }, "there was no <Error> to send: $reported")
        assertTrue("failed a1 402" in events, events.toString())
        assertTrue("finished a1 failed 402" in events, events.toString())
    }

    /** The last frames of a creative already watched through are its end, not a failure. */
    @Test
    fun aStallAfterTheCreativeWasWatchedThroughIsItsEnd() {
        val session = session()
        val stalled = slot("a1")
        val clock = ScriptedClock()
        session.slotStarted(stalled, 1, clock)
        // Past the completion threshold, short of where the engine would finish on its own.
        val watchedThrough = 10.0 * VastTrackingEngine.COMPLETION_THRESHOLD + 0.02
        clock.play(from = 0.0, to = watchedThrough)
        assertTrue("complete" !in reported)
        clock.stick(at = watchedThrough, forSeconds = VastAdSession.STALL_TIMEOUT_SECONDS + 1)

        assertTrue("complete" in reported, reported.toString())
        assertTrue(reported.none { it.startsWith("error") }, reported.toString())
        assertTrue(events.none { it.startsWith("failed") }, events.toString())
        assertTrue("finished a1 completed" in events, events.toString())
    }

    // MARK: - Harness

    private fun session(configuration: VastConfiguration = VastConfiguration()): VastAdSession {
        val loader = object : VastResourceLoader {
            override suspend fun loadVast(url: String, timeoutSeconds: Double): String = error("no tags in this test")
        }
        val transport = object : VastBeaconTransport {
            override suspend fun fire(beacons: List<VastBeacon>) = Unit
        }
        val environment = VastAdSession.Environment(
            transport = transport,
            loader = loader,
            appBundle = "com.kinodaran.test",
            screenSize = { VastMacroExpander.Size(1280, 720) },
            uptimeSeconds = { uptime },
            appLifecycle = app.registry,
        )
        return VastAdSession(configuration, environment, UnconfinedTestDispatcher()).also {
            it.listener = recorder
            it.attach(player)
            player.session = it
        }
    }

    private fun leaveApp() {
        app.registry.currentState = Lifecycle.State.CREATED
    }

    private fun returnToApp() {
        app.registry.currentState = Lifecycle.State.RESUMED
    }

    private fun slot(id: String, index: Int = 0, errorUri: Boolean = true): VastAdSlot {
        val ad = parse(inLine(id, errorUri))
        return VastAdSlot(index, ad, ad.linear.mediaFiles.first(), VastTrackingEngine(ad))
    }

    private fun parse(xml: String): VastAd {
        val body = VastParser().parse(xml).entries.first().body
        return (body as VastDocument.Body.InLine).ad
    }

    /**
     * Media3's player as the session sees it: one `playWhenReady` for every
     * creative and the content, reported back through `notePlayback` the way the
     * loader's `onPlayWhenReadyChanged` does.
     */
    private class ScriptedPlayer : VastBreakPlayer {
        lateinit var session: VastAdSession
        var playWhenReady = true
            private set
        val abandoned = mutableListOf<String>()

        override val wantsPlayback: Boolean get() = playWhenReady

        fun setPlayWhenReady(value: Boolean) {
            if (playWhenReady == value) return
            playWhenReady = value
            session.notePlayback(value)
        }

        override fun skip(slot: VastAdSlot) = Unit

        override fun abandon(slot: VastAdSlot) {
            abandoned += "abandon ${slot.ad.id}"
        }

        override fun pause() = setPlayWhenReady(false)

        override fun play() = setPlayWhenReady(true)

        override fun onAdClicked() = Unit
    }

    /** Ticks on demand, a quarter of a second apart; uptime moves with them. */
    private inner class ScriptedClock : VastClock {
        private val flow = MutableSharedFlow<VastTick>(extraBufferCapacity = 256)
        private var wall = 0.0

        override fun ticks(): Flow<VastTick> = flow

        override fun stop() = Unit

        fun play(from: Double, to: Double) {
            var time = from
            while (time < to) {
                emit(time)
                time = minOf(to, time + STEP)
                advance(STEP)
            }
            emit(to)
        }

        /** The playhead stays put while the player claims to be playing. */
        fun stick(at: Double, forSeconds: Double) {
            var stuck = 0.0
            while (stuck < forSeconds) {
                advance(STEP)
                stuck += STEP
                emit(at)
            }
        }

        private fun advance(seconds: Double) {
            wall += seconds
            uptime += seconds
        }

        private fun emit(adTime: Double) {
            check(flow.tryEmit(VastTick(adTime = adTime, duration = 10.0, rate = 1f, wallClock = wall)))
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

    private fun label(outcome: VastAdOutcome): String = when (outcome) {
        VastAdOutcome.Completed -> "completed"
        VastAdOutcome.Skipped -> "skipped"
        is VastAdOutcome.Failed -> "failed ${outcome.error.code}"
    }

    private companion object {
        const val STEP = 0.25

        fun inLine(id: String, errorUri: Boolean): String {
            val error = if (errorUri) "<Error><![CDATA[https://ads.test/$id/error?code=[ERRORCODE]]]></Error>" else ""
            val events = listOf("start", "firstQuartile", "midpoint", "thirdQuartile", "complete", "pause", "resume")
                .joinToString("") { """<Tracking event="$it"><![CDATA[https://ads.test/$id/$it]]></Tracking>""" }
            return """
                <VAST version="4.3"><Ad id="$id"><InLine>
                  <AdSystem>test</AdSystem>
                  $error
                  <Impression><![CDATA[https://ads.test/$id/impression]]></Impression>
                  <Creatives><Creative><Linear>
                    <Duration>00:00:10</Duration>
                    <TrackingEvents>$events</TrackingEvents>
                    <MediaFiles><MediaFile delivery="progressive" type="video/mp4" width="1280" height="720"><![CDATA[https://cdn.test/$id.mp4]]></MediaFile></MediaFiles>
                  </Linear></Creative></Creatives>
                </InLine></Ad></VAST>
            """.trimIndent()
        }
    }
}

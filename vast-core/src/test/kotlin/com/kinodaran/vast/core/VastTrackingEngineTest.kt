package com.kinodaran.vast.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The engine is pure, so every rule below is exercised with a scripted tick
 * sequence — no player, no media file, no real elapsed time.
 */
class VastTrackingEngineTest {

    // MARK: - Fixtures

    private fun url(path: String) = "https://ads.test/$path"

    /** 30 second ad, skippable at 5s, full quartile set plus a 15s progress beacon. */
    private fun makeAd(duration: Double = 30.0) = VastAd(
        id = "ad-1",
        linear = VastAd.Linear(
            duration = duration,
            skipOffset = VastAd.SkipOffset.Time(5.0),
            trackingEvents = mapOf(
                VastTrackingEvent.START to listOf(url("start")),
                VastTrackingEvent.FIRST_QUARTILE to listOf(url("q1")),
                VastTrackingEvent.MIDPOINT to listOf(url("q2")),
                VastTrackingEvent.THIRD_QUARTILE to listOf(url("q3")),
                VastTrackingEvent.COMPLETE to listOf(url("complete")),
                VastTrackingEvent.SKIP to listOf(url("skip")),
            ),
            progressEvents = listOf(VastAd.ProgressEvent(VastAd.SkipOffset.Time(15.0), url("p15"))),
        ),
        impressions = listOf(url("impression")),
        errors = listOf(url("error")),
    )

    private fun tick(time: Double, wall: Double, rate: Float = 1f, ours: Boolean = true) =
        VastTick(adTime = time, duration = 30.0, rate = rate, wallClock = wall, itemIsOurs = ours)

    /** Runs a tick sequence and returns the events that fired, in order. */
    private fun VastTrackingEngine.play(ticks: List<Pair<Double, Double>>): List<String> =
        ticks.flatMap { advance(tick(it.first, it.second)) }.map { it.label }

    private fun seconds(through: Int) = (0..through).map { it.toDouble() to it.toDouble() }

    // MARK: - Normal playback

    @Test
    fun uninterruptedPlaybackFiresEveryEventOnce() {
        val engine = VastTrackingEngine(makeAd())
        val events = engine.play(seconds(through = 30))

        assertEquals(
            listOf("impression", "start", "firstQuartile", "midpoint", "p15", "thirdQuartile", "complete"),
            events,
        )
        assertEquals(30.0, engine.watched, 0.01)
    }

    @Test
    fun impressionFiresOnceEvenAcrossManyTicks() {
        val events = VastTrackingEngine(makeAd()).play(seconds(through = 3))

        assertEquals(1, events.count { it == "impression" })
        assertEquals(1, events.count { it == "start" })
    }

    // MARK: - Seeking

    /**
     * VAST 4.3 §3.14.1 defines a quartile as the creative having "played
     * continuously ... at normal speed", so skipping ahead must not earn one.
     */
    @Test
    fun forwardSeekDoesNotEarnQuartiles() {
        val engine = VastTrackingEngine(makeAd())
        val events = engine.play(listOf(0.0 to 0.0, 1.0 to 1.0, 2.0 to 2.0, 28.0 to 3.0, 29.0 to 4.0))

        assertEquals(listOf("impression", "start"), events)
        assertEquals(2.0, engine.watched, 0.01)
    }

    /**
     * Regression: the tick immediately after a seek looks like ordinary playback,
     * so a mark anchored on the raw playhead let the skipped span back in and
     * unlocked every quartile. Coverage stays where the viewer left off.
     */
    @Test
    fun tickAfterForwardSeekDoesNotReadmitSkippedTime() {
        val engine = VastTrackingEngine(makeAd())
        engine.play(listOf(0.0 to 0.0, 1.0 to 1.0, 2.0 to 2.0, 28.0 to 3.0, 29.0 to 4.0, 30.0 to 5.0))

        assertEquals(2.0, engine.watched, 0.01)
    }

    @Test
    fun rewindDoesNotRefireAlreadyEarnedQuartiles() {
        val engine = VastTrackingEngine(makeAd())
        val events = engine.play(listOf(0.0 to 0.0, 8.0 to 8.0, 16.0 to 16.0, 2.0 to 17.0, 3.0 to 18.0, 4.0 to 19.0))

        assertEquals(1, events.count { it == "firstQuartile" })
        assertEquals(1, events.count { it == "midpoint" })
        // Coverage peaked at 16s; rewinding and replaying does not extend it.
        assertEquals(16.0, engine.watched, 0.01)
    }

    // MARK: - Player state

    @Test
    fun pausedTicksDoNotAccumulateWatchedTime() {
        val engine = VastTrackingEngine(makeAd())
        engine.advance(tick(0.0, 0.0))
        engine.advance(tick(4.0, 4.0))
        // Paused for ten wall-clock seconds; the playhead does not move.
        engine.advance(tick(4.0, 14.0, rate = 0f))
        engine.advance(tick(5.0, 15.0))

        assertEquals(5.0, engine.watched, 0.01, "the pause cost no coverage")
    }

    /** The host swapping the player's item out means nothing afterwards can be attributed to this creative. */
    @Test
    fun foreignPlayerItemEndsSessionWithDisplayError() {
        val engine = VastTrackingEngine(makeAd())
        engine.advance(tick(0.0, 0.0))
        engine.advance(tick(4.0, 4.0))
        val beacons = engine.advance(tick(5.0, 5.0, ours = false))

        assertEquals(listOf("error 405"), beacons.map { it.label })
        assertTrue(engine.isFinished)
        assertTrue(engine.advance(tick(6.0, 6.0)).isEmpty(), "a finished session emits nothing further")
    }

    // MARK: - Skip

    /**
     * §3.14.1: a `progress` offset the viewer already earned still counts when the
     * ad is skipped after it — that is what makes a skipped view billable.
     */
    @Test
    fun skipAfterProgressOffsetStillFiresThatProgressBeacon() {
        val engine = VastTrackingEngine(makeAd())
        engine.play(seconds(through = 16))

        assertEquals(listOf("skip"), engine.userDidSkip().map { it.label }, "p15 already fired during playback")
        assertTrue(engine.isFinished)
    }

    @Test
    fun skipBeforeProgressOffsetDoesNotFireIt() {
        val engine = VastTrackingEngine(makeAd())
        engine.play(seconds(through = 2))

        assertEquals(listOf("skip"), engine.userDidSkip().map { it.label })
    }

    @Test
    fun skipAfterFinishEmitsNothing() {
        val engine = VastTrackingEngine(makeAd())
        engine.play(seconds(through = 30))

        assertTrue(engine.userDidSkip().isEmpty())
    }

    // MARK: - Errors

    @Test
    fun failureFiresEveryErrorUriInTheChain() {
        val ad = VastAd(
            id = "ad-1",
            linear = VastAd.Linear(duration = 30.0),
            errors = listOf(url("wrapper-1-error"), url("wrapper-2-error"), url("inline-error")),
        )
        val beacons = VastTrackingEngine(ad).fail(VastError.NO_SUPPORTED_MEDIA_FILE)

        assertEquals(3, beacons.size, "§2.3.5.1 requires every wrapper in the chain to be told")
        assertEquals(setOf("error 403"), beacons.map { it.label }.toSet())
    }

    // MARK: - Duration

    @Test
    fun zeroDurationNeverFiresQuartiles() {
        val events = VastTrackingEngine(makeAd(duration = 0.0), duration = 0.0).play(seconds(through = 2))

        assertFalse("firstQuartile" in events)
    }

    /** `<Duration>` is advisory; the player's real duration wins when supplied. */
    @Test
    fun playerDurationOverridesDeclaredDuration() {
        val events = VastTrackingEngine(makeAd(duration = 30.0), duration = 8.0)
            .play(listOf(0.0 to 0.0, 2.0 to 2.0, 4.0 to 4.0))

        assertTrue("firstQuartile" in events)
        assertTrue("midpoint" in events)
    }

    // MARK: - End of playback

    /**
     * Buffering does not skip any of the creative, so it must not stand between
     * the viewer and `complete`.
     */
    @Test
    fun stallDoesNotPreventCompletion() {
        val engine = VastTrackingEngine(makeAd())
        val events = mutableListOf<String>()

        events += engine.advance(tick(0.0, 0.0)).map { it.label }
        for (second in 1..10) events += engine.advance(tick(second.toDouble(), second.toDouble())).map { it.label }
        events += engine.advance(tick(10.0, 18.0)).map { it.label } // stalled 8s
        for (second in 11..30) events += engine.advance(tick(second.toDouble(), second + 8.0)).map { it.label }

        assertEquals(30.0, engine.watched, 0.01, "the stall cost no coverage")
        assertTrue("complete" in events)
    }

    /**
     * Real media routinely runs a little short of its declared `<Duration>`, so
     * coverage alone may never cross the threshold. The player's end-of-item
     * signal is what ends the ad in that case.
     */
    @Test
    fun shortMediaEndsOnEndOfItemRatherThanHanging() {
        val engine = VastTrackingEngine(makeAd(duration = 30.0), duration = 30.0)
        // The file actually runs 26s, so coverage stops well short of 29.1.
        engine.advance(tick(0.0, 0.0))
        for (second in 1..26) engine.advance(tick(second.toDouble(), second.toDouble()))
        assertFalse(engine.isFinished, "coverage cannot reach a threshold the media never allows")

        val beacons = engine.playbackDidReachEnd().map { it.label }
        assertTrue(engine.isFinished)
        assertFalse("complete" in beacons, "26 of a declared 30 seconds is not 100% of the creative")
    }

    /**
     * Reaching the end is not the same as having watched it: seeking to the end
     * must not earn `complete` (§3.14.1 "played to the end at normal speed").
     */
    @Test
    fun seekingToTheEndDoesNotEarnComplete() {
        val engine = VastTrackingEngine(makeAd())
        engine.advance(tick(0.0, 0.0))
        engine.advance(tick(1.0, 1.0))
        engine.advance(tick(29.0, 2.0)) // jump

        assertFalse("complete" in engine.playbackDidReachEnd().map { it.label })
        assertTrue(engine.isFinished, "the session still ends")
    }

    @Test
    fun endOfItemIsIgnoredAfterTheSessionAlreadyFinished() {
        val engine = VastTrackingEngine(makeAd())
        engine.play(seconds(through = 30))

        assertTrue(engine.isFinished)
        assertTrue(engine.playbackDidReachEnd().isEmpty(), "complete must not fire twice")
    }

    /**
     * `<Duration>` is advisory and routinely disagrees with the real media by a
     * fraction of a second; the player's figure replaces it.
     */
    @Test
    fun reportedDurationReplacesTheDeclaredOne() {
        val engine = VastTrackingEngine(makeAd(duration = 24.0))
        engine.advance(VastTick(adTime = 0.0, duration = 24.04, rate = 1f, wallClock = 0.0))
        assertEquals(24.04, engine.duration, 0.001)
    }

    // MARK: - The end of the creative

    /**
     * What the engine cuts off the end of a creative is bounded by one tick, not by
     * a fraction of the running time.
     */
    @Test
    fun theCutAtTheEndIsWithinOneTickWhateverTheDuration() {
        val interval = 0.2

        for (duration in listOf(10.0, 30.0, 60.0)) {
            val engine = VastTrackingEngine(makeAd(duration = duration), duration = duration)
            var completedAt: Double? = null
            var time = 0.0

            while (time <= duration && completedAt == null) {
                val fired = engine.advance(VastTick(adTime = time, duration = duration, rate = 1f, wallClock = time))
                if (fired.any { it.label == "complete" }) completedAt = time
                time += interval
            }

            assertNotNull(completedAt, "a ${duration.toInt()}s creative never completed")
            assertTrue(
                duration - completedAt <= interval + 0.001,
                "a ${duration.toInt()}s creative lost more than one tick of its ending",
            )
        }
    }

    /**
     * A creative that stalls *after* being watched through is finished, not
     * broken. Reporting 402 for its last frames would tell the ad server that a
     * delivered impression had failed.
     */
    @Test
    fun aStallPastTheWatchedThresholdCompletesRatherThanFailing() {
        val engine = VastTrackingEngine(makeAd(duration = 30.0), duration = 30.0)
        engine.advance(tick(0.0, 0.0))
        for (second in 1..29) engine.advance(tick(second.toDouble(), second.toDouble()))
        // Past `COMPLETION_THRESHOLD`, short of the margin the engine finishes on.
        engine.advance(tick(29.5, 29.5))
        assertFalse(engine.isFinished)

        val beacons = engine.playbackDidStall().map { it.label }
        assertTrue("complete" in beacons, "29.5 of 30 seconds is a watched creative")
        assertFalse("error 402" in beacons)
        assertTrue(engine.isFinished)
    }

    /** A creative that never advances is written off, and the error goes to every URI in the chain. */
    @Test
    fun stallReportsMediaTimeout() {
        val engine = VastTrackingEngine(makeAd())
        engine.advance(tick(0.0, 0.0))

        assertEquals(listOf("error 402"), engine.playbackDidStall().map { it.label })
        assertTrue(engine.isFinished)
    }
}

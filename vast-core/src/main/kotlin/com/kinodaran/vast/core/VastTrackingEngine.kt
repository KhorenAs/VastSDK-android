package com.kinodaran.vast.core

import kotlin.math.max
import kotlin.math.min

/**
 * Decides which beacons a playback session owes, from a stream of ticks.
 *
 * Pure and deterministic: no IO, no player, no wall-clock reads of its own. Feed
 * it a tick sequence and it returns the beacons — which is exactly how the seek
 * and asset-swap rules are tested.
 *
 * Not thread-safe; a session drives one engine from one thread.
 */
public class VastTrackingEngine(
    private val ad: VastAd,
    /** Pins the duration; the player's reported figure is then ignored. */
    duration: Double? = null,
    /**
     * Whether something is going to execute the ad's verification resources.
     *
     * The engine cannot know this — running verification code means an Open
     * Measurement integration, which lives above this layer — so it is stated by
     * whoever built the engine. When nothing will run them, the vendors are owed
     * `verificationNotExecuted` rather than silence.
     */
    private val measurementWillRun: Boolean = false,
) {

    /**
     * Starts from `<Duration>` and is replaced by the player's own figure as soon
     * as one is available: the declared value is advisory, and quartiles computed
     * against the wrong length land in the wrong places.
     */
    public var duration: Double = duration ?: ad.linear.duration
        private set

    /**
     * How far into the creative the viewer has actually got, in seconds.
     *
     * A high-water mark over *covered* timeline, which is the distinction §3.14.1
     * is really drawing with "played continuously": buffering does not skip any
     * of the creative, whereas seeking does.
     *
     * - A stall leaves the playhead where it was; when it resumes, the next
     *   position is still contiguous, so coverage continues.
     * - A forward seek lands beyond what wall-clock time could account for, so it
     *   never extends coverage — and cannot afterwards, because the mark only
     *   moves within reach of where it already was.
     */
    public var watched: Double = 0.0
        private set

    public var isFinished: Boolean = false
        private set

    private val fired = mutableSetOf<VastTrackingEvent>()
    private val firedProgress = mutableSetOf<Int>()
    private var firedImpression = false
    private var previous: VastTick? = null

    /** True when the caller pinned the duration, in which case reported values are ignored. */
    private val durationIsPinned = duration != null

    // MARK: - Input

    public fun advance(tick: VastTick): List<VastBeacon> {
        if (isFinished) return emptyList()

        // The host replaced the player item mid-ad. Nothing after this point can
        // be attributed to our creative, so stop and report.
        if (!tick.itemIsOurs) return fail(VastError.MEDIA_FILE_DISPLAY_PROBLEM)

        val beacons = mutableListOf<VastBeacon>()
        try {
            val reported = tick.duration
            if (!durationIsPinned && reported != null && reported > 0) {
                duration = reported
            }

            if (tick.rate > 0) {
                beacons += startIfNeeded()
                advanceWatched(tick)
            }

            beacons += quartileBeacons()
            beacons += progressBeacons()

            if (duration > 0 && watched >= duration - SELF_COMPLETION_MARGIN) {
                beacons += finish()
            }
        } finally {
            previous = tick
        }
        return beacons
    }

    /**
     * The creative is ready and playback is about to begin (§3.14.1 `loaded`).
     *
     * Not host-reportable, and not derivable from a tick either — readiness is
     * something only whoever loaded the media knows.
     */
    public fun creativeDidLoad(): List<VastBeacon> {
        if (isFinished) return emptyList()
        return fire(VastTrackingEvent.LOADED)
    }

    /**
     * The player reported that the item played to its end.
     *
     * This is the authoritative end of the creative. Accumulated watch time cannot
     * be used on its own: every buffering stall is excluded from it by design, so
     * on a stuttering connection the threshold is never reached and the ad would
     * hang on its last frame forever.
     *
     * `complete` still requires the creative to have actually been watched
     * through — reaching the end by seeking is not completion (§3.14.1).
     */
    public fun playbackDidReachEnd(): List<VastBeacon> {
        if (isFinished) return emptyList()
        val beacons = progressBeacons().toMutableList()
        if (watched >= duration * COMPLETION_THRESHOLD) {
            beacons += fire(VastTrackingEvent.COMPLETE)
        }
        isFinished = true
        return beacons
    }

    /**
     * Playback stopped advancing and is not going to resume.
     *
     * A creative that stalls *after* being watched through is finished, not
     * broken. Reporting 402 for its last few frames would tell the ad server a
     * delivered impression had failed.
     */
    public fun playbackDidStall(): List<VastBeacon> {
        if (isFinished) return emptyList()
        if (duration > 0 && watched >= duration * COMPLETION_THRESHOLD) {
            return playbackDidReachEnd()
        }
        return fail(VastError.MEDIA_FILE_TIMEOUT)
    }

    public fun userDidSkip(): List<VastBeacon> {
        if (isFinished) return emptyList()
        isFinished = true
        // A skipped ad may still owe progress beacons that its watched time
        // already earned (§3.14.1), so those are emitted before `skip`.
        return progressBeacons() + fire(VastTrackingEvent.SKIP)
    }

    /**
     * Reports a player-operation event the engine cannot observe from ticks —
     * mute, pause, fullscreen and friends (§3.14.1 "Player Operation Metrics").
     *
     * Quartile and lifecycle events are derived from playback instead and are not
     * accepted here, so a host cannot fabricate a billable impression.
     */
    public fun report(event: VastTrackingEvent): List<VastBeacon> {
        if (isFinished || !event.isHostReportable) return emptyList()
        return fire(event)
    }

    public fun fail(error: VastError): List<VastBeacon> {
        if (isFinished) return emptyList()
        isFinished = true
        return ad.errors.map { VastBeacon(VastBeacon.Kind.Error(error), it, ad.id) }
    }

    /** `<CustomClick>` — an interaction the host saw and the engine cannot. */
    public fun reportCustomClick(): List<VastBeacon> {
        if (isFinished) return emptyList()
        return ad.linear.customClicks.map { VastBeacon(VastBeacon.Kind.CustomClick, it, ad.id) }
    }

    // MARK: - Rules

    /**
     * Extends coverage to this tick's position when the position is within reach
     * of the mark — that is, when elapsed wall-clock time at the current rate
     * could plausibly have carried the playhead there.
     *
     * A rewind lands below the mark and leaves it untouched. A forward seek lands
     * above what elapsed time allows and is refused, permanently: the mark can
     * only ever advance from where it already is, so no later tick can smuggle
     * the skipped span back in.
     */
    private fun advanceWatched(tick: VastTick) {
        val previous = previous
        if (previous == null) {
            watched = max(watched, min(tick.adTime, SEEK_TOLERANCE))
            return
        }
        val elapsed = max(0.0, tick.wallClock - previous.wallClock) * tick.rate.toDouble()
        val reachable = watched + elapsed + SEEK_TOLERANCE
        if (tick.adTime > reachable) return
        watched = max(watched, tick.adTime)
    }

    private fun startIfNeeded(): List<VastBeacon> {
        val beacons = mutableListOf<VastBeacon>()
        if (!firedImpression) {
            firedImpression = true
            beacons += ad.impressions.map { VastBeacon(VastBeacon.Kind.Impression, it, ad.id) }
            // Reported with the impression, not later: the vendor is deciding right
            // now whether this session counts as measured.
            beacons += verificationsNotExecuted()
            beacons += viewabilityUndetermined()
            beacons += fire(VastTrackingEvent.CREATIVE_VIEW)
        }
        beacons += fire(VastTrackingEvent.START)
        return beacons
    }

    /**
     * One beacon per tracker of every vendor whose code will not run.
     *
     * Reason 2 (`RESOURCE_LOAD_ERROR`) never originates here — only whoever tried
     * to load a resource can report that, and by definition nothing tried.
     */
    private fun verificationsNotExecuted(): List<VastBeacon> {
        if (measurementWillRun) return emptyList()
        return ad.adVerifications.flatMap { verification ->
            val reason = if (verification.omidResource == null) {
                VastVerification.NotExecutedReason.RESOURCE_NOT_SUPPORTED
            } else {
                VastVerification.NotExecutedReason.NOT_EXECUTED
            }
            verification.notExecutedTrackers.map { VastBeacon(VastBeacon.Kind.VerificationNotExecuted(reason), it, ad.id) }
        }
    }

    /**
     * The only viewability outcome this player can honestly claim.
     *
     * §3.6 offers three, and a player with no viewability measurement can report
     * exactly one of them. Sending nothing at all is the tempting option and the
     * wrong one: an unmeasured impression left silent is counted as measured by
     * whoever asked.
     */
    private fun viewabilityUndetermined(): List<VastBeacon> {
        if (measurementWillRun) return emptyList()
        val asked = ad.viewableImpression ?: return emptyList()
        return asked.viewUndetermined.map { VastBeacon(VastBeacon.Kind.ViewUndetermined, it, ad.id) }
    }

    private fun quartileBeacons(): List<VastBeacon> {
        if (duration <= 0) return emptyList()
        val progress = watched / duration
        val beacons = mutableListOf<VastBeacon>()
        if (progress >= 0.25) beacons += fire(VastTrackingEvent.FIRST_QUARTILE)
        if (progress >= 0.50) beacons += fire(VastTrackingEvent.MIDPOINT)
        if (progress >= 0.75) beacons += fire(VastTrackingEvent.THIRD_QUARTILE)
        return beacons
    }

    private fun progressBeacons(): List<VastBeacon> {
        val beacons = mutableListOf<VastBeacon>()
        ad.linear.progressEvents.forEachIndexed { index, event ->
            val offset = event.offset.seconds(duration)
            if (watched < offset || index in firedProgress) return@forEachIndexed
            firedProgress += index
            beacons += VastBeacon(VastBeacon.Kind.Progress(offset), event.url, ad.id)
        }
        return beacons
    }

    private fun finish(): List<VastBeacon> {
        isFinished = true
        return fire(VastTrackingEvent.COMPLETE)
    }

    /** Emits an event's URIs at most once when the event is once-only. */
    private fun fire(event: VastTrackingEvent): List<VastBeacon> {
        if (event.isOnce && !fired.add(event)) return emptyList()
        return event.equivalents
            .flatMap { ad.linear.trackingEvents[it].orEmpty() }
            .map { VastBeacon(VastBeacon.Kind.Tracking(event), it, ad.id) }
    }

    public companion object {
        /**
         * A jump larger than this, relative to what elapsed wall-clock allows, is
         * treated as a seek rather than playback.
         */
        public const val SEEK_TOLERANCE: Double = 0.75

        /**
         * Fraction of the creative that counts as "played to the end". Declared
         * `<Duration>` and real media length routinely differ by a fraction of a
         * second, and the last tick rarely lands exactly on the final frame.
         */
        public const val COMPLETION_THRESHOLD: Double = 0.97

        /**
         * How close to the end, in seconds, the engine will call the creative
         * finished *by itself*, with no word from the player.
         *
         * A margin rather than a fraction, because the gap that matters is one
         * tick and a tick is a fixed 200ms whether the creative runs ten seconds
         * or sixty. A ratio held the wrong quantity constant: 0.97 stopped a
         * thirty-second ad with nearly a second unplayed — usually where the logo
         * is. [COMPLETION_THRESHOLD] asks a different question, whether enough of
         * the creative was *watched*, and keeps its own value.
         */
        public const val SELF_COMPLETION_MARGIN: Double = 0.25
    }
}

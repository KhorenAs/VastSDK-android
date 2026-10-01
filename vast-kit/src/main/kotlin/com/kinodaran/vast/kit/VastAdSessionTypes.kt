package com.kinodaran.vast.kit

import com.kinodaran.vast.core.VastAd
import com.kinodaran.vast.core.VastBeacon
import com.kinodaran.vast.core.VastError
import com.kinodaran.vast.core.VastMacroValues
import com.kinodaran.vast.core.VastResourceLoader

/** Where a session is, as the host's UI reads it. */
public sealed interface VastAdState {
    public data object Idle : VastAdState

    /** Resolving the response, or waiting for a creative to start. */
    public data object Loading : VastAdState

    public data object Playing : VastAdState

    public data object Paused : VastAdState

    public data class Finished(val outcome: VastAdOutcome) : VastAdState
}

public sealed interface VastAdOutcome {
    public data object Completed : VastAdOutcome

    public data object Skipped : VastAdOutcome

    public data class Failed(val error: VastError) : VastAdOutcome
}

/** Position within the pod, 1-based. `(1, 1)` for a single ad. */
public data class VastAdPosition(val index: Int, val total: Int) {
    val isPod: Boolean get() = total > 1

    public companion object {
        public val SINGLE: VastAdPosition = VastAdPosition(1, 1)
    }
}

/**
 * Who provides the skip control.
 *
 * Not a styling choice. VAST 4.3 §2.3 states the player should not "play the
 * Skippable Ad as a Linear Ad (without skip controls)", so this decides whether a
 * skippable ad may be played at all — an advertiser paid the skippable rate and
 * is owed the skip.
 */
public enum class VastSkipPresentation {
    /** The SDK draws the control. Compliant by construction. */
    SDK,

    /** The host draws it, reading `canSkip` and `timeUntilSkip`. */
    HOST,

    /** Skippable ads are refused with VAST error 200 rather than played without a skip control. */
    UNSUPPORTED,
}

/**
 * How a click reaches the advertiser.
 *
 * §3.10.1 makes `ClickThrough` support Required, and describes it as the URI
 * "the media player opens when a viewer clicks the ad" — the ad surface itself,
 * not necessarily a separate button.
 */
public enum class VastClickPresentation {
    /** Tapping the ad opens the destination. The spec's own model. */
    SURFACE,

    /** The host wires its own affordance and calls `click()`. */
    HOST,

    /** No click path at all. Not compliant; opt in knowingly. */
    DISABLED,
}

/**
 * Whether an ad may play in the Picture in Picture window.
 *
 * The window shows the player and the system's media controls; the ad surface —
 * skip control, badge, click layer — stays behind in the app. What that costs
 * is smaller than it first looks: tapping the window returns to the app, where
 * the whole surface is where it was. The skip control is a tap further away, not
 * gone. So the default is to leave the window alone; the other two are for
 * players that must keep an ad where its controls are.
 */
public enum class VastPictureInPicturePolicy {
    /**
     * The ad plays in the window, like the content it interrupted. The listener
     * hears `onSkipControlUnavailable` when a skip control comes due while the
     * viewer is out there and cannot see it.
     */
    ALLOWED,

    /** Entering the window pauses the ad — reported as §3.14.1 `pause` — and leaving it resumes. */
    PAUSES_AD,

    /**
     * The ad does not play in the window: entering it holds the ad, unreported,
     * until the viewer is back, and `permitsPictureInPicture` is false for the
     * length of the break so the host can turn automatic entry off.
     */
    SUSPENDED,
}

/** Why [VastAdSession.skip] refused. It never refuses silently: an unhonoured skip violates §2.3. */
public sealed class VastSkipException(message: String) : Exception(message) {
    public class NoActiveAd : VastSkipException("no ad is playing")

    public class NotSkippable : VastSkipException("the ad on screen declares no skipoffset")

    public class NotYetAvailable(public val afterSeconds: Double) :
        VastSkipException("skip unlocks in $afterSeconds s")
}

/**
 * Everything a host may swap out. Supplying fakes lets the whole session run
 * against a scripted clock, transport and loader.
 *
 * The defaults are the compliant ones: the SDK draws the skip control and the
 * ad surface is clickable, so a host that configures nothing still honours §2.3
 * and §3.10.1.
 */
public data class VastConfiguration(
    /** VAST 4.3 §3.19.1 requires accepting at least five. */
    val maxWrapperDepth: Int = 5,
    val wrapperTimeoutSeconds: Double = 5.0,
    /**
     * Total time a response may take to resolve, however many Wrappers it needs.
     * `wrapperTimeoutSeconds` bounds one hop, and five hops at five seconds is
     * twenty-five — a viewer who waited that long for an ad was shown a bug, not
     * an ad. While it runs the content waits, because the break is a pre-roll.
     * Zero disables the budget.
     */
    val resolutionTimeoutSeconds: Double = 10.0,
    val skipPresentation: VastSkipPresentation = VastSkipPresentation.SDK,
    val clickPresentation: VastClickPresentation = VastClickPresentation.SURFACE,
    /** Whether the ad may follow the content into the Picture in Picture window. */
    val pictureInPicture: VastPictureInPicturePolicy = VastPictureInPicturePolicy.ALLOWED,
    /** What the system's media controls show and allow during a break; see [VastAdSession.forMediaSession]. */
    val nowPlaying: VastNowPlayingPolicy = VastNowPlayingPolicy.DESCRIBES_AD,
    /** `null` uses the built-in clock, which samples the player every 200 ms. */
    val clock: VastClock? = null,
    /** `null` uses [VastHttpTransport] with the shared retry queue. */
    val transport: VastBeaconTransport? = null,
    /** `null` uses [VastHttpLoader]. */
    val loader: VastResourceLoader? = null,
    /**
     * §6 macro values only the host can supply — identifier, consent, where this
     * break sits in its content. Unsupplied values report `-1`, which is honest
     * and also often unbiddable; see [VastMacroValues].
     */
    val macroValues: VastMacroValues = VastMacroValues(),
)

/**
 * What the session did, for a host that wants to know without observing the
 * state flows. Called on the main thread. Every method has a default, so a host
 * implements only what it reads.
 */
public interface VastAdSessionListener {

    public fun onAdStarted(ad: VastAd, position: VastAdPosition) {}

    /**
     * Playback position, once per tick. `duration` is what the player reports,
     * falling back to `<Duration>` until it does — the declared value is advisory
     * and the two often disagree.
     */
    public fun onAdProgress(ad: VastAd, timeSeconds: Double, durationSeconds: Double) {}

    /** `skipoffset` elapsed — a host drawing its own control draws it now. */
    public fun onSkipAvailable(ad: VastAd) {}

    public fun onAdFinished(ad: VastAd, outcome: VastAdOutcome) {}

    /**
     * One ad failed, or the response produced none (`ad` is then `null`). A pod
     * continues with the next ad or a stand-alone substitute, and the content
     * plays whatever happens.
     */
    public fun onAdFailed(error: VastError, ad: VastAd?) {}

    public fun onAllAdsFinished() {}

    /**
     * The SDK owns the skip control for this ad but cannot show it: the surface is
     * missing or too small, or the control itself drew at no usable size.
     */
    public fun onSkipControlUnavailable(ad: VastAd, reason: String) {}

    /** The ad declares a ClickThrough that no viewer can reach — on a TV left on `SURFACE`, say. */
    public fun onClickThroughUnavailable(ad: VastAd, reason: String) {}

    /**
     * The session reported something to the ad server.
     *
     * Called once per distinct kind, in the order the beacons went out, and whether
     * or not the network accepted them — what is reported is the event, not the
     * delivery. A response carrying three `<Impression>` URLs is three beacons and
     * one impression, so this fires once for it.
     *
     * Tracking is the one thing a host cannot observe for itself: the beacons live
     * inside the response and go out from here. This is a mirror, not a hook, and
     * nothing is asked of the listener.
     */
    public fun onReported(kind: VastBeacon.Kind, ad: VastAd?) {}

    /**
     * The activity entered or left Picture in Picture. Reported for the whole
     * session, not only during a break: a host that hides its own controls for the
     * window needs to know either way.
     */
    public fun onPictureInPictureChanged(isActive: Boolean) {}
}

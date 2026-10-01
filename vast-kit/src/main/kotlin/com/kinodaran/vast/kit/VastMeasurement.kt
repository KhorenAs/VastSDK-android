package com.kinodaran.vast.kit

import android.view.View
import com.kinodaran.vast.core.VastAd
import com.kinodaran.vast.core.VastError
import com.kinodaran.vast.core.VastTrackingEvent
import com.kinodaran.vast.core.VastVerification

/**
 * Where third-party measurement plugs in.
 *
 * The SDK parses `<AdVerifications>` and never executes it: running a vendor's
 * code means the IAB Open Measurement SDK, which is licensed separately, ships
 * as its own binary, and brings a WebView with it. None of that belongs in this
 * library — so this interface is the seam, and an implementation lives outside.
 *
 * Supplying one changes what the SDK reports. With no measurement configured,
 * every vendor is told `verificationNotExecuted`, because that is the truth. With
 * one configured the SDK goes quiet and the implementation owns the reporting,
 * including telling the session when a resource failed to load —
 * `RESOURCE_LOAD_ERROR` can only be known by whoever tried.
 *
 * Called on the main thread.
 */
public interface VastMeasurement {

    /** One ad is about to be shown. Called before the impression. */
    public fun begin(context: VastMeasurementContext)

    /** Playback reached something a measurement vendor cares about. */
    public fun record(event: VastMeasurementEvent)

    /** The ad is over, by any route. Called exactly once per [begin]. */
    public fun finish()

    /**
     * The OMID partner name and version, as the vendor's script sees it, for the
     * `[OMIDPARTNER]` macro. `null` reports it as unknown — honest for an
     * integration that is not OMID.
     */
    public val omidPartner: String? get() = null
}

/** What an implementation needs to open a measurement session. */
public class VastMeasurementContext(
    public val ad: VastAd,
    /** The view the creative is shown in, when the SDK knows it. */
    public val adView: View?,
) {
    public val verifications: List<VastVerification> get() = ad.adVerifications
}

/**
 * Modelled as one sealed type rather than a method per event so that the
 * *sequence* can be asserted in a test. Measurement integrations fail on order
 * far more often than on any single call.
 */
public sealed interface VastMeasurementEvent {
    public data object Impression : VastMeasurementEvent

    public data class Start(val duration: Double, val isMuted: Boolean) : VastMeasurementEvent

    /** `FIRST_QUARTILE`, `MIDPOINT`, `THIRD_QUARTILE` or `COMPLETE`. */
    public data class Quartile(val event: VastTrackingEvent) : VastMeasurementEvent

    public data class Progress(val offset: Double) : VastMeasurementEvent

    public data object Pause : VastMeasurementEvent

    public data object Resume : VastMeasurementEvent

    public data object Skipped : VastMeasurementEvent

    public data object Clicked : VastMeasurementEvent

    public data class Failed(val error: VastError) : VastMeasurementEvent
}

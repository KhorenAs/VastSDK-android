package com.kinodaran.vast.core

/**
 * A tracking request the engine decided should be sent.
 *
 * The engine returns these rather than performing IO, so tests assert on plain
 * values instead of mocking a network layer.
 */
public data class VastBeacon(
    val kind: Kind,
    val url: String,
    val adId: String,
) {
    public sealed interface Kind {
        public data object Impression : Kind

        public data class Tracking(val event: VastTrackingEvent) : Kind

        public data class Progress(val offset: Double) : Kind

        public data object ClickTracking : Kind

        public data class Error(val error: VastError) : Kind

        /**
         * A verification vendor asked to observe this ad and nothing ran its code
         * (§3.16). Silence would let the vendor count the session as measured, so
         * the reason is reported rather than withheld.
         */
        public data class VerificationNotExecuted(val reason: VastVerification.NotExecutedReason) : Kind

        /**
         * `<ViewUndetermined>` (§3.6): the response asked about viewability and
         * this player cannot measure it. The same reasoning as above — silence
         * would let a buyer treat an unmeasured impression as a measured one.
         */
        public data object ViewUndetermined : Kind

        /**
         * `<CustomClick>` (§3.10.3): an interaction the host reported that opens
         * nothing, as distinct from a click-through.
         */
        public data object CustomClick : Kind
    }
}

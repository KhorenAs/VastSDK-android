package com.kinodaran.vast.core

/** One parsed `<VAST>` response, before Wrapper flattening. */
public data class VastDocument(
    val version: String,
    val entries: List<Entry>,
    /** Root-level `<Error>`, used for the "no ad" response (§2.3.6.4). */
    val noAdError: String?,
) {

    val isNoAd: Boolean get() = entries.isEmpty()

    public data class Entry(
        val id: String,
        val sequence: Int?,
        val body: Body,
    )

    public sealed interface Body {
        public data class InLine(val ad: VastAd) : Body

        /** `<Wrapper>` — carries its own trackers plus the next tag to fetch. */
        public data class Wrapper(val wrapper: VastDocument.Wrapper) : Body

        /**
         * An `<InLine>` whose only creatives are ones this SDK does not play —
         * `<NonLinearAds>`, `<CompanionAds>`. Kept rather than dropped so the
         * server hears 201 ("expecting different linearity") instead of being told
         * it returned nothing, and so its `<Error>` URIs still fire.
         */
        public data class UnplayableCreative(val errors: List<String>) : Body
    }

    public data class Wrapper(
        val tagUri: String,
        val impressions: List<String>,
        val errors: List<String>,
        val trackingEvents: Map<VastTrackingEvent, List<String>>,
        val clickTracking: List<String>,
        val clickThrough: String?,
        val extensions: List<VastAd.Extension>,
        /**
         * Verification vendors are usually injected by an intermediary rather than
         * the advertiser, so a Wrapper carrying them is the common case.
         */
        val verifications: List<VastVerification>,
        /**
         * A Wrapper may ask about viewability on its own account (§3.6), and an
         * intermediary that wanted to hear does not stop wanting because the
         * InLine wanted to as well.
         */
        val viewableImpression: VastAd.ViewableImpression?,
        /** Default `true` (§3.19). */
        val followAdditionalWrappers: Boolean,
        /** Default `false` (§3.19). */
        val allowMultipleAds: Boolean,
        val fallbackOnNoAd: Boolean?,
    )
}

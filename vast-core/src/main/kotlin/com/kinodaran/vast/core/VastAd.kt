package com.kinodaran.vast.core

/**
 * A single `<Ad>` resolved down to everything needed to play and track it.
 *
 * A Wrapper chain is already flattened by the time an ad reaches this type:
 * `trackingEvents`, `impressions` and `errors` hold the accumulated URIs from
 * every Wrapper in the chain plus the final InLine, as VAST 4.3 §2.3.5 requires.
 *
 * URIs are kept as strings, normalised the way the Apple SDK's `URL(string:)`
 * normalises them: characters that are not legal in a URI — brackets around a
 * macro among them — arrive percent-encoded. [VastMacroExpander] reads both forms.
 */
public data class VastAd(
    val id: String,
    /** `<Ad sequence="n">`. `null` means a stand-alone ad (VAST 4.3 §3.3.1 "ad buffet"). */
    val sequence: Int? = null,
    val adSystem: String? = null,
    val title: String? = null,
    val linear: Linear,
    /** Fired together, the moment the first frame renders (§2.3.4). */
    val impressions: List<String> = emptyList(),
    /** Every `<Error>` in the chain. On failure ALL of them fire (§2.3.5.1). */
    val errors: List<String> = emptyList(),
    /** Vendor-specific `<Extensions>`, handed to the host untouched (§3.18). */
    val extensions: List<Extension> = emptyList(),
    /**
     * `<AdVerifications>` (§3.16), accumulated from the whole Wrapper chain and
     * normalised across VAST 3 and 4 shapes. Parsed, never executed — see [VastVerification].
     */
    val adVerifications: List<VastVerification> = emptyList(),
    /**
     * The `id` of every Wrapper crossed to reach this ad, outermost first.
     *
     * Empty for a direct InLine response. Reporting pipelines use this to say
     * which intermediary served an ad, so it is kept rather than discarded with
     * the rest of the Wrapper once the chain is flattened.
     */
    val wrapperAdIds: List<String> = emptyList(),
    /**
     * `<AdServingId>` (§3.4). Required from VAST 4.1.
     *
     * The value both sides quote when their counts disagree: unique to this
     * response, from this server, for this request.
     */
    val adServingId: String? = null,
    /**
     * `<UniversalAdId>` (§3.7.1). Required in VAST 4 — one per `<Creative>`, and
     * the only identifier that means the same thing to both sides.
     */
    val universalAdIds: List<UniversalAdId> = emptyList(),
    /** `<ViewableImpression>` (§3.6), if the response asked to hear about viewability. */
    val viewableImpression: ViewableImpression? = null,
    /**
     * `<Icon>` (§3.15) — AdChoices, in practice. Parsed and handed over; drawing
     * it belongs to whoever owns the ad UI.
     */
    val icons: List<Icon> = emptyList(),
    /** `<Advertiser>` (§3.9) — who the ad is for. */
    val advertiser: String? = null,
    /** `<Pricing>` (§3.8) — what the impression cost, as the server states it. */
    val pricing: Pricing? = null,
    /** `<Category>` (§3.5) — the advertiser's industry. */
    val categories: List<Category> = emptyList(),
    /**
     * `<Expires>` (§3.4) — how long this response may be held before it stops
     * being worth playing, in seconds. Only meaningful to a host that caches
     * responses; this SDK plays them as they arrive.
     */
    val expires: Double? = null,
) {

    /**
     * Whether this is a Skippable Linear Ad: the creative declared a
     * `skipoffset` (§2.3).
     *
     * Not a styling question. An advertiser paid the skippable rate on the
     * strength of that attribute, so this is what decides whether a skip control
     * is owed at all.
     */
    val isSkippable: Boolean get() = linear.skipOffset != null

    /**
     * Whether the response placed this ad in a pod, by giving it a `sequence`
     * attribute (§3.3.1).
     *
     * A fact about what the ad server trafficked, not about the break being
     * played: a lone `sequence="1"` ad *is* part of a pod while its break has one
     * slot, and a stand-alone ad substituted for a failed pod member is *not*
     * while the break around it still has all of its slots.
     */
    val isPartOfPod: Boolean get() = sequence != null

    /**
     * Whether this response asks the player to draw no UI of its own.
     *
     * Read from `<Extension type="uiSettings">` — `<UiHidden>`, or the
     * `<UiHideable>` spelling the live AdFox tag sends. A vendor convention
     * rather than VAST, and the one key the SDK interprets instead of merely
     * handing over. Presence is the request, so `<UiHidden/>` with no text
     * counts; only an explicitly negative value reads as "keep your UI".
     *
     * **This is what the response asked for, and on its own it changes nothing.**
     * A response cannot hide the SDK's UI by itself — that would let a vendor
     * decide whether the host honours §2.3. The host has to allow it too.
     */
    val isUiHidden: Boolean get() = VastUiSettings.asksForHostDrawnUi(this)

    /** `<Linear>` — the only creative type this SDK plays. */
    public data class Linear(
        val duration: Double,
        /**
         * `<Linear skipoffset>`. Non-null means the ad is a Skippable Linear Ad
         * and MUST be offered with skip controls (§2.3).
         */
        val skipOffset: SkipOffset? = null,
        val mediaFiles: List<MediaFile> = emptyList(),
        val clickThrough: String? = null,
        val clickTracking: List<String> = emptyList(),
        /**
         * `<CustomClick>` (§3.10.3) — trackers for a click the player reports
         * without opening anything, as distinct from `<ClickTracking>`, which
         * accompanies a click-through. Real tags send both.
         */
        val customClicks: List<String> = emptyList(),
        val trackingEvents: Map<VastTrackingEvent, List<String>> = emptyMap(),
        /**
         * `<Tracking event="progress" offset="...">` — decides whether a skipped
         * ad still counts as viewed (§3.14.1).
         */
        val progressEvents: List<ProgressEvent> = emptyList(),
    ) {
        public fun resolvedSkipOffset(): Double? = skipOffset?.seconds(duration)
    }

    /** `HH:MM:SS`, `HH:MM:SS.mmm`, or `n%`. */
    public sealed interface SkipOffset {
        public fun seconds(forDuration: Double): Double

        public data class Time(val seconds: Double) : SkipOffset {
            override fun seconds(forDuration: Double): Double = seconds
        }

        public data class Percent(val percent: Double) : SkipOffset {
            override fun seconds(forDuration: Double): Double = forDuration * percent / 100
        }
    }

    public data class ProgressEvent(val offset: SkipOffset, val url: String)

    /**
     * One `<Extension>` kept as raw XML. The SDK never interprets these; a host
     * reads its own vendor keys (e.g. Kinodaran's `uiSettings/UiHideable`).
     */
    public data class Extension(val type: String?, val xml: String) {

        /**
         * The text of a direct child element, e.g. `value("UiHideable")` on a
         * `uiSettings` extension.
         *
         * Reading a vendor key should not mean writing a string scanner in every
         * host. This still assigns the value no meaning — whether `"1"` means the
         * UI should hide is the vendor's convention, and the host's to apply.
         *
         * `null` when the element is absent; an element present but empty yields
         * `""`, which is a different fact and is reported as such.
         */
        public fun value(element: String): String? {
            val openStart = xml.indexOf("<$element")
            if (openStart < 0) return null
            val nameEnd = openStart + element.length + 1
            val openEnd = xml.indexOf('>', nameEnd)
            if (openEnd < 0) return null

            // `<UiHideable/>` carries no text, and its ">" belongs to the open tag.
            if (xml.substring(nameEnd, openEnd).endsWith("/")) return ""

            val bodyStart = openEnd + 1
            val closeStart = xml.indexOf("</$element>", bodyStart)
            if (closeStart < 0) return null

            return text(xml.substring(bodyStart, closeStart))
        }

        /** Ad servers wrap extension values in CDATA about as often as not. */
        private fun text(body: String): String {
            val trimmed = body.trim()
            if (!trimmed.startsWith("<![CDATA[") || !trimmed.endsWith("]]>")) return trimmed
            return trimmed.substring(9, trimmed.length - 3).trim()
        }
    }

    public data class MediaFile(
        val id: String? = null,
        val url: String,
        val mimeType: String,
        val delivery: Delivery = Delivery.PROGRESSIVE,
        val width: Int? = null,
        val height: Int? = null,
        val bitrate: Int? = null,
        val minBitrate: Int? = null,
        val maxBitrate: Int? = null,
        val scalable: Boolean = true,
        val maintainAspectRatio: Boolean = true,
        val codec: String? = null,
    )

    public enum class Delivery(public val vastName: String) {
        PROGRESSIVE("progressive"),
        STREAMING("streaming"),
        ;

        public companion object {
            public fun fromVastName(name: String): Delivery? = entries.firstOrNull { it.vastName == name }
        }
    }

    /**
     * `<UniversalAdId>` (§3.7.1). Required in VAST 4.
     *
     * The one identifier that means the same thing to everyone: an ad server's
     * own `<Ad id>` is its own, while this names the *creative* in a registry
     * both sides can look up.
     */
    public data class UniversalAdId(
        /** `idRegistry` — who issued the value. `"unknown"` when a server says so. */
        val registry: String?,
        val value: String,
    )

    /**
     * `<ViewableImpression>` (§3.6).
     *
     * Three outcomes, not one: the ad was viewable, it was not, or the player
     * could not tell. The third is the honest answer for a player with no
     * viewability measurement, and it is the one this SDK sends.
     */
    public data class ViewableImpression(
        val id: String? = null,
        val viewable: List<String> = emptyList(),
        val notViewable: List<String> = emptyList(),
        val viewUndetermined: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = viewable.isEmpty() && notViewable.isEmpty() && viewUndetermined.isEmpty()
    }

    /**
     * `<Icon>` (§3.15) — the AdChoices mark, in practice.
     *
     * Parsed and handed over rather than drawn. What matters is that the
     * response's icon is not silently dropped: §3.15 expects the player to
     * display one, and a response carrying `program="AdChoices"` means somebody
     * upstream undertook to show it.
     */
    public data class Icon(
        /** `program` — which scheme the icon belongs to, e.g. `AdChoices`. */
        val program: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        /** `left`, `right`, or a number of pixels. */
        val xPosition: String? = null,
        /** `top`, `bottom`, or a number of pixels. */
        val yPosition: String? = null,
        /** When the icon should appear, from the start of the creative. */
        val offset: Double? = null,
        /** How long it should stay. `null` means for the rest of the ad. */
        val duration: Double? = null,
        val staticResource: String? = null,
        val staticResourceType: String? = null,
        val clickThrough: String? = null,
        val clickTracking: List<String> = emptyList(),
        val viewTracking: List<String> = emptyList(),
    )

    /** `<Pricing>` (§3.8). What the impression cost, as the server states it. */
    public data class Pricing(
        /** `CPM`, `CPC`, `CPE`, `CPV`. */
        val model: String?,
        /** ISO 4217, e.g. `USD`. */
        val currency: String?,
        val value: Double,
    )

    /** `<Category>` (§3.5) — the advertiser's industry, per some authority. */
    public data class Category(
        /** `authority` — the URL of the taxonomy the code belongs to. */
        val authority: String?,
        val code: String,
    )
}

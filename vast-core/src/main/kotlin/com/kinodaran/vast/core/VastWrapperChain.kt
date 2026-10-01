package com.kinodaran.vast.core

import java.net.URI

/**
 * Fetches a VAST document. Lives in `vast-core` rather than `vast-kit` because
 * wrapper resolution is pure logic that only needs "give me the XML at this
 * URL" — keeping the interface here lets the whole chain be tested with a map
 * of fixtures instead of a network.
 */
public interface VastResourceLoader {
    /**
     * Any failure — a timeout, a dead host, a non-2xx response — is reported by
     * the resolver as 301, so the loader only has to fail, not classify.
     */
    public suspend fun loadVast(url: String, timeoutSeconds: Double): String
}

/**
 * Drives Wrapper redirection without performing any IO.
 *
 * The caller executes [Step.Follow] with its own loader and feeds the result
 * back in. Depth limits, tracker accumulation, ClickThrough precedence and error
 * reporting are therefore exercised with fixture strings and no network.
 */
public class VastWrapperChain(
    /** §3.19.1: "the player is only required to accept five wrappers". */
    public val maxDepth: Int = 5,
) {

    public sealed interface Step {
        /** Fetch this tag and call [accept] again with the parsed result. */
        public data class Follow(val url: String) : Step

        /** The chain ended at an InLine response; these ads carry every tracker collected on the way down. */
        public data class Resolved(val ads: List<VastAd>) : Step

        public data class Failed(val error: VastError) : Step
    }

    private val errors = mutableListOf<String>()

    /**
     * Every `<Error>` seen on the way down. On failure they all fire, because
     * §2.3.5.1 requires each Wrapper in the chain to be told.
     */
    public val accumulatedErrors: List<String> get() = errors.toList()

    public var depth: Int = 0
        private set

    private val impressions = mutableListOf<String>()
    private val tracking = LinkedHashMap<VastTrackingEvent, MutableList<String>>()
    private val clickTracking = mutableListOf<String>()

    /**
     * §2.3.5.2: the ClickThrough closest to the InLine response wins, so this is
     * overwritten as the chain descends and by the InLine itself if it has one.
     */
    private var clickThrough: String? = null
    private val extensions = mutableListOf<VastAd.Extension>()
    private val verifications = mutableListOf<VastVerification>()

    /** `<Ad id>` of each Wrapper crossed, in the order they were crossed. */
    private val wrapperAdIds = mutableListOf<String>()

    /** Viewability URIs the Wrappers asked for, kept alongside the InLine's. */
    private var viewableImpression: VastAd.ViewableImpression? = null
    private var allowMultipleAds = false

    /**
     * Consumes one parsed document.
     *
     * @param baseUrl where this document was fetched from. A `VASTAdTagURI` is
     *   frequently relative, and only the caller knows what to resolve it against.
     */
    public fun accept(document: VastDocument, baseUrl: String? = null): Step {
        if (document.isNoAd) {
            // §2.3.6.4 names 303 for the no-ad response, and it is the honest code
            // at either depth: no VAST response produced an ad. Reporting a wrapper
            // error for a direct response describes a hop that never happened.
            document.noAdError?.let { errors += it }
            return Step.Failed(VastError.NO_VAST_RESPONSE_AFTER_WRAPPERS)
        }

        val inLineAds = document.entries.mapNotNull { (it.body as? VastDocument.Body.InLine)?.ad }
        if (inLineAds.isNotEmpty()) {
            return Step.Resolved(permitted(inLineAds).map(::merge))
        }

        // No InLine yet, so this is another Wrapper hop.
        val entry = document.entries.firstOrNull { it.body is VastDocument.Body.Wrapper }
        if (entry == null) {
            // Unless the response did contain an ad, just not one with a Linear
            // creative. The server filled the slot; the player wanted a different
            // linearity, and §2.3.6 gives that its own code.
            val unplayable = document.entries.firstNotNullOfOrNull { it.body as? VastDocument.Body.UnplayableCreative }
            if (unplayable != null) {
                errors += unplayable.errors
                return Step.Failed(VastError.UNEXPECTED_LINEARITY)
            }
            return Step.Failed(VastError.WRAPPER_GENERAL)
        }
        val wrapper = (entry.body as VastDocument.Body.Wrapper).wrapper
        absorb(wrapper, entry.id)

        if (depth >= maxDepth) {
            return Step.Failed(VastError.WRAPPER_LIMIT_REACHED)
        }
        if (!wrapper.followAdditionalWrappers && depth != 0) {
            // The parent forbade further redirection, and this response is not an
            // InLine, so there is nothing left to play.
            return Step.Failed(VastError.NO_VAST_RESPONSE_AFTER_WRAPPERS)
        }
        depth += 1
        return Step.Follow(resolve(wrapper.tagUri, baseUrl))
    }

    /**
     * The error beacons owed when the chain fails. Use when the caller's own step
     * fails — a fetch timeout, or a document that will not parse.
     */
    public fun errorBeacons(error: VastError, adId: String = "-"): List<VastBeacon> =
        errors.map { VastBeacon(VastBeacon.Kind.Error(error), it, adId) }

    /**
     * Applies the calling Wrapper's `allowMultipleAds` constraint (§3.19).
     *
     * The attribute defaults to `false`, which means "only the first stand-alone
     * Ad (with no sequence values) in the requested VAST response is allowed" —
     * so a wrapper that says nothing is asking for one ad, not a pod.
     *
     * A direct response is untouched: the constraint belongs to a wrapper
     * requesting on someone's behalf, and there is no wrapper at depth zero.
     */
    private fun permitted(ads: List<VastAd>): List<VastAd> {
        if (depth == 0 || allowMultipleAds || ads.size <= 1) return ads

        // Read literally, a pod-only response under this constraint contains
        // nothing that is allowed. Taking its first ad instead keeps the slot
        // filled while still honouring "not multiple" — refusing fill outright
        // would punish the advertiser for the wrapper's default.
        return listOf(ads.firstOrNull { it.sequence == null } ?: ads[0])
    }

    // MARK: - Accumulation

    /**
     * Verifications accumulate like trackers, with one difference that matters: a
     * duplicate tracker is a harmless second request, while a duplicate
     * verification is a second measurement session for a vendor that asked for
     * one. The first mention of a (vendor, resources) pair wins.
     */
    private fun absorbVerifications(incoming: List<VastVerification>) {
        for (verification in incoming) {
            if (verifications.none { it.isSameAs(verification) }) verifications += verification
        }
    }

    private fun absorbViewableImpression(incoming: VastAd.ViewableImpression?) {
        incoming ?: return
        val existing = viewableImpression
        viewableImpression = VastAd.ViewableImpression(
            id = existing?.id ?: incoming.id,
            viewable = existing?.viewable.orEmpty() + incoming.viewable,
            notViewable = existing?.notViewable.orEmpty() + incoming.notViewable,
            viewUndetermined = existing?.viewUndetermined.orEmpty() + incoming.viewUndetermined,
        )
    }

    private fun absorb(wrapper: VastDocument.Wrapper, adId: String) {
        impressions += wrapper.impressions
        errors += wrapper.errors
        absorbViewableImpression(wrapper.viewableImpression)
        clickTracking += wrapper.clickTracking
        extensions += wrapper.extensions
        absorbVerifications(wrapper.verifications)
        // An ad server that omits `<Ad id>` on a Wrapper leaves nothing to report,
        // and an empty string in the chain would read as a real intermediary.
        if (adId.isNotEmpty()) wrapperAdIds += adId
        allowMultipleAds = wrapper.allowMultipleAds
        for ((event, urls) in wrapper.trackingEvents) {
            tracking.getOrPut(event) { mutableListOf() } += urls
        }
        wrapper.clickThrough?.let { clickThrough = it }
    }

    /**
     * Folds everything collected on the way down into the resolved ad.
     *
     * A pod resolved through a Wrapper gets the wrapper's trackers on *every*
     * member (§3.3.1), which falls out of merging per ad rather than once.
     */
    private fun merge(ad: VastAd): VastAd {
        val merged = LinkedHashMap<VastTrackingEvent, List<String>>(ad.linear.trackingEvents)
        for ((event, urls) in tracking) {
            merged[event] = merged[event].orEmpty() + urls
        }
        // `copy` carries every field the chain does not touch — adServingId,
        // universalAdIds, icons, pricing and the rest — so none of them can be
        // dropped for the responses that came through a Wrapper, which is most.
        return ad.copy(
            linear = ad.linear.copy(
                // The InLine's own ClickThrough is closest to the creative and
                // therefore wins over anything a calling Wrapper supplied.
                clickThrough = ad.linear.clickThrough ?: clickThrough,
                clickTracking = ad.linear.clickTracking + clickTracking,
                trackingEvents = merged,
            ),
            impressions = ad.impressions + impressions,
            errors = ad.errors + errors,
            extensions = ad.extensions + extensions,
            adVerifications = mergedVerifications(ad),
            wrapperAdIds = wrapperAdIds.toList(),
            viewableImpression = mergedViewableImpression(ad),
        )
    }

    /**
     * A Wrapper may ask about viewability too (§3.6), and both are owed an answer:
     * an intermediary that wanted to hear about it does not stop wanting because
     * the InLine wanted to as well.
     */
    private fun mergedViewableImpression(ad: VastAd): VastAd.ViewableImpression? {
        val chain = viewableImpression
        val inLine = ad.viewableImpression
        if (chain == null && inLine == null) return null
        val merged = VastAd.ViewableImpression(
            id = inLine?.id ?: chain?.id,
            viewable = inLine?.viewable.orEmpty() + chain?.viewable.orEmpty(),
            notViewable = inLine?.notViewable.orEmpty() + chain?.notViewable.orEmpty(),
            viewUndetermined = inLine?.viewUndetermined.orEmpty() + chain?.viewUndetermined.orEmpty(),
        )
        return if (merged.isEmpty) null else merged
    }

    /**
     * The InLine's own verifications first — it is closest to the creative — then
     * the chain's, skipping vendors it already asked for.
     */
    private fun mergedVerifications(ad: VastAd): List<VastVerification> {
        val merged = ad.adVerifications.toMutableList()
        for (verification in verifications) {
            if (merged.none { it.isSameAs(verification) }) merged += verification
        }
        return merged
    }

    private fun VastVerification.isSameAs(other: VastVerification): Boolean =
        vendor == other.vendor && resources.map { it.url } == other.resources.map { it.url }

    internal companion object {
        private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        /** Real responses redirect with a relative `VASTAdTagURI`. */
        fun resolve(tag: String, base: String?): String {
            if (SCHEME.containsMatchIn(tag) || base == null) return tag
            return try {
                val baseUri = URI(base)
                // `URI.resolve` drops the slash between an empty path and a
                // relative reference ("http://host" + "a.xml" = "http://hosta.xml");
                // RFC 3986 §5.2.3 and Foundation both supply one.
                val anchored = if (baseUri.rawPath.isNullOrEmpty() && baseUri.rawAuthority != null) {
                    URI(baseUri.scheme, baseUri.rawAuthority, "/", null, null)
                } else {
                    baseUri
                }
                anchored.resolve(tag).toString()
            } catch (_: Exception) {
                tag
            }
        }
    }
}

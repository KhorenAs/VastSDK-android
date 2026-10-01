package com.kinodaran.vast.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Fetches a tag and follows its Wrapper chain to the InLine ads behind it.
 *
 * The only impure part is asking the loader for XML; every decision — when to
 * stop, which error code to report, what to accumulate — belongs to
 * [VastWrapperChain] and is tested without IO.
 */
public class VastTagResolver(
    private val loader: VastResourceLoader,
    private val maxDepth: Int = 5,
    /** Per hop. A session that wants a bound on the whole chain imposes it around this call. */
    private val timeoutSeconds: Double = 5.0,
) {

    /** Ads plus the error URIs owed if playback later fails. */
    public data class Resolution(val ads: List<VastAd>, val chain: VastWrapperChain)

    /**
     * Raised when the chain ends without a playable ad. [beacons] are the
     * `<Error>` requests owed to every Wrapper that was traversed.
     */
    public class Failure(public val error: VastError, public val beacons: List<VastBeacon>) :
        Exception("VAST resolution failed with ${error.code} ${error.name}")

    private val parser = VastParser()

    /** Resolves a tag URL, following Wrappers until an InLine response. */
    public suspend fun resolveTag(url: String): Resolution = follow(url, VastWrapperChain(maxDepth))

    /**
     * Resolves a document already in hand. Wrappers inside it are still followed,
     * so a caller holding XML gets the same behaviour as [resolveTag].
     */
    public suspend fun resolveXml(xml: String, baseUrl: String? = null): Resolution {
        val chain = VastWrapperChain(maxDepth)
        return when (val step = step(xml, baseUrl, chain)) {
            is VastWrapperChain.Step.Resolved -> Resolution(step.ads, chain)
            is VastWrapperChain.Step.Failed -> throw Failure(step.error, chain.errorBeacons(step.error))
            // The chain carries on rather than starting again. Handing the tail a
            // fresh one would drop this document's own `<Impression>` and `<Error>`
            // URIs, restart the depth count — making the effective limit twice
            // `maxDepth` — and lose its `allowMultipleAds` constraint.
            is VastWrapperChain.Step.Follow -> follow(step.url, chain)
        }
    }

    /** Walks the redirect chain from [url], carrying what has been collected. */
    private suspend fun follow(url: String, chain: VastWrapperChain): Resolution {
        var next = url

        while (true) {
            val xml = try {
                loader.loadVast(next, timeoutSeconds)
            } catch (cancelled: CancellationException) {
                // Cancellation is the caller leaving, not the redirect failing:
                // nothing is reported for a break nobody is waiting for.
                throw cancelled
            } catch (_: Exception) {
                // A dead or slow redirect is a 301, and the wrappers already
                // traversed still expect to hear about it.
                throw Failure(VastError.WRAPPER_TIMEOUT, chain.errorBeacons(VastError.WRAPPER_TIMEOUT))
            }

            when (val step = step(xml, next, chain)) {
                is VastWrapperChain.Step.Resolved -> return Resolution(step.ads, chain)
                is VastWrapperChain.Step.Follow -> next = step.url
                is VastWrapperChain.Step.Failed -> throw Failure(step.error, chain.errorBeacons(step.error))
            }
        }
    }

    private fun step(xml: String, baseUrl: String?, chain: VastWrapperChain): VastWrapperChain.Step {
        val document = try {
            parser.parse(xml)
        } catch (failure: VastException) {
            throw Failure(failure.error, chain.errorBeacons(failure.error))
        }
        return chain.accept(document, baseUrl)
    }
}

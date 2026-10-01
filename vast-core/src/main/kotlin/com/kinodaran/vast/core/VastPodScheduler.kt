package com.kinodaran.vast.core

/**
 * Decides play order for a VAST response (§3.3.1).
 *
 * Sequenced ads form the pod and play in numerical order. Non-sequenced ads are
 * the "ad buffet" and are held back as substitutes for pod members that fail.
 */
public class VastPodScheduler(ads: List<VastAd>) {

    private val pod: List<VastAd>
    private val spares: ArrayDeque<VastAd>
    private var index = 0

    init {
        val sequenced = ads.filter { it.sequence != null }.sortedBy { it.sequence ?: 0 }
        val standAlone = ArrayDeque(ads.filter { it.sequence == null })
        // A response with no sequenced ads is a plain single/buffet response;
        // play the first stand-alone ad as the "pod".
        pod = if (sequenced.isEmpty() && standAlone.isNotEmpty()) listOf(standAlone.removeFirst()) else sequenced
        spares = standAlone
    }

    public val totalCount: Int get() = pod.size
    public val currentIndex: Int get() = index

    /**
     * What [next] would hand over, without handing it over.
     *
     * For warming the creative after this one while this one plays. Consuming the
     * entry to look at it would mean playing the pod in the wrong order, so
     * looking has to be free.
     */
    public fun peek(): VastAd? = pod.getOrNull(index)

    public fun next(): VastAd? {
        val ad = pod.getOrNull(index) ?: return null
        index += 1
        return ad
    }

    /**
     * The ad just handed out could not play. Substitute an unplayed stand-alone
     * ad if one exists; otherwise the caller moves on to the next pod entry.
     */
    public fun substituteForFailure(): VastAd? = spares.removeFirstOrNull()
}

package com.kinodaran.vast.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VastPodSchedulerTest {

    private fun ad(id: String, sequence: Int? = null) = VastAd(id = id, sequence = sequence, linear = VastAd.Linear(duration = 10.0))

    private fun VastPodScheduler.drain(): List<String> = generateSequence { next() }.map { it.id }.toList()

    /** §3.3.1: sequenced ads play in numerical order, whatever order they appear in the document. */
    @Test
    fun podPlaysInSequenceOrderNotDocumentOrder() {
        val scheduler = VastPodScheduler(listOf(ad("c", 3), ad("a", 1), ad("b", 2)))
        assertEquals(listOf("a", "b", "c"), scheduler.drain())
        assertEquals(3, scheduler.totalCount)
    }

    /** Non-sequenced ads are the "ad buffet" — held back, not played inline. */
    @Test
    fun standAloneAdsAreNotPartOfThePod() {
        val scheduler = VastPodScheduler(listOf(ad("pod-1", 1), ad("pod-2", 2), ad("spare")))
        assertEquals(2, scheduler.totalCount)
        assertEquals(listOf("pod-1", "pod-2"), scheduler.drain())
    }

    /** §3.3.1: a failed pod ad is substituted by an un-played stand-alone ad. */
    @Test
    fun failedPodAdIsSubstitutedByAStandAloneAd() {
        val scheduler = VastPodScheduler(listOf(ad("pod-1", 1), ad("pod-2", 2), ad("spare-1"), ad("spare-2")))
        scheduler.next()
        assertEquals("spare-1", scheduler.substituteForFailure()?.id)
        assertEquals("spare-2", scheduler.substituteForFailure()?.id)
        assertNull(scheduler.substituteForFailure(), "each spare is used at most once")
    }

    /** With no stand-alone ad left, the player moves on — a failure must not end the break. */
    @Test
    fun podContinuesWhenNoSubstituteRemains() {
        val scheduler = VastPodScheduler(listOf(ad("pod-1", 1), ad("pod-2", 2)))
        assertEquals("pod-1", scheduler.next()?.id)
        assertNull(scheduler.substituteForFailure())
        assertEquals("pod-2", scheduler.next()?.id)
    }

    /** The common single-ad response has no sequence at all and must still play. */
    @Test
    fun singleStandAloneResponsePlays() {
        val scheduler = VastPodScheduler(listOf(ad("only")))
        assertEquals(1, scheduler.totalCount)
        assertEquals(listOf("only"), scheduler.drain())
    }

    @Test
    fun emptyResponseYieldsNothing() {
        val scheduler = VastPodScheduler(emptyList())
        assertEquals(0, scheduler.totalCount)
        assertNull(scheduler.next())
    }

    // MARK: - Counting

    /** §3.3.1: ads with no `sequence` are alternatives, not a running order. */
    @Test
    fun responseOfOnlyStandAloneAdsPlaysOneOfThem() {
        val scheduler = VastPodScheduler(listOf(ad("a"), ad("b"), ad("c")))
        assertEquals(1, scheduler.totalCount, "a buffet is one slot, not three")
        assertEquals(listOf("a"), scheduler.drain())
    }

    @Test
    fun unplayedStandAloneAdsRemainAvailableAsSubstitutes() {
        val scheduler = VastPodScheduler(listOf(ad("a"), ad("b"), ad("c")))
        scheduler.next()
        assertEquals("b", scheduler.substituteForFailure()?.id)
        assertEquals("c", scheduler.substituteForFailure()?.id)
    }

    /** A substitute fills the slot the failed ad occupied, so the position shown does not advance for it. */
    @Test
    fun substituteBelongsToTheSamePodSlot() {
        val scheduler = VastPodScheduler(listOf(ad("pod-1", 1), ad("pod-2", 2), ad("spare")))
        assertEquals(2, scheduler.totalCount)
        assertEquals("pod-1", scheduler.next()?.id)
        assertEquals(1, scheduler.currentIndex, "one slot consumed")
        assertEquals("spare", scheduler.substituteForFailure()?.id)
        assertEquals(1, scheduler.currentIndex, "a substitute does not consume a new slot")
    }

    // MARK: - Looking ahead

    @Test
    fun peekDoesNotConsumeTheEntry() {
        val scheduler = VastPodScheduler(listOf(ad("first", 1), ad("second", 2)))
        assertEquals("first", scheduler.peek()?.id)
        assertEquals("first", scheduler.peek()?.id, "looking twice changed what is next")
        assertEquals("first", scheduler.next()?.id)
        assertEquals("second", scheduler.peek()?.id)
    }

    @Test
    fun peekIsEmptyOnceThePodIsSpent() {
        val scheduler = VastPodScheduler(listOf(ad("only", 1)))
        scheduler.next()
        assertNull(scheduler.peek())
    }
}

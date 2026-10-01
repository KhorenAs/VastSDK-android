package com.kinodaran.vast.core

import com.kinodaran.vast.core.VastMediaFileSelector.Capabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Selection is a ranking rather than a filter wherever the spec allows it: an ad
 * with one poorly-fitting media file should still play.
 */
class VastMediaFileSelectorTest {

    private val selector = VastMediaFileSelector()
    private val capabilities = Capabilities(width = 1280, height = 720)

    private fun file(
        url: String,
        type: String = "video/mp4",
        delivery: VastAd.Delivery = VastAd.Delivery.PROGRESSIVE,
        width: Int? = null,
        height: Int? = null,
    ) = VastAd.MediaFile(url = url, mimeType = type, delivery = delivery, width = width, height = height)

    // MARK: - Transport security

    /** A response offering both renditions must not have the http one chosen and then refused as cleartext. */
    @Test
    fun httpsIsPreferredOverAnOtherwiseIdenticalHttpFile() {
        val chosen = selector.select(listOf(file("http://ads.test/a.mp4", width = 854, height = 480), file("https://ads.test/a.mp4", width = 854, height = 480)), capabilities)
        assertTrue(chosen.url.startsWith("https:"))
    }

    /** The preference only decides between otherwise equal files. */
    @Test
    fun theHttpsPreferenceDoesNotOverrideAMuchBetterFit() {
        val chosen = selector.select(listOf(file("http://ads.test/fits.mp4", width = 854, height = 480), file("https://ads.test/huge.mp4", width = 3840, height = 2160)), capabilities)
        assertEquals("fits.mp4", chosen.url.lastPathComponent)
    }

    /** An http-only response still plays: refusing it would be the SDK deciding the advertiser loses the delivery. */
    @Test
    fun anHttpOnlyResponseIsStillPlayed() {
        assertEquals("only.mp4", selector.select(listOf(file("http://ads.test/only.mp4", width = 854, height = 480)), capabilities).url.lastPathComponent)
    }

    // MARK: - Fit

    @Test
    fun theClosestResolutionWins() {
        val chosen = selector.select(
            listOf(
                file("https://ads.test/small.mp4", width = 320, height = 180),
                file("https://ads.test/right.mp4", width = 1280, height = 720),
                file("https://ads.test/huge.mp4", width = 3840, height = 2160),
            ),
            capabilities,
        )
        assertEquals("right.mp4", chosen.url.lastPathComponent)
    }

    /** The penalty for undershooting rises twice as fast as the one for overshooting. */
    @Test
    fun undershootingIsPenalisedMoreThanOvershooting() {
        val chosen = selector.select(listOf(file("https://ads.test/under.mp4", width = 960, height = 540), file("https://ads.test/over.mp4", width = 1600, height = 900)), capabilities)
        assertEquals("over.mp4", chosen.url.lastPathComponent)
    }

    /** Half and double really are equal, so the response's own order decides. */
    @Test
    fun equalDistanceLeavesTheResponseOrderToDecide() {
        val files = listOf(file("https://ads.test/half.mp4", width = 640, height = 360), file("https://ads.test/double.mp4", width = 2560, height = 1440))

        assertEquals("half.mp4", selector.select(files, capabilities).url.lastPathComponent)
        assertEquals("double.mp4", selector.select(files.reversed(), capabilities).url.lastPathComponent)
    }

    // MARK: - What is refused

    /** One file the player cannot decode must not lose the ad when another can. */
    @Test
    fun anUndecodableContainerIsSkippedRatherThanChosen() {
        val chosen = selector.select(listOf(file("https://ads.test/flash.flv", type = "video/x-flv", width = 1280, height = 720), file("https://ads.test/ok.mp4", width = 320, height = 180)), capabilities)
        assertEquals("ok.mp4", chosen.url.lastPathComponent)
    }

    @Test
    fun nothingPlayableIsReportedAs403() {
        val error = assertFailsWith<VastException> { selector.select(listOf(file("https://ads.test/x.flv", type = "video/x-flv")), capabilities) }.error
        assertEquals(VastError.NO_SUPPORTED_MEDIA_FILE, error)
        assertEquals(403, error.code)
    }

    /** A player with no streaming pipeline cannot take a streaming delivery, however well it fits. */
    @Test
    fun streamingIsRefusedWhereItCannotBePlayed() {
        val chosen = selector.select(
            listOf(
                file("https://ads.test/live.m3u8", type = "application/x-mpegurl", delivery = VastAd.Delivery.STREAMING, width = 1280, height = 720),
                file("https://ads.test/file.mp4", width = 320, height = 180),
            ),
            capabilities.copy(supportsStreaming = false),
        )
        assertEquals("file.mp4", chosen.url.lastPathComponent)
    }

    /** MIME types arrive in any case; matching them exactly would refuse playable files. */
    @Test
    fun mimeTypeMatchingIgnoresCase() {
        assertEquals("a.mp4", selector.select(listOf(file("https://ads.test/a.mp4", type = "Video/MP4")), capabilities).url.lastPathComponent)
    }
}

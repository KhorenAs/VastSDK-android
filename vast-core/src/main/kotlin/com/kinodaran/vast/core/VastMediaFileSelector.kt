package com.kinodaran.vast.core

import kotlin.math.max

/**
 * Picks the `<MediaFile>` best suited to the current device.
 *
 * Selection is a ranking rather than a filter wherever the spec allows it: an ad
 * with only one media file should still play even if its resolution or bitrate
 * is a poor fit, because a no-fill costs the advertiser a delivery while a
 * slightly-too-large file costs a little bandwidth.
 */
public class VastMediaFileSelector {

    public data class Capabilities(
        /** The ad surface's size in pixels — not the screen's: an inline player wants an inline rendition. */
        val width: Int,
        val height: Int,
        /**
         * Container types the player can decode. Files outside this set are
         * rejected outright — playing them would produce error 403 anyway.
         */
        val supportedMimeTypes: Set<String> = MEDIA3_DEFAULTS,
        /** Soft ceiling in kbps. Higher-bitrate files rank lower but stay eligible. */
        val preferredBitrate: Int? = null,
        /** `false` where the player has no HLS pipeline. */
        val supportsStreaming: Boolean = true,
    ) {
        public companion object {
            /**
             * What Media3 plays with `media3-exoplayer` and `media3-exoplayer-hls`.
             *
             * QuickTime is left out although the Apple SDK lists it: Media3's MP4
             * extractor reads some `.mov` files and not others, and a creative that
             * is chosen and then fails costs a 405 where refusing it would have let
             * an `.mp4` alternative play.
             */
            public val MEDIA3_DEFAULTS: Set<String> = setOf(
                "video/mp4", "video/x-m4v", "video/3gpp", "video/webm",
                "application/x-mpegurl", "application/vnd.apple.mpegurl",
            )
        }
    }

    /** @throws VastException with [VastError.NO_SUPPORTED_MEDIA_FILE] (403) when nothing is playable. */
    public fun select(files: List<VastAd.MediaFile>, capabilities: Capabilities): VastAd.MediaFile {
        val eligible = files.filter { playable(it, capabilities) }
        if (eligible.isEmpty()) throw VastException(VastError.NO_SUPPORTED_MEDIA_FILE)
        // The first of equal costs wins, so the response's own order decides a tie.
        return eligible.minBy { cost(it, capabilities) }
    }

    private fun playable(file: VastAd.MediaFile, capabilities: Capabilities): Boolean {
        if (file.mimeType.lowercase() !in capabilities.supportedMimeTypes) return false
        if (file.delivery == VastAd.Delivery.STREAMING && !capabilities.supportsStreaming) return false
        return true
    }

    /**
     * Lower is better. Resolution dominates because upscaling a small creative is
     * the most visible defect; bitrate breaks ties.
     */
    private fun cost(file: VastAd.MediaFile, capabilities: Capabilities): Double {
        var score = 0.0

        val width = file.width?.takeIf { it > 0 }
        val height = file.height?.takeIf { it > 0 }
        if (width != null && height != null) {
            val widthRatio = width.toDouble() / max(capabilities.width, 1)
            val heightRatio = height.toDouble() / max(capabilities.height, 1)
            val ratio = max(widthRatio, heightRatio)
            // Being too small is penalised roughly twice as hard as being too large,
            // since the player can downscale cleanly but not invent detail.
            score += if (ratio >= 1) ratio - 1 else (1 - ratio) * 2
        } else {
            // Undeclared dimensions are common and are not disqualifying, but a file
            // that states its size is a safer pick when one exists.
            score += UNDECLARED_SIZE_COST
        }

        // Android refuses cleartext by default from API 28, and a response offering
        // both renditions would otherwise have the http one chosen and then fail as
        // a media error. Small, so it only decides between otherwise equal files.
        if (!file.url.startsWith("https:", ignoreCase = true)) score += CLEARTEXT_COST

        val ceiling = capabilities.preferredBitrate
        val bitrate = effectiveBitrate(file)
        if (ceiling != null && bitrate != null) {
            val ratio = bitrate.toDouble() / max(ceiling, 1)
            score += if (ratio > 1) ratio - 1 else 0.0
        }
        return score
    }

    /** VAST 4 lets a file declare a range instead of a single bitrate. */
    private fun effectiveBitrate(file: VastAd.MediaFile): Int? {
        file.bitrate?.let { return it }
        val minimum = file.minBitrate
        val maximum = file.maxBitrate
        if (minimum == null || maximum == null) return minimum ?: maximum
        return (minimum + maximum) / 2
    }
}

/** What a file that does not state its size gives up to one that does. */
private const val UNDECLARED_SIZE_COST = 0.5

/** What an `http` rendition gives up to an `https` one: enough to decide a tie, no more. */
private const val CLEARTEXT_COST = 0.25

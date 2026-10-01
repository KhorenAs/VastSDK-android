package com.kinodaran.vast.kit

import android.net.Uri
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.AdPlaybackState
import androidx.media3.common.AdViewProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ads.AdsLoader
import androidx.media3.exoplayer.source.ads.AdsMediaSource
import com.kinodaran.vast.core.VastError
import kotlinx.coroutines.Job
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Inserts a VAST break into the host's own Media3 player, the way
 * `media3-exoplayer-ima` inserts an IMA one.
 *
 * Host code is the usual Media3 ads wiring, with [withVastAds] doing the
 * factory part:
 *
 * ```kotlin
 * val session = VastAdSession(context)
 * val player = ExoPlayer.Builder(context)
 *     .setMediaSourceFactory(DefaultMediaSourceFactory(context).withVastAds(session))
 *     .build()
 * session.adsLoader.setPlayer(player)
 * player.setMediaItem(
 *     MediaItem.Builder()
 *         .setUri(contentUri)
 *         .setAdsConfiguration(MediaItem.AdsConfiguration.Builder(adTagUri).build())
 *         .build(),
 * )
 * ```
 *
 * The break is a pre-roll: the response is resolved when the content is
 * prepared, the content waits for it — no longer than the resolution budget —
 * and every playable ad of the pod is one ad in a single ad group at the start of
 * the content. Media3 then owns playback, buffering and the return to content;
 * this class reports what it sees to the [VastAdSession], which owns tracking.
 *
 * One break at a time, as a session is one break. Main thread only.
 */
@OptIn(UnstableApi::class)
public class VastAdsLoader internal constructor(private val session: VastAdSession) : AdsLoader {

    private var nextPlayer: Player? = null
    private var player: Player? = null
    private var source: AdsMediaSource? = null
    private var eventListener: AdsLoader.EventListener? = null
    private var dataSpec: DataSpec? = null
    private var adsId: Any? = null
    private var adPlaybackState: AdPlaybackState? = null
    private var resolution: Job? = null
    private val slots = mutableListOf<VastAdSlot>()
    private var playingIndex = C.INDEX_UNSET
    private var breakOver = false
    private val period = Timeline.Period()

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = sync(naturally = false)

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            sync(naturally = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = session.notePlayback(playWhenReady)

        /**
         * A creative plays at normal speed or not at all. Watched time is measured
         * from the playhead, so an ad at 2× is watched in half the time and every
         * quartile still fires — the speed menu is the scrubber by another name.
         * Media3 already refuses seeks while an ad plays; speed it leaves to us.
         */
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (playingIndex != C.INDEX_UNSET && playbackParameters.speed != 1f) player?.setPlaybackSpeed(1f)
        }
    }

    /** The content's own speed, held while the break plays at 1× and given back after it. */
    private var hostSpeed: Float? = null

    private val breakPlayer = object : VastBreakPlayer {
        override val wantsPlayback: Boolean
            get() = player?.let { it.playWhenReady && it.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE } ?: false

        override fun skip(slot: Int) = update { it.withSkippedAd(AD_GROUP, slot) }

        override fun abandon(slot: Int) = update { it.withSkippedAd(AD_GROUP, slot) }

        override fun pause() {
            player?.pause()
        }

        override fun play() {
            player?.play()
        }

        override fun onAdClicked() {
            eventListener?.onAdClicked()
        }
    }

    // MARK: - AdsLoader

    override fun setPlayer(player: Player?) {
        nextPlayer = player
    }

    /** Any content type: the break is a pre-roll and does not depend on how the content is delivered. */
    override fun setSupportedContentTypes(vararg contentTypes: Int) {}

    override fun start(
        adsMediaSource: AdsMediaSource,
        adTagDataSpec: DataSpec,
        adsId: Any,
        adViewProvider: AdViewProvider,
        eventListener: AdsLoader.EventListener,
    ) {
        val player = nextPlayer ?: run {
            VastLog.warning("VastAdsLoader.start without setPlayer; the content plays without its break")
            eventListener.onAdPlaybackState(AdPlaybackState(adsId))
            return
        }
        attach(player, adsMediaSource, eventListener)

        // The same content prepared again — a player rebuilt after a configuration
        // change, say. The break already resolved is the one to show, from where it was.
        val known = adPlaybackState
        if (adsId == this.adsId && known != null) {
            eventListener.onAdPlaybackState(known)
            sync(naturally = false)
            return
        }

        reset()
        this.adsId = adsId
        this.dataSpec = adTagDataSpec
        val tag = tagFor(adTagDataSpec.uri) ?: run {
            session.resolveFailedBeforeLoading(VastError.WRAPPER_GENERAL)
            deliver(AdPlaybackState(adsId))
            return
        }
        resolution = session.resolve(tag) { planned -> onResolved(adsId, planned) }
    }

    override fun stop(adsMediaSource: AdsMediaSource, eventListener: AdsLoader.EventListener) {
        if (source !== adsMediaSource) return
        player?.removeListener(playerListener)
        session.attach(null)
        player = null
        source = null
        this.eventListener = null
    }

    override fun release() {
        resolution?.cancel()
        resolution = null
        player?.removeListener(playerListener)
        session.attach(null)
        player = null
        nextPlayer = null
        source = null
        eventListener = null
        reset()
    }

    override fun handlePrepareComplete(adsMediaSource: AdsMediaSource, adGroupIndex: Int, adIndexInAdGroup: Int) {
        if (adGroupIndex != AD_GROUP) return
        slots.getOrNull(adIndexInAdGroup)?.let(session::slotPrepared)
    }

    override fun handlePrepareError(adsMediaSource: AdsMediaSource, adGroupIndex: Int, adIndexInAdGroup: Int, exception: IOException) {
        if (adGroupIndex != AD_GROUP) return
        val slot = slots.getOrNull(adIndexInAdGroup) ?: return
        session.slotFailed(slot, errorFor(exception))
        update { state ->
            var next = state.withAdLoadError(AD_GROUP, adIndexInAdGroup)
            // §3.3.1: an unplayed stand-alone ad takes the failed one's turn. Media3
            // cannot put a new creative where one has already been tried, so the
            // substitute joins the end of the group instead — the break still has
            // as many ads as the pod asked for.
            session.substitute(slots.size)?.let { spare ->
                slots += spare
                next = next.withAdCount(AD_GROUP, slots.size).withAvailableAdMediaItem(AD_GROUP, spare.index, mediaItem(spare))
            }
            next
        }
        sync(naturally = false)
    }

    // MARK: - The break

    private fun attach(player: Player, adsMediaSource: AdsMediaSource, eventListener: AdsLoader.EventListener) {
        this.player?.removeListener(playerListener)
        this.player = player
        this.source = adsMediaSource
        this.eventListener = eventListener
        player.addListener(playerListener)
        session.attach(breakPlayer)
    }

    private fun reset() {
        resolution?.cancel()
        resolution = null
        adsId = null
        adPlaybackState = null
        dataSpec = null
        slots.clear()
        playingIndex = C.INDEX_UNSET
        breakOver = false
        hostSpeed = null
    }

    private fun onResolved(adsId: Any, planned: List<VastAdSlot>) {
        if (adsId != this.adsId) return
        resolution = null
        slots.clear()
        slots += planned
        if (planned.isEmpty()) {
            breakOver = true
            // No ad group at all: the content plays as though it had no break.
            deliver(AdPlaybackState(adsId))
            return
        }
        var state = AdPlaybackState(adsId, 0L).withAdCount(AD_GROUP, planned.size)
        for (slot in planned) state = state.withAvailableAdMediaItem(AD_GROUP, slot.index, mediaItem(slot))
        deliver(state)
    }

    private fun update(change: (AdPlaybackState) -> AdPlaybackState) {
        val state = adPlaybackState ?: return
        deliver(change(state))
    }

    private fun deliver(state: AdPlaybackState) {
        adPlaybackState = state
        eventListener?.onAdPlaybackState(state)
    }

    /**
     * Works out which creative the player is on, and tells the session what
     * changed. Driven by both timeline changes and discontinuities, as IMA's is:
     * either can be the one that moves the player out of an ad.
     */
    private fun sync(naturally: Boolean) {
        val player = player ?: return
        val state = adPlaybackState ?: return
        val ours = player.isPlayingAd && player.currentAdGroupIndex == AD_GROUP && isOurPeriod(player)
        val index = if (ours) player.currentAdIndexInAdGroup else C.INDEX_UNSET

        if (playingIndex != C.INDEX_UNSET && index != playingIndex) {
            val left = slots[playingIndex]
            session.slotLeft(left, naturally)
            // Played, unless it was already settled some other way — skipped,
            // abandoned, or failed.
            if (state.getAdGroup(AD_GROUP).states.getOrNull(left.index) == AdPlaybackState.AD_STATE_AVAILABLE) {
                deliver(state.withPlayedAd(AD_GROUP, left.index))
            }
        }
        if (index != C.INDEX_UNSET && index != playingIndex) {
            if (hostSpeed == null) {
                hostSpeed = player.playbackParameters.speed
                if (hostSpeed != 1f) player.setPlaybackSpeed(1f)
            }
            val slot = slots.getOrNull(index)
            if (slot != null) session.slotStarted(slot, slots.size, VastPlayerClock(player, slot, AD_GROUP, ::isOurPeriod, clockOf(player)))
        }
        playingIndex = index

        if (!ours && !breakOver && slots.isNotEmpty() && isSettled()) {
            breakOver = true
            hostSpeed?.let { if (it != 1f) player.setPlaybackSpeed(it) }
            hostSpeed = null
            session.breakEnded()
        }
    }

    /** ExoPlayer's own clock where there is one — a fake one, in tests — and the system's otherwise. */
    private fun clockOf(player: Player): Clock = (player as? ExoPlayer)?.clock ?: Clock.DEFAULT

    private fun isOurPeriod(player: Player): Boolean {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return false
        return timeline.getPeriod(player.currentPeriodIndex, period).adsId == adsId
    }

    /** Every ad in the group played, skipped or failed: nothing is left for the break to show. */
    private fun isSettled(): Boolean {
        val group = adPlaybackState?.getAdGroup(AD_GROUP) ?: return true
        return group.states.take(group.count.coerceAtLeast(0)).none { it == AdPlaybackState.AD_STATE_AVAILABLE || it == AdPlaybackState.AD_STATE_UNAVAILABLE }
    }

    public companion object {
        private const val AD_GROUP = 0

        /**
         * An `AdsConfiguration` tag for a response already in hand, as a `data:`
         * URI. Wrappers inside it are still followed; a relative `VASTAdTagURI`
         * cannot be, as there is nothing to resolve it against — prefer the URL the
         * response came from whenever there is one.
         */
        public fun adTagUriForResponse(xml: String): Uri =
            Uri.parse("data:text/xml;base64," + Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))

        internal fun tagFor(uri: Uri): VastAdTag? {
            val text = uri.toString()
            if (!text.startsWith("data:", ignoreCase = true)) return VastAdTag.Url(text)
            val comma = text.indexOf(',')
            if (comma < 0) return null
            val header = text.substring(5, comma)
            val payload = text.substring(comma + 1)
            val xml = try {
                if (header.endsWith(";base64", ignoreCase = true)) {
                    String(Base64.decode(payload, Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    URLDecoder.decode(payload, "UTF-8")
                }
            } catch (_: IllegalArgumentException) {
                return null
            }
            return VastAdTag.Xml(xml, baseUrl = null)
        }

        /**
         * Media3's spelling of the HLS type, which its source factory matches
         * exactly. Responses write it in every case there is.
         */
        private fun mimeTypeFor(type: String): String? = when (type.lowercase()) {
            "application/x-mpegurl", "application/vnd.apple.mpegurl" -> MimeTypes.APPLICATION_M3U8
            "" -> null
            else -> type
        }

        private fun mediaItem(slot: VastAdSlot): MediaItem =
            MediaItem.Builder().setUri(slot.mediaFile.url).setMimeType(mimeTypeFor(slot.mediaFile.mimeType)).build()

        /** Maps Media3's failure onto the code an ad server understands. */
        internal fun errorFor(exception: IOException): VastError {
            var cause: Throwable? = exception
            while (cause != null) {
                when (cause) {
                    is SocketTimeoutException -> return VastError.MEDIA_FILE_TIMEOUT
                    is HttpDataSource.InvalidResponseCodeException -> return VastError.MEDIA_FILE_NOT_FOUND
                    is HttpDataSource.HttpDataSourceException -> return VastError.MEDIA_FILE_NOT_FOUND
                }
                cause = cause.cause
            }
            // A supported container the pipeline still could not render.
            return VastError.MEDIA_FILE_DISPLAY_PROBLEM
        }
    }
}

/**
 * Routes ad insertion for every `MediaItem` with an `AdsConfiguration` through
 * [session]'s loader.
 *
 * No ad view is given to Media3: the SDK draws its UI with `VastAdSurface`, and
 * nothing in it measures a view. An Open Measurement adapter, which would, can
 * be handed one through `setLocalAdInsertionComponents` directly.
 */
@OptIn(UnstableApi::class)
public fun DefaultMediaSourceFactory.withVastAds(session: VastAdSession): DefaultMediaSourceFactory =
    setLocalAdInsertionComponents({ session.adsLoader }, AdViewProvider { null })

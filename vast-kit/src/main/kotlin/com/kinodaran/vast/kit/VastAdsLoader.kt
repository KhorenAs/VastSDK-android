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
 * The tag in the `AdsConfiguration` is a pre-roll: it is resolved when the
 * content is prepared, the content waits for it — no longer than the resolution
 * budget — and every playable ad of the pod is one ad in an ad group at the start
 * of the content. More breaks can be asked for while the content plays with
 * [VastAdSession.insertBreak]; each becomes an ad group at the playhead. Media3
 * then owns playback, buffering and the return to content; this class reports
 * what it sees to the [VastAdSession], which owns tracking.
 *
 * One break plays at a time. Main thread only.
 */
@OptIn(UnstableApi::class)
public class VastAdsLoader internal constructor(private val session: VastAdSession) : AdsLoader {

    private var nextPlayer: Player? = null
    private var player: Player? = null
    private var source: AdsMediaSource? = null
    private var eventListener: AdsLoader.EventListener? = null
    private var adsId: Any? = null
    private var adPlaybackState: AdPlaybackState? = null
    private var resolution: Job? = null
    private val period = Timeline.Period()

    /**
     * Every break given a place in the timeline, by the position of its ad group
     * in the content period. Keyed by position rather than by group index because
     * an index moves when a break is inserted before it; a position does not.
     */
    private val breaks = mutableMapOf<Long, MutableList<VastAdSlot>>()

    /** Breaks delivered to Media3 and not yet over, by the same key. */
    private val openBreaks = mutableSetOf<Long>()

    /** The creative on screen, if the player is on one of ours. */
    private var playing: VastAdSlot? = null

    /** Breaks asked for while another was resolving or playing, in the order they were asked for. */
    private val pending = ArrayDeque<VastAdTag>()

    /**
     * Where an inserted break was put, until the player's timeline has it and the
     * playhead can be sent through it.
     */
    private var midrollAwaitingSeekUs: Long? = null

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            sync(naturally = false)
            enterInsertedBreakIfReady()
        }

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
            if (playing != null && playbackParameters.speed != 1f) player?.setPlaybackSpeed(1f)
        }
    }

    /** The content's own speed, held while a break plays at 1× and given back after it. */
    private var hostSpeed: Float? = null

    private val breakPlayer = object : VastBreakPlayer {
        override val wantsPlayback: Boolean
            get() = player?.let { it.playWhenReady && it.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE } ?: false

        override fun skip(slot: VastAdSlot) = settle(slot) { state, group -> state.withSkippedAd(group, slot.index) }

        override fun abandon(slot: VastAdSlot) = settle(slot) { state, group -> state.withSkippedAd(group, slot.index) }

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

    /** Any content type: a break does not depend on how the content is delivered. */
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
        // change, say. The breaks already resolved are the ones to show, from where they were.
        val known = adPlaybackState
        if (adsId == this.adsId && known != null) {
            eventListener.onAdPlaybackState(known)
            sync(naturally = false)
            return
        }

        reset()
        this.adsId = adsId
        val tag = tagFor(adTagDataSpec.uri) ?: run {
            session.resolveFailedBeforeLoading(VastError.WRAPPER_GENERAL)
            deliver(AdPlaybackState(adsId))
            return
        }
        resolution = session.resolve(tag, blocking = true) { planned -> onPrerollResolved(adsId, planned) }
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
        player?.removeListener(playerListener)
        session.attach(null)
        player = null
        nextPlayer = null
        source = null
        eventListener = null
        reset()
    }

    override fun handlePrepareComplete(adsMediaSource: AdsMediaSource, adGroupIndex: Int, adIndexInAdGroup: Int) {
        slotAt(adGroupIndex, adIndexInAdGroup)?.let(session::slotPrepared)
    }

    override fun handlePrepareError(adsMediaSource: AdsMediaSource, adGroupIndex: Int, adIndexInAdGroup: Int, exception: IOException) {
        val slot = slotAt(adGroupIndex, adIndexInAdGroup) ?: return
        val slots = breaks[slot.groupTimeUs] ?: return
        session.slotFailed(slot, errorFor(exception))
        update { state ->
            val group = groupIndexOf(state, slot.groupTimeUs) ?: return@update state
            var next = state.withAdLoadError(group, adIndexInAdGroup)
            // §3.3.1: an unplayed stand-alone ad takes the failed one's turn. Media3
            // cannot put a new creative where one has already been tried, so the
            // substitute joins the end of the group instead — the break still has
            // as many ads as the pod asked for.
            session.substitute(slots.size)?.let { spare ->
                spare.groupTimeUs = slot.groupTimeUs
                slots += spare
                next = next.withAdCount(group, slots.size).withAvailableAdMediaItem(group, spare.index, mediaItem(spare))
            }
            next
        }
        sync(naturally = false)
    }

    // MARK: - Breaks on demand

    /**
     * Plays a break as soon as the content allows it: right away when the content
     * is what is playing, and after the break on screen when it is not. Several
     * asked for at once play one after another, in the order they were asked for.
     *
     * The break is put into the content's own timeline at the playhead, so the
     * content stops where it was and resumes from there. Content the player
     * cannot seek in — a live stream without a window — holds an inserted break
     * until the viewer next seeks, which is Media3's rule rather than ours.
     */
    internal fun insertBreak(adTag: Uri) {
        val tag = tagFor(adTag) ?: return VastLog.warning("insertBreak: not an ad tag: $adTag")
        pending.addLast(tag)
        session.notePendingBreaks(pending.size)
        startNextPending()
    }

    private fun startNextPending() {
        val player = player ?: return
        val adsId = adsId ?: return
        // Not before the content has its timeline, not while another break is
        // resolving, and not on top of one that is playing.
        //
        // `isActive` rather than a null check: a response that resolves at once can
        // finish inside `resolve` itself, before its job is ever assigned here, and
        // a completed job left in the field would hold the queue shut for good.
        if (adPlaybackState == null || resolution?.isActive == true || openBreaks.isNotEmpty() || player.isPlayingAd) return
        val tag = pending.removeFirstOrNull() ?: return
        session.notePendingBreaks(pending.size)
        resolution = session.resolve(tag, blocking = false) { planned -> onMidrollResolved(adsId, planned) }
    }

    // MARK: - Resolution

    private fun onPrerollResolved(adsId: Any, planned: List<VastAdSlot>) {
        if (adsId != this.adsId) return
        resolution = null
        if (planned.isEmpty()) {
            // No ad group at all: the content plays as though it had no break.
            deliver(AdPlaybackState(adsId))
            startNextPending()
            return
        }
        deliver(withBreak(AdPlaybackState(adsId), 0L, planned))
    }

    private fun onMidrollResolved(adsId: Any, planned: List<VastAdSlot>) {
        if (adsId != this.adsId) return
        resolution = null
        val player = player
        val state = adPlaybackState
        if (planned.isEmpty() || player == null || state == null) {
            startNextPending()
            return
        }
        // At the playhead itself, not ahead of it. Media3 does not interrupt content
        // for an ad group that appears where the content already is — it holds the
        // change until the next seek, so as not to cut a viewer off mid-sentence —
        // and that is exactly what a break asked for now has to do. So the break
        // goes in here, and once the player's timeline has it, the playhead is sent
        // back to where it is: a seek plays the unplayed group before it first, and
        // the content resumes from that same position afterwards.
        //
        // Strictly after every break already there: the content resumes from the
        // very position the last break sat at, and two groups at one position would
        // be one break as far as Media3 is concerned. A millisecond later is enough,
        // and whole milliseconds are what a seek can name.
        val playhead = contentPeriodPositionUs(player)
        val latest = (0 until state.adGroupCount).maxOfOrNull { state.getAdGroup(it).timeUs } ?: C.TIME_UNSET
        val positionUs = if (latest == C.TIME_UNSET || playhead > latest) playhead else (latest / 1000 + 1) * 1000
        deliver(withBreak(state, positionUs, planned))
        midrollAwaitingSeekUs = positionUs
        enterInsertedBreakIfReady()
    }

    private fun enterInsertedBreakIfReady() {
        val positionUs = midrollAwaitingSeekUs ?: return
        val player = player ?: return
        if (player.isPlayingAd) return
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return
        val known = timeline.getPeriod(player.currentPeriodIndex, period).adPlaybackState
        if ((0 until known.adGroupCount).none { known.getAdGroup(it).timeUs == positionUs }) return
        midrollAwaitingSeekUs = null
        // The group's own position, in window time, which is what a seek is given.
        player.seekTo(positionUs / 1000 + period.positionInWindowMs)
    }

    /** A new ad group at [positionUs] holding [slots], recorded as an open break. */
    private fun withBreak(state: AdPlaybackState, positionUs: Long, slots: List<VastAdSlot>): AdPlaybackState {
        val group = (0 until state.adGroupCount).count { state.getAdGroup(it).timeUs < positionUs }
        var next = state.withNewAdGroup(group, positionUs).withAdCount(group, slots.size)
        for (slot in slots) {
            slot.groupTimeUs = positionUs
            next = next.withAvailableAdMediaItem(group, slot.index, mediaItem(slot))
        }
        breaks[positionUs] = slots.toMutableList()
        openBreaks += positionUs
        return next
    }

    // MARK: - The break in progress

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
        breaks.clear()
        openBreaks.clear()
        playing = null
        hostSpeed = null
        midrollAwaitingSeekUs = null
        pending.clear()
        session.notePendingBreaks(0)
    }

    private fun update(change: (AdPlaybackState) -> AdPlaybackState) {
        val state = adPlaybackState ?: return
        deliver(change(state))
    }

    private fun settle(slot: VastAdSlot, change: (AdPlaybackState, Int) -> AdPlaybackState) = update { state ->
        groupIndexOf(state, slot.groupTimeUs)?.let { change(state, it) } ?: state
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
        val current = if (player.isPlayingAd && isOurPeriod(player)) slotAt(player.currentAdGroupIndex, player.currentAdIndexInAdGroup) else null

        val left = playing
        if (left != null && left !== current) {
            session.slotLeft(left, naturally)
            // Played, unless it was already settled some other way — skipped,
            // abandoned, or failed.
            val group = groupIndexOf(state, left.groupTimeUs)
            if (group != null && state.getAdGroup(group).states.getOrNull(left.index) == AdPlaybackState.AD_STATE_AVAILABLE) {
                deliver(state.withPlayedAd(group, left.index))
            }
        }
        if (current != null && current !== playing) {
            if (hostSpeed == null) {
                hostSpeed = player.playbackParameters.speed
                if (hostSpeed != 1f) player.setPlaybackSpeed(1f)
            }
            val total = breaks[current.groupTimeUs]?.size ?: 1
            session.slotStarted(current, total, VastPlayerClock(player, { slotAt(it) === current }, clockOf(player)))
        }
        playing = current

        // A break is over when every ad in it is settled and the player is not on
        // one of them — which covers a break whose every ad failed to load and that
        // the player therefore never entered.
        for (positionUs in openBreaks.toList()) {
            if (current?.groupTimeUs == positionUs || !isSettled(positionUs)) continue
            openBreaks -= positionUs
            hostSpeed?.let { if (it != 1f) player.setPlaybackSpeed(it) }
            hostSpeed = null
            session.breakEnded()
        }
        if (current == null) startNextPending()
    }

    /** ExoPlayer's own clock where there is one — a fake one, in tests — and the system's otherwise. */
    private fun clockOf(player: Player): Clock = (player as? ExoPlayer)?.clock ?: Clock.DEFAULT

    private fun isOurPeriod(player: Player): Boolean {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return false
        return timeline.getPeriod(player.currentPeriodIndex, period).adsId == adsId
    }

    /** The slot the player is on right now, if it is one of ours. */
    private fun slotAt(player: Player): VastAdSlot? =
        if (player.isPlayingAd && isOurPeriod(player)) slotAt(player.currentAdGroupIndex, player.currentAdIndexInAdGroup) else null

    private fun slotAt(adGroupIndex: Int, adIndexInAdGroup: Int): VastAdSlot? {
        val state = adPlaybackState ?: return null
        if (adGroupIndex !in 0 until state.adGroupCount) return null
        return breaks[state.getAdGroup(adGroupIndex).timeUs]?.getOrNull(adIndexInAdGroup)
    }

    private fun groupIndexOf(state: AdPlaybackState, positionUs: Long): Int? =
        (0 until state.adGroupCount).firstOrNull { state.getAdGroup(it).timeUs == positionUs }

    /** Every ad in the break played, skipped or failed: nothing is left for it to show. */
    private fun isSettled(positionUs: Long): Boolean {
        val state = adPlaybackState ?: return true
        val group = groupIndexOf(state, positionUs)?.let(state::getAdGroup) ?: return true
        return group.states.take(group.count.coerceAtLeast(0)).none { it == AdPlaybackState.AD_STATE_AVAILABLE || it == AdPlaybackState.AD_STATE_UNAVAILABLE }
    }

    /**
     * The content playhead in period time, which is what an ad group's position
     * is measured in. The two differ by the window's offset in its period, as they
     * do for IMA.
     */
    private fun contentPeriodPositionUs(player: Player): Long {
        val timeline = player.currentTimeline
        val windowPositionMs = player.contentPosition
        if (timeline.isEmpty) return windowPositionMs * 1000
        return (windowPositionMs - timeline.getPeriod(player.currentPeriodIndex, period).positionInWindowMs) * 1000
    }

    public companion object {
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

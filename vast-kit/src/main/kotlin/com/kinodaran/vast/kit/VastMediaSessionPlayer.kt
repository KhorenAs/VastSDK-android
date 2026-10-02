package com.kinodaran.vast.kit

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import com.kinodaran.vast.core.VastAd
import kotlinx.coroutines.flow.combine

/**
 * What the system's media controls show, and allow, while a break plays.
 *
 * The notification, the lock screen, a Bluetooth headset and Android Auto all
 * drive the host's `MediaSession`, and through it the player — which, during a
 * break, is playing the creative.
 */
public enum class VastNowPlayingPolicy {
    /**
     * Locks the controls that would move the playhead or leave the creative, and
     * describes the ad while it plays. The default: the alternative is a lock
     * screen naming a film that is not the thing making the sound.
     */
    DESCRIBES_AD,

    /** Locks the controls, and leaves the host's metadata exactly as it is. */
    LOCKS_CONTROLS,

    /** Neither. The system controls are the host's, skipping included. */
    UNTOUCHED,
}

/**
 * The player to hand the host's `MediaSession`, so the system's controls behave
 * during a break. Built by [VastAdSession.forMediaSession].
 *
 * Media3 already refuses a seek inside an ad, and takes the scrubber away
 * itself. What it leaves is the rest of the transport: *next* and *previous*,
 * which move to another media item and out of the creative, and the speed
 * control, which plays it in half the time with every quartile still firing.
 * VAST defines nothing that would permit either. This takes them away for the
 * length of the break — they disappear from the notification rather than doing
 * nothing — and refuses the calls if a controller sends them anyway.
 *
 * Play and pause are deliberately left alone: they reach the player like any
 * other pause, and the session reports them as §3.14.1 `pause` and `resume`.
 *
 * Everything is given back when the break ends, because nothing was changed:
 * this only answers differently while an ad is on, and tells the session's
 * listeners — `MediaSession` among them — each time the answer changes.
 */
@OptIn(UnstableApi::class)
public class VastMediaSessionPlayer internal constructor(
    player: Player,
    private val session: VastAdSession,
    private val policy: VastNowPlayingPolicy,
    /** The localised fallback for an ad with no `<AdTitle>`. */
    private val fallbackTitle: String,
) : ForwardingPlayer(player) {

    private val listeners = mutableMapOf<Player.Listener, Player.Listener>()
    private var breakActive = false
    private var describedAd: MediaMetadata? = null

    init {
        session.launchOnMain {
            combine(session.state, session.currentAd) { state, ad ->
                val active = when (state) {
                    VastAdState.Loading, VastAdState.Playing, VastAdState.Paused -> true
                    VastAdState.Idle, is VastAdState.Finished -> false
                }
                active to ad
            }.collect { (active, ad) -> refresh(active, ad) }
        }
    }

    // MARK: - Commands

    override fun getAvailableCommands(): Player.Commands {
        val commands = super.getAvailableCommands()
        if (!locks) return commands
        return commands.buildUpon().removeAll(*LOCKED_COMMANDS).build()
    }

    override fun isCommandAvailable(command: Int): Boolean =
        if (locks && command in LOCKED_COMMANDS) false else super.isCommandAvailable(command)

    override fun seekToNext(): Unit = unlessLocked { super.seekToNext() }

    override fun seekToNextMediaItem(): Unit = unlessLocked { super.seekToNextMediaItem() }

    override fun seekToPrevious(): Unit = unlessLocked { super.seekToPrevious() }

    override fun seekToPreviousMediaItem(): Unit = unlessLocked { super.seekToPreviousMediaItem() }

    override fun seekForward(): Unit = unlessLocked { super.seekForward() }

    override fun seekBack(): Unit = unlessLocked { super.seekBack() }

    override fun seekTo(positionMs: Long): Unit = unlessLocked { super.seekTo(positionMs) }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long): Unit = unlessLocked { super.seekTo(mediaItemIndex, positionMs) }

    override fun seekToDefaultPosition(): Unit = unlessLocked { super.seekToDefaultPosition() }

    override fun seekToDefaultPosition(mediaItemIndex: Int): Unit = unlessLocked { super.seekToDefaultPosition(mediaItemIndex) }

    override fun setPlaybackSpeed(speed: Float): Unit = unlessLocked { super.setPlaybackSpeed(speed) }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters): Unit =
        unlessLocked { super.setPlaybackParameters(playbackParameters) }

    // MARK: - Metadata

    /**
     * `<AdTitle>` when the response carried one, and the SDK's own localised word
     * otherwise — a lock screen reading "Advertisement" in an Armenian app is the
     * same mistake an English skip control would be. `<Advertiser>` (§3.9) is the
     * artist, left out rather than guessed at when the response does not say.
     */
    override fun getMediaMetadata(): MediaMetadata = describedAd ?: super.getMediaMetadata()

    // MARK: - Listeners

    /**
     * Registered on the wrapped player through a filter that answers with this
     * player's commands and metadata, so a listener is never told the creative's
     * neighbours are a tap away while the notification says otherwise.
     */
    override fun addListener(listener: Player.Listener) {
        val filtered = Filtered(listener)
        listeners[listener] = filtered
        wrappedPlayer.addListener(filtered)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let(wrappedPlayer::removeListener)
    }

    /**
     * Forwards every callback by hand. Kotlin's `by` delegation does not forward
     * an interface's Java default methods — and every `Player.Listener` method is
     * one — so a delegating filter passed on only what it overrode, and a
     * `MediaSession` behind it never heard the player start, stop or buffer: the
     * system showed a session stuck at "buffering", and a remote's play/pause key
     * did nothing.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class Filtered(private val listener: Player.Listener) : Player.Listener {
        private val self: Player get() = this@VastMediaSessionPlayer

        override fun onEvents(player: Player, events: Player.Events) = listener.onEvents(self, events)

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) = listener.onAvailableCommandsChanged(self.availableCommands)

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = listener.onMediaMetadataChanged(self.mediaMetadata)

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = listener.onTimelineChanged(timeline, reason)

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = listener.onMediaItemTransition(mediaItem, reason)

        override fun onTracksChanged(tracks: Tracks) = listener.onTracksChanged(tracks)

        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) = listener.onPlaylistMetadataChanged(mediaMetadata)

        override fun onIsLoadingChanged(isLoading: Boolean) = listener.onIsLoadingChanged(isLoading)

        override fun onLoadingChanged(isLoading: Boolean) = listener.onLoadingChanged(isLoading)

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) = listener.onTrackSelectionParametersChanged(parameters)

        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = listener.onPlayerStateChanged(playWhenReady, playbackState)

        override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(playbackState)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = listener.onPlayWhenReadyChanged(playWhenReady, reason)

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
            listener.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)

        override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)

        override fun onRepeatModeChanged(repeatMode: Int) = listener.onRepeatModeChanged(repeatMode)

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = listener.onShuffleModeEnabledChanged(shuffleModeEnabled)

        override fun onPlayerError(error: PlaybackException) = listener.onPlayerError(error)

        override fun onPlayerErrorChanged(error: PlaybackException?) = listener.onPlayerErrorChanged(error)

        override fun onPositionDiscontinuity(reason: Int) = listener.onPositionDiscontinuity(reason)

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
            listener.onPositionDiscontinuity(oldPosition, newPosition, reason)

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = listener.onPlaybackParametersChanged(playbackParameters)

        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) = listener.onSeekBackIncrementChanged(seekBackIncrementMs)

        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) = listener.onSeekForwardIncrementChanged(seekForwardIncrementMs)

        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
            listener.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)

        override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)

        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) = listener.onAudioAttributesChanged(audioAttributes)

        override fun onVolumeChanged(volume: Float) = listener.onVolumeChanged(volume)

        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)

        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = listener.onDeviceInfoChanged(deviceInfo)

        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = listener.onDeviceVolumeChanged(volume, muted)

        override fun onVideoSizeChanged(videoSize: VideoSize) = listener.onVideoSizeChanged(videoSize)

        override fun onSurfaceSizeChanged(width: Int, height: Int) = listener.onSurfaceSizeChanged(width, height)

        override fun onRenderedFirstFrame() = listener.onRenderedFirstFrame()

        override fun onCues(cues: List<Cue>) = listener.onCues(cues)

        override fun onCues(cueGroup: CueGroup) = listener.onCues(cueGroup)

        override fun onMetadata(metadata: Metadata) = listener.onMetadata(metadata)
    }

    // MARK: - Break lifetime

    private val locks: Boolean get() = breakActive && policy != VastNowPlayingPolicy.UNTOUCHED

    private fun refresh(active: Boolean, ad: VastAd?) {
        val commandsChanged = active != breakActive && policy != VastNowPlayingPolicy.UNTOUCHED
        breakActive = active
        val described = if (active && ad != null && policy == VastNowPlayingPolicy.DESCRIBES_AD) {
            MediaMetadata.Builder()
                .setTitle(ad.title?.takeIf { it.isNotBlank() } ?: fallbackTitle)
                .setArtist(ad.advertiser)
                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                .build()
        } else {
            null
        }
        val metadataChanged = described != describedAd
        describedAd = described
        if (!commandsChanged && !metadataChanged) return

        val flags = FlagSet.Builder()
        if (commandsChanged) flags.add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
        if (metadataChanged) flags.add(Player.EVENT_MEDIA_METADATA_CHANGED)
        val events = Player.Events(flags.build())
        for (listener in listeners.keys.toList()) {
            if (commandsChanged) listener.onAvailableCommandsChanged(availableCommands)
            if (metadataChanged) listener.onMediaMetadataChanged(mediaMetadata)
            listener.onEvents(this, events)
        }
    }

    private inline fun unlessLocked(action: () -> Unit) {
        if (!locks) action()
    }

    private companion object {
        /**
         * Everything that would move the playhead or leave the creative, and the
         * speed control — watched time is measured from the playhead, so an ad at
         * 2× is watched in half the time.
         */
        val LOCKED_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH,
        )
    }
}

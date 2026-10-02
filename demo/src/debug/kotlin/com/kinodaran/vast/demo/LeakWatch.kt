package com.kinodaran.vast.demo

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import leakcanary.AppWatcher

/**
 * Hands what a closed player screen released to LeakCanary, which reports any
 * of it still reachable five seconds later — a session the process lifecycle
 * still observes, say, or a player a callback still holds.
 *
 * Not at once: Media3 1.11.1 holds a released ExoPlayer, and everything it
 * references — the session, through its media source factory — until its
 * stuck-playing timeout. `StuckPlayerDetector.release()` clears its messages and
 * then removes its listener, and removing a listener delivers the events still
 * pending, which posts a fresh timeout. Watching after that timeout keeps the
 * check about what the SDK and this app hold.
 */
@OptIn(UnstableApi::class)
internal fun expectReleased(vararg released: Pair<Any, String>) {
    val media3Hold = ExoPlayer.Builder.DEFAULT_STUCK_PLAYING_DETECTION_TIMEOUT_MS.toLong()
    Handler(Looper.getMainLooper()).postDelayed({
        for ((instance, description) in released) AppWatcher.objectWatcher.expectWeaklyReachable(instance, description)
    }, media3Hold)
}

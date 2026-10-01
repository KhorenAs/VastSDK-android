package com.kinodaran.vast.core

/**
 * One observation of the ad playhead.
 *
 * The tracking engine never asks a player for the time — time is pushed in.
 * That makes every rule (quartiles, seek rejection, asset-swap detection) a pure
 * function of two consecutive ticks, and therefore unit-testable without a
 * player, a media file, or real elapsed time.
 */
public data class VastTick(
    /** Playhead inside the ad creative, in seconds. */
    val adTime: Double,
    /** Duration reported by the player; falls back to `<Duration>` when unknown. */
    val duration: Double?,
    /** `0` while paused/stalled. Quartiles only count at normal speed (§3.14.1). */
    val rate: Float,
    /** Monotonic wall clock in seconds, used to tell natural playback from a seek. */
    val wallClock: Double,
    /** `false` once the host swapped the player's item out from under the ad. */
    val itemIsOurs: Boolean = true,
    val isMuted: Boolean = false,
)

package com.kinodaran.vast.kit

import com.kinodaran.vast.core.VastTick
import kotlinx.coroutines.flow.Flow

/**
 * Source of playhead observations for the tracking engine.
 *
 * An interface for two reasons: tests drive a scripted tick sequence with no
 * player at all, and a host that already runs its own playback-time accounting
 * can feed that in directly. An injected clock is reused for every ad in a pod,
 * so [ticks] must be collectable again after [stop]. The built-in clock is
 * instead rebuilt per ad, because it binds to that ad's place in the timeline.
 */
public interface VastClock {
    public fun ticks(): Flow<VastTick>

    public fun stop()
}

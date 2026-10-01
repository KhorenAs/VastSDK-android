package com.kinodaran.vast.kit

import com.kinodaran.vast.core.VastBeacon

/**
 * Sends the beacons the engine produced. Fire-and-forget: a tracking failure
 * must never interrupt playback, so an implementation reports nothing back and
 * throws nothing out.
 */
public interface VastBeaconTransport {
    public suspend fun fire(beacons: List<VastBeacon>)
}

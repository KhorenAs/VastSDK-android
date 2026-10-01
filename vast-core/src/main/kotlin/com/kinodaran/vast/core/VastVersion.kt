package com.kinodaran.vast.core

/**
 * This SDK's own version.
 *
 * Hard-coded rather than read from build config: `vast-core` is a plain JVM
 * library with no generated sources, and a version that reads as "unknown" in a
 * release build is worse than one that has to be edited at release time. It
 * reaches ad servers through `[CLIENTUA]`, which is how an exchange tells one
 * player from another when a creative misbehaves.
 */
public object VastVersion {
    public const val CURRENT: String = "0.1.0"
}

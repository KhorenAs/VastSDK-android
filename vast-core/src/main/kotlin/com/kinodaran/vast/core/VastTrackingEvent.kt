package com.kinodaran.vast.core

/**
 * Every event name VAST 4.3 §3.14.1 allows under `<TrackingEvents>`.
 *
 * `progress` is intentionally absent: it carries an `offset` attribute and is
 * modelled by [VastAd.ProgressEvent] instead.
 */
public enum class VastTrackingEvent(public val vastName: String) {

    // Player operation metrics
    MUTE("mute"),
    UNMUTE("unmute"),
    PAUSE("pause"),
    RESUME("resume"),
    REWIND("rewind"),
    SKIP("skip"),
    PLAYER_EXPAND("playerExpand"),
    PLAYER_COLLAPSE("playerCollapse"),
    NOT_USED("notUsed"),

    // VAST 2.0/3.0 names. Live ad servers still emit these — a real AdFox tag
    // sends `fullscreen`, `expand`, `collapse`, `acceptInvitation` and `close` —
    // so dropping them would silently lose tracking the server is waiting for.
    // They are kept distinct from their 4.x replacements rather than rewritten,
    // because the URIs behind them are the server's, not ours.
    FULLSCREEN("fullscreen"),
    EXIT_FULLSCREEN("exitFullscreen"),
    EXPAND("expand"),
    COLLAPSE("collapse"),
    ACCEPT_INVITATION("acceptInvitation"),
    CLOSE("close"),

    // Linear ad metrics
    LOADED("loaded"),
    START("start"),
    FIRST_QUARTILE("firstQuartile"),
    MIDPOINT("midpoint"),
    THIRD_QUARTILE("thirdQuartile"),
    COMPLETE("complete"),
    OTHER_AD_INTERACTION("otherAdInteraction"),
    CLOSE_LINEAR("closeLinear"),

    CREATIVE_VIEW("creativeView"),
    ;

    /**
     * Quartiles require continuous playback at normal speed (§3.14.1), so a
     * forward seek past one of these must NOT fire it.
     */
    public val requiresContinuousPlayback: Boolean
        get() = this == FIRST_QUARTILE || this == MIDPOINT || this == THIRD_QUARTILE || this == COMPLETE

    /** Events that may be sent at most once per ad. */
    public val isOnce: Boolean
        get() = when (this) {
            MUTE, UNMUTE, PAUSE, RESUME, REWIND, OTHER_AD_INTERACTION,
            FULLSCREEN, EXIT_FULLSCREEN, EXPAND, COLLAPSE, ACCEPT_INVITATION,
            -> false
            else -> true
        }

    /**
     * Whether a host may report this event directly.
     *
     * Quartiles, `start` and `complete` are derived from observed playback
     * instead, so that a caller cannot fabricate a billable event.
     */
    public val isHostReportable: Boolean
        get() = when (this) {
            START, FIRST_QUARTILE, MIDPOINT, THIRD_QUARTILE, COMPLETE, CREATIVE_VIEW, SKIP, LOADED -> false
            else -> true
        }

    /**
     * Names that mean the same thing across VAST versions.
     *
     * VAST 4.3 §3.14.1 states that `playerExpand` replaces `fullscreen` and
     * `playerCollapse` replaces `exitFullscreen`, but servers keep sending the
     * old spelling. Firing one fires every equivalent, so a host reports the
     * event once and whichever name the server used is honoured.
     */
    public val equivalents: List<VastTrackingEvent>
        get() = when (this) {
            PLAYER_EXPAND, FULLSCREEN, EXPAND -> listOf(PLAYER_EXPAND, FULLSCREEN, EXPAND)
            PLAYER_COLLAPSE, EXIT_FULLSCREEN, COLLAPSE -> listOf(PLAYER_COLLAPSE, EXIT_FULLSCREEN, COLLAPSE)
            CLOSE_LINEAR, CLOSE -> listOf(CLOSE_LINEAR, CLOSE)
            else -> listOf(this)
        }

    public companion object {
        /** The event a `<Tracking event="...">` names, matched exactly as the spec spells it. */
        public fun fromVastName(name: String): VastTrackingEvent? = entries.firstOrNull { it.vastName == name }
    }
}

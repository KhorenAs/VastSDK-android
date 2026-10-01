package com.kinodaran.vast.core

/**
 * The §6 macro values only the host can supply.
 *
 * Every one of these is something the SDK must not decide for itself. An
 * advertising identifier requires the host to have asked the viewer for
 * permission; a consent string comes from a CMP the host chose; whether this
 * break is a pre-roll is a fact about the host's own timeline. The SDK reporting
 * a guess in any of those places would be worse than reporting nothing, which is
 * what an unsupplied macro does — §6 gives it `-1`, meaning "not known".
 *
 * Left empty, every macro here goes out as `-1`. That is the correct default and
 * also a real cost: an ad server with no `IFA` and no `APPBUNDLE` will often not
 * bid at all. Filling these in is the difference between a request an exchange
 * can price and one it cannot.
 */
public data class VastMacroValues(
    /**
     * `[IFA]` — the advertising identifier, if the host has permission to use one.
     * On Android that is the Google advertising ID with the viewer's consent;
     * without it, leave this `null` rather than sending a zeroed identifier.
     */
    val identifierForAdvertising: String? = null,
    /**
     * `[IFATYPE]` — what kind of identifier [identifierForAdvertising] is, in the
     * vendor-agnostic spelling the spec asks for: `aaid`, `sessionid`.
     */
    val identifierType: String? = null,
    /** `[LIMITADTRACKING]` — whether the viewer asked not to be tracked. `null` means unknown, which is different from `false`. */
    val limitsAdTracking: Boolean? = null,
    /** `[GDPRCONSENT]` — the IAB TCF consent string, straight from the CMP. */
    val gdprConsent: String? = null,
    /** `[REGULATIONS]` — which regimes apply to this request, e.g. `gdpr` or `coppa`. */
    val regulations: String? = null,
    /**
     * `[DEVICEUA]` — the device's browser user agent.
     *
     * Host-supplied because obtaining it means instantiating a `WebView`, and this
     * SDK deliberately has none. A host that does not have one should leave this
     * alone rather than inventing a plausible string, which is worse for fraud
     * scoring than an honest blank.
     */
    val deviceUserAgent: String? = null,
    /** `[PLACEMENTTYPE]` — where the player sits, in AdCOM's numbering. */
    val placementType: Int? = null,
    /**
     * `[BREAKPOSITION]` — 1 pre-roll, 2 mid-roll, 3 post-roll, 0 unknown.
     *
     * The SDK cannot know this: it is handed one break at a time and never sees
     * the content timeline that decides where the break sits.
     */
    val breakPosition: Int? = null,
    /** `[CONTENTID]` — the host's own identifier for what the ad interrupted. */
    val contentId: String? = null,
    /** `[CONTENTURI]` — where that content came from. */
    val contentUri: String? = null,
    /**
     * Vendor macros this SDK knows nothing about.
     *
     * Keyed without brackets: `mapOf("CORRELATOR" to "1234")` replaces
     * `[CORRELATOR]`. Anything §6 does not define is left alone when it is not
     * here, because the spec is explicit that unknown macros must not all become `-1`.
     */
    val custom: Map<String, String> = emptyMap(),
)

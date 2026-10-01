package com.kinodaran.vast.kit

import com.kinodaran.vast.core.VastAd

/**
 * The one compliance promise the SDK cannot verify on its own: with
 * [VastSkipPresentation.SDK], a skippable ad is offered a skip control (§2.3),
 * and that control has to be somewhere a viewer can reach.
 *
 * What can be detected is a host that never composed the ad surface, or gave it
 * no room, or a replaced skip control that draws nothing. A host deliberately
 * drawing something above the ad cannot be: Compose does not expose occlusion,
 * and that is a decision rather than a mistake.
 *
 * Judged only when the control is due, seconds into playback. Asking earlier
 * cannot tell "missing" from "not laid out yet". Reported, never fatal: this is
 * a heuristic about someone else's layout, and a wrong heuristic must not take
 * the host's app down with it.
 */
internal class VastCompliance(private val session: VastAdSession) {

    private var surfaceDp: Pair<Float, Float>? = null
    private var skipControlDp: Pair<Float, Float>? = null

    /** One complaint per ad: the control is re-measured on every layout pass. */
    private var reportedSkipControlProblem = false

    fun reset() {
        reportedSkipControlProblem = false
        skipControlDp = null
    }

    fun noteSurface(widthDp: Float, heightDp: Float) {
        surfaceDp = widthDp to heightDp
    }

    fun noteSkipControl(widthDp: Float, heightDp: Float) {
        skipControlDp = widthDp to heightDp
        val ad = session.currentSlot?.ad ?: return
        if (session.skipPresentation(ad) != VastSkipPresentation.SDK || !ad.isSkippable || reportedSkipControlProblem) return
        val diagnosis = skipControlDiagnosis() ?: return
        report(ad, diagnosis)
    }

    /** At the moment the control comes due. */
    fun verifySkipSurface(ad: VastAd) {
        if (session.skipPresentation(ad) != VastSkipPresentation.SDK || !ad.isSkippable) return
        // No control was drawn to measure — the response handed the UI to the host.
        if (session.suppressesAdUi(ad)) return
        val diagnosis = surfaceDiagnosis() ?: return
        report(ad, diagnosis)
    }

    /**
     * §3.10.1 makes ClickThrough support Required, and the surface is how the SDK
     * provides it — except on a television, where a transparent layer under a
     * D-pad takes no focus. Saying so is the difference between a host choosing
     * `HOST` and a host shipping an ad nobody can click.
     */
    fun verifyClickPath(ad: VastAd) {
        if (!session.isTelevision || session.configuration.clickPresentation != VastClickPresentation.SURFACE) return
        if (ad.linear.clickThrough == null) return
        val reason = "clickPresentation is SURFACE, but a TV has no pointer and a transparent layer cannot take " +
            "D-pad focus, so this ad has no click path. Set clickPresentation to HOST and call click() from a " +
            "focusable control of your own, or to DISABLED to opt out knowingly."
        VastLog.warning("click path unavailable: $reason")
        session.listener?.onClickThroughUnavailable(ad, reason)
    }

    private fun report(ad: VastAd, reason: String) {
        reportedSkipControlProblem = true
        VastLog.warning("skip control unavailable: $reason")
        session.listener?.onSkipControlUnavailable(ad, reason)
    }

    private fun surfaceDiagnosis(): String? {
        val (width, height) = surfaceDp
            ?: return "VastAdSurface was never laid out, so the skip control this ad requires cannot be shown. " +
                "Compose VastAdSurface over your player, or set skipPresentation to HOST or UNSUPPORTED."
        if (width >= MINIMUM_USABLE_EDGE_DP && height >= MINIMUM_USABLE_EDGE_DP) return null
        return "VastAdSurface is ${width.toInt()}×${height.toInt()}dp, too small to carry a skip control. " +
            "Give it the player's bounds rather than wrapping it in a sized container."
    }

    private fun skipControlDiagnosis(): String? {
        val (width, height) = skipControlDp ?: return null
        if (width >= MINIMUM_USABLE_EDGE_DP && height >= MINIMUM_USABLE_EDGE_DP) return null
        return "The skip control drew at ${width.toInt()}×${height.toInt()}dp, too small for a viewer to hit. " +
            "A skip slot that draws an empty or unsized composable leaves a skippable ad with no way to skip it."
    }

    companion object {
        /** Android's minimum touch target. Below it in either dimension there is nowhere to put a usable control. */
        const val MINIMUM_USABLE_EDGE_DP = 48f
    }
}

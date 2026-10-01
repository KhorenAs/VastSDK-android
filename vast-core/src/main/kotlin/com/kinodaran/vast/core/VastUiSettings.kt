package com.kinodaran.vast.core

/**
 * The one vendor convention this SDK interprets.
 *
 * `<Extension type="uiSettings">` is nobody's standard: §3.18 leaves
 * `<Extensions>` entirely to vendors, and [VastAd.Extension.value] deliberately
 * stops at *reading* a key rather than deciding what it means. This is the
 * single exception to that, kept in one file so the exception stays countable —
 * and surfaced only through [VastAd.isUiHidden].
 *
 * It lives in `vast-core` because a host depending on `vast-core` alone — its
 * own player, its own UI — is exactly the host that most needs the answer.
 */
internal object VastUiSettings {

    const val EXTENSION_TYPE = "uiSettings"

    /**
     * Both spellings seen in the wild. The live AdFox tag this SDK was tested
     * against sends `<UiHideable>`; `<UiHidden>` is the spelling used elsewhere in
     * the same family. Reading both costs nothing, and missing the key costs a
     * whole break drawn with the wrong UI — silently, because the response still
     * looks fine.
     */
    val hiddenKeys = listOf("UiHidden", "UiHideable")

    /**
     * Whether this ad's response asks the player to draw no UI of its own.
     *
     * Presence is the signal, as the vendor intends: `<UiHidden/>` with no text
     * counts. Only an explicitly negative value is read as "no", so a server that
     * sends `0` to mean "keep your UI" is honoured rather than inverted.
     */
    fun asksForHostDrawnUi(ad: VastAd): Boolean {
        for (vendor in ad.extensions) {
            if (!vendor.type.equals(EXTENSION_TYPE, ignoreCase = true)) continue
            for (key in hiddenKeys) {
                val value = vendor.value(key) ?: continue
                return isAffirmative(value)
            }
        }
        return false
    }

    private fun isAffirmative(value: String): Boolean = when (value.trim().lowercase()) {
        "0", "false", "no" -> false
        else -> true
    }
}

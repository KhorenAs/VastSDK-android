package com.kinodaran.vast.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Substitutes VAST 4.3 §6 macros into a tracking URI.
 *
 * Three rules from the spec drive the whole implementation:
 *
 * - A macro is its name *including* the brackets, and the whole thing is
 *   replaced: `?e=[ERRORCODE]` becomes `?e=403`, not `?e=[403]`.
 * - `encodeURIComponent` is applied to each **value**, not to the finished
 *   string — so a comma separating two array values stays a comma while a comma
 *   inside one value does not.
 * - A macro the spec defines but this player does not supply becomes `-1`
 *   (unknown) or `-2` (known but withheld). A macro the spec does *not* define is
 *   left exactly as it was: "do not replace all unknown macros with -1".
 */
public class VastMacroExpander {

    /**
     * What this player can tell the ad server about the moment being reported.
     *
     * Every field is optional because honesty is the point: a value left `null`
     * is reported as unknown rather than guessed at.
     */
    public data class Context(
        val errorCode: VastError? = null,
        /**
         * Why a verification vendor's code did not run, for `[REASON]` on a
         * `verificationNotExecuted` tracker (§3.16). Unset on every other beacon.
         */
        val verificationNotExecutedReason: VastVerification.NotExecutedReason? = null,
        /** Playhead within the ad creative, in seconds. */
        val adPlayhead: Double? = null,
        /** Playhead within the content the ad interrupted, in seconds. */
        val contentPlayhead: Double? = null,
        val assetUri: String? = null,
        /** Milliseconds since the epoch. Defaults to the moment of expansion when `null`. */
        val timestampMillis: Long? = null,
        /** Pinned in tests; a fresh random value otherwise. */
        val cacheBuster: String? = null,
        val playerSize: Size? = null,
        val isMuted: Boolean? = null,
        val isFullscreen: Boolean? = null,
        val appBundle: String? = null,
        /**
         * Vendors named in the ad's `<AdVerifications>`. Reported as unknown while
         * empty, which is the difference between "nobody asked" and "somebody
         * asked and we are not saying".
         */
        val verificationVendors: List<String> = emptyList(),
        /** The OMID partner name and version, when a measurement integration is present to have one. */
        val omidPartner: String? = null,
        /** Vendor macros this SDK knows nothing about, supplied by the host. */
        val custom: Map<String, String> = emptyMap(),
        /**
         * Everything only the host can answer — identifier, consent, where the
         * break sits. Unset values report `-1`, which is what §6 means by
         * "unknown" and an honest thing to say.
         */
        val host: VastMacroValues = VastMacroValues(),
        /** The MIME type of the creative being played, for `[MEDIAMIME]`. */
        val mediaMimeType: String? = null,
        /**
         * `[TRANSACTIONID]` — one value for one break, so an ad server can tie this
         * request's beacons together.
         */
        val transactionId: String? = null,
        /**
         * Whether something will execute the ad's verification code, which is what
         * `[APIFRAMEWORKS]` is being asked about.
         */
        val executesOmid: Boolean = false,
    )

    /** A player size in pixels, for `[PLAYERSIZE]`. */
    public data class Size(val width: Int, val height: Int)

    /**
     * Expands a URI held as a request target, as the session does before sending
     * a beacon.
     *
     * Any bracket still standing afterwards belongs to a macro this player left
     * alone — a vendor's own. Brackets are not legal in a query, so they are
     * re-encoded, and only they are: every other escape is left exactly as it
     * was, so a value already encoded correctly is never encoded twice.
     */
    public fun expandUrl(url: String, context: Context): String =
        expand(url, context).replace("[", "%5B").replace("]", "%5D")

    /**
     * String form, so a caller holding a template rather than a request target —
     * and the tests — can use the same code path. Unexpanded brackets are left as
     * text here; only [expandUrl] re-encodes them.
     */
    public fun expand(string: String, context: Context): String {
        val source = normalisingEncodedBrackets(string)
        val result = StringBuilder()
        var index = 0

        while (true) {
            val open = source.indexOf('[', index)
            if (open < 0) break
            result.append(source, index, open)

            val close = source.indexOf(']', open + 1)
            if (close < 0) {
                // An unbalanced bracket is just text.
                result.append(source, open, source.length)
                return result.toString()
            }

            val name = source.substring(open + 1, close)
            result.append(replacement(name, context) ?: source.substring(open, close + 1))
            index = close + 1
        }

        return result.append(source, index, source.length).toString()
    }

    // MARK: - Values

    /** `null` means "leave the macro alone" — it is not one the spec defines. */
    private fun replacement(name: String, context: Context): String? {
        context.custom[name]?.let { return encode(it) }
        val host = context.host

        return when (name) {
            "ERRORCODE" -> context.errorCode?.code?.toString() ?: UNKNOWN_VALUE

            "REASON" -> context.verificationNotExecutedReason?.code?.toString() ?: UNKNOWN_VALUE

            "VERIFICATIONVENDORS" ->
                if (context.verificationVendors.isEmpty()) UNKNOWN_VALUE else encode(context.verificationVendors.joinToString(","))

            "OMIDPARTNER" -> context.omidPartner?.let(::encode) ?: UNKNOWN_VALUE

            // MEDIAPLAYHEAD is the pre-4.1 spelling of the same value.
            "ADPLAYHEAD", "MEDIAPLAYHEAD" -> context.adPlayhead?.let { encode(timecode(it)) } ?: UNKNOWN_VALUE

            "CONTENTPLAYHEAD" -> context.contentPlayhead?.let { encode(timecode(it)) } ?: UNKNOWN_VALUE

            // The stored form of the URI, as the Apple SDK's `absoluteString` is, so a
            // space already written as `%20` is encoded once more and nowhere else.
            "ASSETURI" -> context.assetUri?.let { encode(VastUrls.normalise(it)) } ?: UNKNOWN_VALUE

            "TIMESTAMP" -> encode(iso8601(context.timestampMillis ?: System.currentTimeMillis()))

            // Always answerable, and the whole point is that it differs per request.
            "CACHEBUSTING" -> context.cacheBuster ?: randomCacheBuster()

            "PLAYERSTATE" -> encode(playerState(context))

            // An array macro: values are comma separated and the comma is not encoded.
            "PLAYERSIZE" -> context.playerSize?.let { "${it.width},${it.height}" } ?: UNKNOWN_VALUE

            "APPBUNDLE" -> context.appBundle?.let(::encode) ?: UNKNOWN_VALUE

            // MARK: Host-supplied

            "IFA" -> host.identifierForAdvertising?.let(::encode) ?: UNKNOWN_VALUE

            "IFATYPE" -> host.identifierType?.let(::encode) ?: UNKNOWN_VALUE

            // Reported as the spec's booleans, and only when it is known: `false`
            // and "nobody asked" are different facts about a viewer.
            "LIMITADTRACKING" -> host.limitsAdTracking?.let { if (it) "1" else "0" } ?: UNKNOWN_VALUE

            // Already base64url by the time a CMP produces it, so encoding it again
            // would corrupt it.
            "GDPRCONSENT" -> host.gdprConsent ?: UNKNOWN_VALUE

            "REGULATIONS" -> host.regulations?.let(::encode) ?: UNKNOWN_VALUE

            "DEVICEUA" -> host.deviceUserAgent?.let(::encode) ?: UNKNOWN_VALUE

            "PLACEMENTTYPE" -> host.placementType?.toString() ?: UNKNOWN_VALUE

            "BREAKPOSITION" -> host.breakPosition?.toString() ?: UNKNOWN_VALUE

            "CONTENTID" -> host.contentId?.let(::encode) ?: UNKNOWN_VALUE

            "CONTENTURI" -> host.contentUri?.let { encode(VastUrls.normalise(it)) } ?: UNKNOWN_VALUE

            // MARK: Answerable by the SDK itself

            "MEDIAMIME" -> context.mediaMimeType?.let(::encode) ?: UNKNOWN_VALUE

            "TRANSACTIONID" -> context.transactionId?.let(::encode) ?: UNKNOWN_VALUE

            // 0 is "the client made this request", which is always true here: there
            // is no server-side stitching in this SDK to be unsure about.
            "SERVERSIDE" -> "0"

            // The only creative this SDK plays is a Linear video.
            "ADTYPE" -> "video"

            // The spec's `name/version` form, naming the SDK rather than the app:
            // the app is `[APPBUNDLE]`, and conflating the two loses both. The same
            // name as the Apple SDK; the version tells the two apart.
            "CLIENTUA" -> encode("VASTSDK/${VastVersion.CURRENT}")

            // What the parser accepts, not what this document happens to be.
            "VASTVERSIONS" -> SUPPORTED_VAST_VERSIONS

            // 7 is OMID-1 in AdCOM's list, and it is only true when something is
            // actually there to execute a vendor's code. Claiming it otherwise would
            // tell a verification vendor to expect a session that never starts.
            "APIFRAMEWORKS" -> if (context.executesOmid) "7" else UNKNOWN_VALUE

            // Defined by the spec but not supplied: report unknown. Not defined by
            // the spec: leave it for whoever put it there.
            else -> if (name in SPEC_DEFINED) UNKNOWN_VALUE else null
        }
    }

    /** `PLAYERSTATE` is an array of flags; an empty state is still an answer. */
    private fun playerState(context: Context): String {
        val flags = mutableListOf<String>()
        if (context.isMuted == true) flags += "muted"
        if (context.isFullscreen == true) flags += "fullscreen"
        return flags.joinToString(",")
    }

    public companion object {
        /** Reported for a value that is known but must not be shared. */
        public const val WITHHELD_VALUE: String = "-2"

        /** Reported for a macro the spec defines but this player does not know. */
        internal const val UNKNOWN_VALUE = "-1"

        /**
         * The VAST versions the parser accepts, for `[VASTVERSIONS]`.
         *
         * A list rather than one number: the parser is deliberately version-
         * tolerant, and an ad server that knows it may send VAST 2 will send its
         * VAST 2.
         */
        internal const val SUPPORTED_VAST_VERSIONS = "2,3,4,4.1,4.2,4.3"

        /** Macros §6 defines. Only these become `-1` when unsupplied; anything else is somebody's vendor extension. */
        internal val SPEC_DEFINED: Set<String> = setOf(
            "ERRORCODE", "ADPLAYHEAD", "MEDIAPLAYHEAD", "CONTENTPLAYHEAD",
            "ASSETURI", "TIMESTAMP", "CACHEBUSTING", "PLAYERSTATE",
            "PLAYERSIZE", "PLAYERCAPABILITIES", "APPBUNDLE", "DOMAIN",
            "PAGEURL", "IFA", "IFATYPE", "CLIENTUA", "DEVICEUA", "DEVICEIP",
            "SERVERSIDE", "ADTYPE", "ADCATEGORIES", "BREAKPOSITION",
            "BLOCKEDADCATEGORIES", "CLICKTYPE", "GDPRCONSENT", "LIMITADTRACKING",
            "REGULATIONS", "TRANSACTIONID", "PLACEMENTTYPE", "INVENTORYSTATE",
            "CONTENTID", "CONTENTURI", "MEDIAMIME", "OMIDPARTNER", "VASTVERSIONS",
            "APIFRAMEWORKS", "EXTENSIONS", "VERIFICATIONVENDORS", "REASON",
        )

        private val ENCODED_MACRO = Regex("%5B([A-Za-z0-9_]+)%5D", RegexOption.IGNORE_CASE)

        /**
         * Restores brackets that became `%5B`/`%5D`.
         *
         * URIs are stored with their brackets encoded (see [VastAd]), so a macro
         * arrives here as `%5BERRORCODE%5D`. Scanning for `[` alone would find
         * nothing, and every macro would go out unexpanded — silently, because the
         * URI still looks plausible.
         *
         * Only bracket pairs wrapping a macro-shaped name are touched, so an
         * encoded bracket that is genuinely part of a value is left as it is.
         */
        internal fun normalisingEncodedBrackets(string: String): String {
            if (!string.contains("%5B", ignoreCase = true)) return string
            return ENCODED_MACRO.replace(string, "[$1]")
        }

        /** `HH:MM:SS.mmm`, the form §6 specifies for playhead values. */
        internal fun timecode(seconds: Double): String {
            if (!seconds.isFinite() || seconds < 0) return "00:00:00.000"
            val total = seconds.toInt()
            val milliseconds = ((seconds - total) * 1000).toInt()
            return String.format(
                Locale.US, "%02d:%02d:%02d.%03d",
                total / 3600, (total % 3600) / 60, total % 60, milliseconds,
            )
        }

        /** Internet date-time in UTC, as Foundation's `.withInternetDateTime` writes it. */
        internal fun iso8601(epochMillis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(epochMillis))

        internal fun randomCacheBuster(): String = String.format(Locale.US, "%08d", Random.nextInt(0, 100_000_000))

        private const val UNRESERVED = "-._~"
        private const val HEX = "0123456789ABCDEF"

        /**
         * Percent-encodes one value. Applied per value rather than to the finished
         * URI, which is what keeps array separators intact. Everything outside
         * RFC 3986's unreserved set is encoded, so `&`, `=`, `+` and `?` cannot
         * break the query a value is placed into.
         */
        internal fun encode(value: String): String {
            val out = StringBuilder(value.length)
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val unsigned = byte.toInt() and 0xFF
                val char = unsigned.toChar()
                if (unsigned < 0x80 && (char.isLetterOrDigit() || char in UNRESERVED)) {
                    out.append(char)
                } else {
                    out.append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0F])
                }
            }
            return out.toString()
        }
    }
}

package com.kinodaran.vast.core

import com.kinodaran.vast.core.VastMacroExpander.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * VAST 4.3 §6. The rules are short but each has a way of going wrong that is
 * invisible until an ad server reports nonsense.
 */
class VastMacroExpanderTest {

    private val expander = VastMacroExpander()

    /** A URI as the parser stores it, brackets encoded — the form every beacon actually has. */
    private fun stored(uri: String) = VastUrls.normalise(uri)

    // MARK: - Replacement

    /** The brackets are part of the macro and go with it. */
    @Test
    fun macroIsReplacedIncludingItsBrackets() {
        val result = expander.expand("https://ads.test/e?code=[ERRORCODE]", Context(errorCode = VastError.NO_SUPPORTED_MEDIA_FILE))
        assertEquals("https://ads.test/e?code=403", result)
    }

    /** The bug the whole file exists to prevent: an unexpanded macro reaches the ad server as text. */
    @Test
    fun errorCodeIsNeverLeftAsLiteralText() {
        val result = expander.expand("https://ads.test/e?code=[ERRORCODE]", Context(errorCode = VastError.WRAPPER_LIMIT_REACHED))
        assertFalse(result.contains("[ERRORCODE]"))
        assertTrue(result.endsWith("=302"))
    }

    @Test
    fun severalMacrosInOneUri() {
        val result = expander.expand(
            "https://ads.test/t?p=[ADPLAYHEAD]&cb=[CACHEBUSTING]&e=[ERRORCODE]",
            Context(errorCode = VastError.MEDIA_FILE_TIMEOUT, adPlayhead = 12.5, cacheBuster = "12345678"),
        )
        assertEquals("https://ads.test/t?p=00%3A00%3A12.500&cb=12345678&e=402", result)
    }

    // MARK: - Unknown values

    /** §6: a macro the spec defines but the player does not supply is `-1`. */
    @Test
    fun specMacroWithNoValueBecomesMinusOne() {
        assertEquals("https://ads.test/t?p=-1", expander.expand("https://ads.test/t?p=[ADPLAYHEAD]", Context()))
    }

    /**
     * §6, verbatim: "do not replace all unknown macros with -1, only do this for
     * macros specifically mentioned in this section".
     */
    @Test
    fun macroTheSpecDoesNotDefineIsLeftUntouched() {
        assertEquals("https://ads.test/t?x=[ADFOX_SOMETHING]", expander.expand("https://ads.test/t?x=[ADFOX_SOMETHING]", Context()))
    }

    @Test
    fun hostSuppliedCustomMacroIsSubstituted() {
        val result = expander.expand("https://ads.test/t?x=[ADFOX_SOMETHING]", Context(custom = mapOf("ADFOX_SOMETHING" to "kinodaran")))
        assertEquals("https://ads.test/t?x=kinodaran", result)
    }

    // MARK: - Encoding

    /** §6: encode each value, not the finished string — otherwise the `?` and `&` that make the URI a URI get encoded too. */
    @Test
    fun valuesAreEncodedButTheUriStructureIsNot() {
        val result = expander.expand("https://ads.test/t?asset=[ASSETURI]&next=1", Context(assetUri = "https://cdn.test/a b.mp4?v=2&x=3"))
        assertTrue(result.startsWith("https://ads.test/t?asset="))
        assertTrue(result.endsWith("&next=1"), "the URI's own separators survived")
        assertTrue(result.contains("%3F"), "the value's own '?' was encoded")
        assertTrue(result.contains("%26"), "the value's own '&' was encoded")
    }

    /** An array macro separates values with commas, and those commas stay commas. */
    @Test
    fun arrayMacroKeepsItsSeparators() {
        val result = expander.expand("https://ads.test/t?size=[PLAYERSIZE]", Context(playerSize = VastMacroExpander.Size(1280, 720)))
        assertEquals("https://ads.test/t?size=1280,720", result)
    }

    @Test
    fun playerStateReportsFlagsAsAnArray() {
        assertEquals("muted%2Cfullscreen", expander.expand("[PLAYERSTATE]", Context(isMuted = true, isFullscreen = true)))
        assertEquals("", expander.expand("[PLAYERSTATE]", Context(isMuted = false, isFullscreen = false)), "no flags is still an answer, not unknown")
    }

    // MARK: - Formats

    /** §6 specifies `HH:MM:SS.mmm` for playhead values. */
    @Test
    fun playheadUsesTheSpecifiedTimeFormat() {
        assertEquals("00:00:00.000", VastMacroExpander.timecode(0.0))
        assertEquals("00:00:12.500", VastMacroExpander.timecode(12.5))
        assertEquals("01:01:01.250", VastMacroExpander.timecode(3661.25))
    }

    /** `MEDIAPLAYHEAD` is the older spelling of `ADPLAYHEAD`; a server using either must get the same answer. */
    @Test
    fun mediaPlayheadIsTreatedAsTheAdPlayhead() {
        val context = Context(adPlayhead = 7.0)
        assertEquals(expander.expand("[ADPLAYHEAD]", context), expander.expand("[MEDIAPLAYHEAD]", context))
    }

    @Test
    fun timestampIsIso8601() {
        val result = expander.expand("[TIMESTAMP]", Context(timestampMillis = 0))
        assertEquals("1970-01-01T00%3A00%3A00Z", result, "Foundation's internet date-time, in UTC")
    }

    /** Cache busting is always answerable, and differing per request is the point. */
    @Test
    fun cacheBustingIsGeneratedWhenNotSupplied() {
        val first = expander.expand("[CACHEBUSTING]", Context())
        assertNotEquals(VastMacroExpander.UNKNOWN_VALUE, first)
        assertEquals(8, first.length)
    }

    // MARK: - Robustness

    /** A stray bracket in a URI is text, not a malformed macro. */
    @Test
    fun unbalancedBracketIsLeftAlone() {
        assertEquals("https://ads.test/t?x=[oops", expander.expand("https://ads.test/t?x=[oops", Context()))
    }

    @Test
    fun uriWithNoMacrosIsUnchanged() {
        val plain = "https://ads.test/impression?id=42"
        assertEquals(plain, expander.expand(plain, Context()))
    }

    @Test
    fun expandingAStoredUriReplacesItsMacro() {
        assertEquals("https://ads.test/t?e=900", expander.expandUrl(stored("https://ads.test/t?e=[ERRORCODE]"), Context(errorCode = VastError.UNDEFINED)))
    }

    // MARK: - Percent-encoded brackets

    @Test
    fun macroSurvivesHavingBeenStoredAsAUri() {
        val uri = stored("https://ads.test/e?code=[ERRORCODE]")
        assertTrue(uri.contains("%5B"), "precondition: the stored form encodes the brackets, which is the whole problem")
        assertEquals("https://ads.test/e?code=403", expander.expandUrl(uri, Context(errorCode = VastError.NO_SUPPORTED_MEDIA_FILE)))
    }

    @Test
    fun encodedBracketsAreRecognisedInStringForm() {
        assertEquals("https://ads.test/e?code=301", expander.expand("https://ads.test/e?code=%5BERRORCODE%5D", Context(errorCode = VastError.WRAPPER_TIMEOUT)))
    }

    /** Lower-case escapes are just as valid. */
    @Test
    fun lowercaseEncodedBracketsAreRecognised() {
        assertEquals("https://ads.test/e?code=900", expander.expand("https://ads.test/e?code=%5bERRORCODE%5d", Context(errorCode = VastError.UNDEFINED)))
    }

    /** An encoded bracket that is not wrapping a macro name stays encoded. */
    @Test
    fun encodedBracketThatIsNotAMacroIsLeftAlone() {
        assertEquals("https://ads.test/t?q=%5Bnot%20a%20macro%5D", expander.expand("https://ads.test/t?q=%5Bnot%20a%20macro%5D", Context()))
    }

    // MARK: - No double encoding

    /**
     * Regression. A vendor macro left in place keeps its brackets, and re-encoding
     * the whole string would turn a correctly encoded `%3A` into `%253A`.
     */
    @Test
    fun valuesAreNotEncodedTwiceWhenAVendorMacroRemains() {
        val expanded = expander.expandUrl(stored("https://ads.test/e?t=[TIMESTAMP]&x=[VENDOR_THING]"), Context(timestampMillis = 1_700_000_000_000))

        assertTrue(expanded.contains("%3A"), "the value is encoded once")
        assertFalse(expanded.contains("%253A"), "and not a second time")
        assertTrue(expanded.contains("%5BVENDOR_THING%5D"), "the vendor macro survives")
    }

    @Test
    fun assetUriIsEncodedExactlyOnce() {
        val expanded = expander.expandUrl(stored("https://ads.test/e?a=[ASSETURI]&x=[VENDOR]"), Context(assetUri = "https://cdn.test/a.mp4"))

        assertTrue(expanded.contains("https%3A%2F%2Fcdn.test%2Fa.mp4"))
        assertFalse(expanded.contains("%25"))
    }

    // MARK: - What only the host can answer

    @Test
    fun theHostsIdentifierReachesTheServer() {
        val values = VastMacroValues(identifierForAdvertising = "AAAA-BBBB", identifierType = "aaid", limitsAdTracking = false)
        val expanded = expander.expand("https://ads.test/i?ifa=[IFA]&t=[IFATYPE]&lmt=[LIMITADTRACKING]", Context(host = values))
        assertEquals("https://ads.test/i?ifa=AAAA-BBBB&t=aaid&lmt=0", expanded)
    }

    /** `false` and "nobody asked" are different facts about a viewer. */
    @Test
    fun anUnknownTrackingPreferenceIsNotReportedAsPermission() {
        assertEquals("https://ads.test/i?lmt=-1", expander.expand("https://ads.test/i?lmt=[LIMITADTRACKING]", Context(host = VastMacroValues())))
    }

    /** A TCF string is already base64url when the CMP hands it over. */
    @Test
    fun theConsentStringIsPassedThroughUntouched() {
        val consent = "CPcqBNVPcqBNVABCDEF_gABABCAAA-AAAAAAAAAAAA"
        val expanded = expander.expand("https://ads.test/i?gdpr_consent=[GDPRCONSENT]", Context(host = VastMacroValues(gdprConsent = consent)))
        assertTrue(expanded.endsWith(consent), "the consent string was re-encoded")
    }

    @Test
    fun whereTheBreakSitsComesFromTheHost() {
        val expanded = expander.expand(
            "https://ads.test/i?pos=[BREAKPOSITION]&plcmt=[PLACEMENTTYPE]&cid=[CONTENTID]",
            Context(host = VastMacroValues(placementType = 2, breakPosition = 1, contentId = "show-42")),
        )
        assertEquals("https://ads.test/i?pos=1&plcmt=2&cid=show-42", expanded)
    }

    // MARK: - What the SDK can answer itself

    @Test
    fun theSdkAnswersWhatItKnowsAboutItself() {
        val expanded = expander.expand(
            "https://ads.test/i?ss=[SERVERSIDE]&type=[ADTYPE]&mime=[MEDIAMIME]&tx=[TRANSACTIONID]",
            Context(mediaMimeType = "video/mp4", transactionId = "abc-123"),
        )
        assertEquals("https://ads.test/i?ss=0&type=video&mime=video%2Fmp4&tx=abc-123", expanded)
    }

    /** `[CLIENTUA]` names the SDK and `[APPBUNDLE]` names the app. */
    @Test
    fun theClientUserAgentNamesTheSdkNotTheApp() {
        val expanded = expander.expand("https://ads.test/i?ua=[CLIENTUA]&app=[APPBUNDLE]", Context(appBundle = "com.kinodaran.app"))
        assertTrue(expanded.contains("VASTSDK%2F${VastVersion.CURRENT}"))
        assertTrue(expanded.contains("app=com.kinodaran.app"))
    }

    @Test
    fun everyParsableVastVersionIsAdvertised() {
        assertEquals("https://ads.test/i?v=2,3,4,4.1,4.2,4.3", expander.expand("https://ads.test/i?v=[VASTVERSIONS]", Context()))
    }

    /** OMID is claimed only when something is actually there to run a vendor's code. */
    @Test
    fun omidIsClaimedOnlyWhenSomethingWillRunIt() {
        assertEquals("-1", expander.expand("[APIFRAMEWORKS]", Context(executesOmid = false)))
        assertEquals("7", expander.expand("[APIFRAMEWORKS]", Context(executesOmid = true)))
    }

    @Test
    fun playerSizeIsAnArrayWithItsCommaIntact() {
        assertEquals("https://ads.test/i?size=1170,658", expander.expand("https://ads.test/i?size=[PLAYERSIZE]", Context(playerSize = VastMacroExpander.Size(1170, 658))))
    }

    /** A spec macro nobody supplied says so, while a vendor's macro is left exactly as it was. */
    @Test
    fun unsuppliedSpecMacrosSayUnknownAndVendorMacrosAreLeftAlone() {
        // Left as text here, because this is the string form. Only `expandUrl`
        // re-encodes the brackets.
        assertEquals(
            "https://ads.test/i?ifa=-1&dev=-1&own=[CORRELATOR]",
            expander.expand("https://ads.test/i?ifa=[IFA]&dev=[DEVICEUA]&own=[CORRELATOR]", Context()),
        )
        assertEquals("https://ads.test/i?own=%5BCORRELATOR%5D", expander.expandUrl("https://ads.test/i?own=%5BCORRELATOR%5D", Context()))
    }
}

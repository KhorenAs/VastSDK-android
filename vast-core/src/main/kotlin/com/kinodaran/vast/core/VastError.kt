package com.kinodaran.vast.core

/**
 * The VAST 4.3 §2.3.6.3 error table.
 *
 * [code] is what replaces the `[ERRORCODE]` macro in an `<Error>` URI.
 */
public enum class VastError(public val code: Int) {

    // Document
    XML_PARSING(100),
    SCHEMA_VALIDATION(101),
    VERSION_NOT_SUPPORTED(102),

    // Trafficking
    TRAFFICKING(200),
    UNEXPECTED_LINEARITY(201),
    UNEXPECTED_DURATION(202),
    UNEXPECTED_SIZE(203),
    AD_CATEGORY_MISSING(204),
    AD_CATEGORY_BLOCKED(205),
    AD_BREAK_SHORTENED(206),

    // Wrapper
    WRAPPER_GENERAL(300),
    WRAPPER_TIMEOUT(301),
    WRAPPER_LIMIT_REACHED(302),
    NO_VAST_RESPONSE_AFTER_WRAPPERS(303),
    IN_LINE_TIMEOUT(304),

    // Linear
    LINEAR_GENERAL(400),
    MEDIA_FILE_NOT_FOUND(401),
    MEDIA_FILE_TIMEOUT(402),
    NO_SUPPORTED_MEDIA_FILE(403),
    MEDIA_FILE_DISPLAY_PROBLEM(405),
    MEZZANINE_MISSING(406),
    MEZZANINE_DOWNLOADING(407),
    CONDITIONAL_AD_REJECTED(408),
    INTERACTIVE_UNIT_NOT_EXECUTED(409),
    VERIFICATION_NOT_EXECUTED(410),
    MEZZANINE_BELOW_SPEC(411),

    // NonLinear / Companion — parsed and reported, never played by this SDK.
    NON_LINEAR_GENERAL(500),
    NON_LINEAR_DIMENSIONS(501),
    NON_LINEAR_FETCH_FAILED(502),
    NON_LINEAR_UNSUPPORTED_TYPE(503),
    COMPANION_GENERAL(600),
    COMPANION_DIMENSIONS(601),
    COMPANION_REQUIRED_NOT_SHOWN(602),
    COMPANION_FETCH_FAILED(603),
    COMPANION_UNSUPPORTED_TYPE(604),

    UNDEFINED(900),
    VPAID_GENERAL(901),
    INTERACTIVE_CREATIVE_FILE(902),
    ;

    public companion object {
        public fun fromCode(code: Int): VastError? = entries.firstOrNull { it.code == code }
    }
}

/** A [VastError] raised as an exception, for the calls that throw one. */
public class VastException(public val error: VastError) : Exception("VAST error ${error.code} ${error.name}")

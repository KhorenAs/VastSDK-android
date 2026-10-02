package com.kinodaran.vast.core

/**
 * How a URI read from a response is stored.
 *
 * The Apple SDK keeps URIs as Foundation `URL`s, and `URL(string:)` percent-
 * encodes what is not legal in a URI rather than refusing it — most importantly
 * the brackets of a macro, so `[ERRORCODE]` is stored as `%5BERRORCODE%5D`.
 * Keeping the same form here keeps every URI byte-identical across the two SDKs,
 * which is what lets one conformance suite check both. `java.net.URI` would
 * instead reject a bracketed query outright and drop every beacon with a macro.
 */
internal object VastUrls {

    /** Characters RFC 3986 never allows unencoded outside an IPv6 host. */
    private const val ILLEGAL = " \"<>\\^`{|}[]"

    /** `null` for an empty value: there is nothing to request. */
    fun from(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return normalise(trimmed)
    }

    fun normalise(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '%' && isEscape(value, index) -> out.append(char)
                char == '%' -> out.append("%25")
                char <= ' ' || char == '\u007F' || char in ILLEGAL -> appendEncoded(out, char.toString())
                char > '\u007F' -> {
                    // A surrogate pair is one code point and one UTF-8 sequence.
                    val end = if (char.isHighSurrogate() && index + 1 < value.length) index + 2 else index + 1
                    appendEncoded(out, value.substring(index, end))
                    index = end
                    continue
                }
                else -> out.append(char)
            }
            index++
        }
        return out.toString()
    }

    private fun isEscape(value: String, index: Int): Boolean =
        index + 2 <= value.lastIndex && value[index + 1].isHexDigit() && value[index + 2].isHexDigit()

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun appendEncoded(out: StringBuilder, text: String) {
        for (byte in text.toByteArray(Charsets.UTF_8)) out.appendPercentEncoded(byte)
    }

    private const val HEX = "0123456789ABCDEF"

    /** `%XX`, upper-case as RFC 3986 asks, for one byte of UTF-8. */
    @Suppress("MagicNumber") // A byte's two hex digits.
    fun StringBuilder.appendPercentEncoded(byte: Byte): StringBuilder {
        val unsigned = byte.toInt() and 0xFF
        return append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0F])
    }
}

package com.kinodaran.vast.core

import kotlin.test.fail

/**
 * Fixtures come from the IAB sample tags shipped with dailymotion/vast-client-js
 * (MIT), plus two live responses, and are the same files VastSDK-apple tests
 * against. They are used because they carry the inconsistencies real ad servers
 * produce, which synthetic XML never does.
 */
internal fun fixture(name: String): String {
    val stream = VastParserTest::class.java.getResourceAsStream("/fixtures/$name.xml")
        ?: fail("missing fixture $name.xml")
    return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

internal fun inLine(entry: VastDocument.Entry): VastAd =
    (entry.body as? VastDocument.Body.InLine)?.ad ?: fail("expected an InLine entry, got ${entry.body}")

internal fun wrapper(entry: VastDocument.Entry): VastDocument.Wrapper =
    (entry.body as? VastDocument.Body.Wrapper)?.wrapper ?: fail("expected a Wrapper entry, got ${entry.body}")

internal fun firstAd(xml: String): VastAd = inLine(VastParser().parse(xml).entries.first())

/** Foundation's `URL.lastPathComponent`, for assertions written the same way as the Apple tests. */
internal val String.lastPathComponent: String
    get() = substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')

/** Foundation's `URL.query`. */
internal val String.query: String?
    get() = substringBefore('#').substringAfter('?', missingDelimiterValue = "").ifEmpty { null }

/** Short, stable name for assertions. */
internal val VastBeacon.label: String
    get() = when (val kind = kind) {
        VastBeacon.Kind.Impression -> "impression"
        is VastBeacon.Kind.Tracking -> kind.event.vastName
        is VastBeacon.Kind.Progress -> url.lastPathComponent
        VastBeacon.Kind.ClickTracking -> "clickTracking"
        is VastBeacon.Kind.Error -> "error ${kind.error.code}"
        is VastBeacon.Kind.VerificationNotExecuted -> "verificationNotExecuted ${kind.reason.code}"
        VastBeacon.Kind.ViewUndetermined -> "viewUndetermined"
        VastBeacon.Kind.CustomClick -> "customClick"
    }

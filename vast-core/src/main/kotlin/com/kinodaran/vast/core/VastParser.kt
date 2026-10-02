package com.kinodaran.vast.core

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/**
 * Turns VAST XML into a [VastDocument].
 *
 * Deliberately version-tolerant. Real ad servers still emit VAST 2.0 and 3.0,
 * 4.x adds elements this SDK does not play, and almost every server has its own
 * idea of how to write a URI — bare text, CDATA, or CDATA padded with newlines.
 * Unknown elements are skipped rather than rejected; only XML that will not
 * parse at all raises [VastError.XML_PARSING] (100).
 *
 * Built on SAX because it is on every JVM and every Android release, so the
 * same code runs in the JVM tests and on a device, and because it is the same
 * event-driven model as Foundation's `XMLParser` — which keeps this a line-for-
 * line port of the Apple parser rather than a reinterpretation of it.
 */
public class VastParser {

    /** @throws VastException with [VastError.XML_PARSING] (100) when the XML is malformed. */
    public fun parse(xml: String): VastDocument {
        val builder = Builder()
        try {
            val reader = factory().newSAXParser().xmlReader
            reader.contentHandler = builder
            reader.errorHandler = builder
            // CDATA boundaries matter: a raw-captured extension keeps its CDATA.
            try {
                reader.setProperty("http://xml.org/sax/properties/lexical-handler", builder)
            } catch (_: SAXException) {
            }
            reader.parse(InputSource(StringReader(xml)))
        } catch (cause: SAXException) {
            throw VastException(VastError.XML_PARSING, cause)
        } catch (cause: IOException) {
            throw VastException(VastError.XML_PARSING, cause)
        } catch (cause: ParserConfigurationException) {
            throw VastException(VastError.XML_PARSING, cause)
        }
        // A VAST 1.0 document is well-formed XML under a different root element.
        // Reporting it as a parse error would tell the ad server the wrong thing,
        // so it gets the code the spec reserves for it.
        if (builder.sawUnsupportedRoot) throw VastException(VastError.VERSION_NOT_SUPPORTED)
        return builder.document ?: throw VastException(VastError.XML_PARSING)
    }

    private fun factory(): SAXParserFactory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        // An ad response has no business reaching outside itself. Not every
        // parser recognises every feature, and a refusal here is not a failure.
        for (feature in listOf(
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
        )) {
            try {
                setFeature(feature, false)
            } catch (_: Exception) {
            }
        }
    }
}

// MARK: - Builder

private class Builder : DefaultHandler2() {

    var document: VastDocument? = null

    /** Set when the root element is not `<VAST>` — i.e. a pre-3.0 document. */
    var sawUnsupportedRoot = false

    private var version = ""
    private val entries = mutableListOf<VastDocument.Entry>()
    private var rootError: String? = null

    private val path = ArrayDeque<String>()
    private val text = StringBuilder()

    // CDATA arrives through `characters` between these two calls.
    private var insideCdata = false
    private val cdata = StringBuilder()

    // Current <Ad>
    private var adId = ""
    private var adSequence: Int? = null
    private var isWrapper = false

    private val impressions = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private var tagUri: String? = null
    private var adSystem: String? = null
    private var adTitle: String? = null
    private var followAdditionalWrappers = true
    private var allowMultipleAds = false
    private var fallbackOnNoAd: Boolean? = null

    // VAST 4 metadata. Required elements, in the parts of the spec that decide
    // whose count is right when two reports disagree.
    private var adServingId: String? = null
    private val universalAdIds = mutableListOf<VastAd.UniversalAdId>()
    private var pendingUniversalAdIdRegistry: String? = null
    private var advertiser: String? = null
    private var pricing: VastAd.Pricing? = null
    private var pendingPricing: Map<String, String> = emptyMap()
    private val categories = mutableListOf<VastAd.Category>()
    private var pendingCategoryAuthority: String? = null
    private var expires: Double? = null

    // Current <ViewableImpression>
    private var sawViewableImpression = false
    private var viewableImpressionId: String? = null
    private val viewable = mutableListOf<String>()
    private val notViewable = mutableListOf<String>()
    private val viewUndetermined = mutableListOf<String>()

    // Current <Icon>
    private val icons = mutableListOf<VastAd.Icon>()
    private var insideIcon = false
    private var pendingIcon: Map<String, String> = emptyMap()
    private var iconStaticResource: String? = null
    private var iconStaticType: String? = null
    private var iconClickThrough: String? = null
    private val iconClickTracking = mutableListOf<String>()
    private val iconViewTracking = mutableListOf<String>()

    // Current <Linear>
    private var duration = 0.0
    private var skipOffset: VastAd.SkipOffset? = null
    private val mediaFiles = mutableListOf<VastAd.MediaFile>()
    private var clickThrough: String? = null
    private val clickTracking = mutableListOf<String>()
    private val customClicks = mutableListOf<String>()
    private val tracking = LinkedHashMap<VastTrackingEvent, MutableList<String>>()
    private val progress = mutableListOf<VastAd.ProgressEvent>()

    private var pendingTrackingEvent: String? = null
    private var pendingTrackingOffset: String? = null
    private var pendingMediaFile: Map<String, String> = emptyMap()

    /**
     * Set by `<NonLinearAds>` or `<CompanionAds>`: creatives this SDK ignores, but
     * whose presence distinguishes "not playable here" from "no fill".
     */
    private var sawUnplayableCreative = false

    // Current <AdVerifications>
    //
    // VAST 4 puts these under <InLine>/<Wrapper>; VAST 3 had no element for them
    // and vendors shipped the same content inside <Extension type="AdVerifications">.
    // Both shapes land in the same list — a host should not have to know which
    // version the server speaks.
    private val verifications = mutableListOf<VastVerification>()

    /** Inside `<AdVerifications>` — or the VAST 3 extension standing in for it. */
    private var insideVerifications = false

    /**
     * True while the extension currently open is that VAST 3 stand-in, so its
     * `</Extension>` closes the verification scope rather than a raw capture.
     */
    private var extensionIsVerifications = false
    private var verificationVendor: String? = null
    private val verificationResources = mutableListOf<VastVerification.Resource>()
    private var verificationParameters: String? = null
    private val verificationNotExecuted = mutableListOf<String>()
    private var pendingResource: Map<String, String> = emptyMap()

    // Raw <Extension> capture
    private val extensions = mutableListOf<VastAd.Extension>()
    private var extensionDepth = 0
    private var extensionType: String? = null
    private val extensionXml = StringBuilder()

    // MARK: Content

    // One branch per VAST element: split up, the element table would be scattered.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
        val element = qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()
        val attributes = attributeMap(attrs)

        // Everything under <Extension> is copied verbatim: the SDK never
        // interprets vendor XML, it just hands it to the host (§3.18).
        if (extensionDepth > 0) {
            extensionXml.append('<').append(element).append(attributeString(attributes)).append('>')
            extensionDepth += 1
            path.addLast(element)
            return
        }

        if (path.isEmpty() && element != "VAST") {
            sawUnsupportedRoot = true
        }

        path.addLast(element)
        text.setLength(0)

        when (element) {
            "VAST" -> version = attributes["version"].orEmpty()

            "Ad" -> {
                resetAd()
                adId = attributes["id"].orEmpty()
                adSequence = attributes["sequence"]?.let(::integer)
            }

            "Wrapper" -> {
                isWrapper = true
                followAdditionalWrappers = bool(attributes["followAdditionalWrappers"], true)
                allowMultipleAds = bool(attributes["allowMultipleAds"], false)
                fallbackOnNoAd = attributes["fallbackOnNoAd"]?.let { bool(it, false) }
            }

            "Linear" -> skipOffset = attributes["skipoffset"]?.let(::offset)

            "Tracking" -> {
                pendingTrackingEvent = attributes["event"]
                pendingTrackingOffset = attributes["offset"]
            }

            "MediaFile" -> pendingMediaFile = attributes

            "UniversalAdId" -> pendingUniversalAdIdRegistry = attributes["idRegistry"]

            "ViewableImpression" -> {
                sawViewableImpression = true
                viewableImpressionId = attributes["id"]
            }

            "Icon" -> {
                insideIcon = true
                pendingIcon = attributes
                iconStaticResource = null
                iconStaticType = null
                iconClickThrough = null
                iconClickTracking.clear()
                iconViewTracking.clear()
            }

            // Also a NonLinear and Companion element, neither of which this SDK
            // plays; only an icon's copy is kept.
            "StaticResource" -> if (insideIcon) iconStaticType = attributes["creativeType"]

            "Pricing" -> pendingPricing = attributes

            "Category" -> pendingCategoryAuthority = attributes["authority"]

            "AdVerifications" -> insideVerifications = true

            "Verification" -> {
                verificationVendor = attributes["vendor"]
                verificationResources.clear()
                verificationParameters = null
                verificationNotExecuted.clear()
                // A Verification outside <AdVerifications> is the VAST 3 shape with
                // the wrapper element omitted; treat the scope as open either way.
                insideVerifications = true
            }

            "JavaScriptResource", "ExecutableResource" -> pendingResource = attributes

            "Extension" -> {
                // The VAST 3 stand-in is parsed, not captured raw: a host asking for
                // `adVerifications` should get them whatever the server speaks, and
                // returning the same content twice would invite double-reporting.
                if (attributes["type"]?.lowercase() == "adverifications") {
                    extensionIsVerifications = true
                    insideVerifications = true
                    return
                }
                extensionDepth = 1
                extensionType = attributes["type"]
                extensionXml.setLength(0)
            }

            // Not parsed — but seeing one changes what an ad with no Linear means:
            // the server filled the slot, just not with something playable here.
            "NonLinearAds", "CompanionAds" -> sawUnplayableCreative = true
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (insideCdata) {
            cdata.appendRange(ch, start, start + length)
            return
        }
        if (extensionDepth > 0) {
            extensionXml.appendRange(ch, start, start + length)
        } else {
            text.appendRange(ch, start, start + length)
        }
    }

    override fun startCDATA() {
        insideCdata = true
        cdata.setLength(0)
    }

    /**
     * Ad servers wrap URIs inconsistently, so CDATA feeds the same buffer as plain
     * text and is trimmed later — except inside a raw-captured extension, which
     * keeps its CDATA so the host sees what the server sent.
     */
    override fun endCDATA() {
        insideCdata = false
        if (extensionDepth > 0) {
            extensionXml.append("<![CDATA[").append(cdata).append("]]>")
        } else {
            text.append(cdata)
        }
        cdata.setLength(0)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        val element = qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()
        try {
            closeElement(element)
        } finally {
            if (path.isNotEmpty()) path.removeLast()
        }
    }

    @Suppress("LongMethod") // One branch per VAST element, as in startElement.
    private fun closeElement(element: String) {
        if (extensionDepth > 0) {
            extensionDepth -= 1
            if (extensionDepth == 0) {
                extensions += VastAd.Extension(extensionType, extensionXml.toString())
                extensionType = null
                extensionXml.setLength(0)
            } else {
                extensionXml.append("</").append(element).append('>')
            }
            return
        }

        val value = text.toString().trim()
        text.setLength(0)

        when (element) {
            "AdSystem" -> adSystem = value

            "AdServingId" -> adServingId = value.ifEmpty { null }

            "UniversalAdId" -> {
                // A registry of "unknown" is a real answer some servers give; only an
                // empty value means there is nothing to record.
                if (value.isNotEmpty()) universalAdIds += VastAd.UniversalAdId(pendingUniversalAdIdRegistry, value)
                pendingUniversalAdIdRegistry = null
            }

            "Advertiser" -> advertiser = value.ifEmpty { null }

            "Pricing" -> {
                val attributes = pendingPricing
                pendingPricing = emptyMap()
                decimal(value)?.let { pricing = VastAd.Pricing(attributes["model"], attributes["currency"], it) }
            }

            "Category" -> {
                val authority = pendingCategoryAuthority
                pendingCategoryAuthority = null
                if (value.isNotEmpty()) categories += VastAd.Category(authority, value)
            }

            // Plain seconds, unlike <Duration>'s timecode.
            "Expires" -> expires = decimal(value)

            "Viewable" -> append(value, viewable)
            "NotViewable" -> append(value, notViewable)
            "ViewUndetermined" -> append(value, viewUndetermined)

            "StaticResource" -> if (insideIcon) iconStaticResource = VastUrls.from(value)
            "IconClickThrough" -> iconClickThrough = VastUrls.from(value)
            "IconClickTracking" -> append(value, iconClickTracking)
            "IconViewTracking" -> append(value, iconViewTracking)
            "Icon" -> finishIcon()

            "CustomClick" -> append(value, customClicks)

            "AdTitle" -> adTitle = value

            "Impression" -> append(value, impressions)

            "Error" ->
                // A root-level <Error> with no <Ad> is the "no ad" response (§2.3.6.4).
                if (path.size <= 2) rootError = VastUrls.from(value) else append(value, errors)

            // May be relative (real responses do this). Resolution against the
            // document's own base URL belongs to the loader, which is the only layer
            // that knows where this XML was fetched from.
            "VASTAdTagURI" -> tagUri = VastUrls.from(value)

            "Duration" -> duration = seconds(value) ?: 0.0

            "ClickThrough" -> clickThrough = VastUrls.from(value)

            "ClickTracking" -> append(value, clickTracking)

            "MediaFile" -> appendMediaFile(value)

            // `<TrackingEvents>` appears under both `<Linear>` and `<Verification>`.
            // Without the scope, a verification's tracker would be filed as a
            // creative event — and since `verificationNotExecuted` is not one, it
            // would vanish instead.
            "Tracking" -> if (insideVerifications) appendVerificationTracking(value) else appendTracking(value)

            "JavaScriptResource" -> appendVerificationResource(VastVerification.Resource.Kind.JAVA_SCRIPT, value)
            "ExecutableResource" -> appendVerificationResource(VastVerification.Resource.Kind.EXECUTABLE, value)

            "VerificationParameters" -> verificationParameters = value.ifEmpty { null }

            "Verification" -> finishVerification()

            "AdVerifications" -> insideVerifications = false

            // Only reached for the VAST 3 stand-in; a raw-captured extension is
            // closed by the depth branch above and never falls through to here.
            "Extension" -> if (extensionIsVerifications) {
                extensionIsVerifications = false
                insideVerifications = false
            }

            "Ad" -> finishAd()

            "VAST" -> document = VastDocument(version, entries.toList(), rootError)
        }
    }

    /** Malformed XML ends the parse, as it does for Foundation's parser. */
    override fun error(e: org.xml.sax.SAXParseException) {
        throw e
    }

    // MARK: - Assembly

    private fun appendTracking(value: String) {
        val name = pendingTrackingEvent
        val url = VastUrls.from(value)
        if (name == null || url == null) return
        val offsetValue = pendingTrackingOffset
        pendingTrackingEvent = null
        pendingTrackingOffset = null

        // `progress` carries an offset and drives skippable-ad billing, so it is
        // modelled separately from the fixed quartiles (§3.14.1).
        if (name == "progress") {
            val offset = offsetValue?.let(::offset) ?: return
            progress += VastAd.ProgressEvent(offset, url)
            return
        }
        val event = VastTrackingEvent.fromVastName(name) ?: return
        tracking.getOrPut(event) { mutableListOf() } += url
    }

    private fun appendMediaFile(value: String) {
        val attributes = pendingMediaFile
        pendingMediaFile = emptyMap()
        val url = VastUrls.from(value) ?: return
        mediaFiles += VastAd.MediaFile(
            id = attributes["id"],
            url = url,
            mimeType = attributes["type"].orEmpty(),
            delivery = VastAd.Delivery.fromVastName(attributes["delivery"].orEmpty()) ?: VastAd.Delivery.PROGRESSIVE,
            width = attributes["width"]?.let(::integer),
            height = attributes["height"]?.let(::integer),
            bitrate = attributes["bitrate"]?.let(::integer),
            minBitrate = attributes["minBitrate"]?.let(::integer),
            maxBitrate = attributes["maxBitrate"]?.let(::integer),
            scalable = bool(attributes["scalable"], true),
            maintainAspectRatio = bool(attributes["maintainAspectRatio"], true),
            codec = attributes["codec"],
        )
    }

    private fun appendVerificationResource(kind: VastVerification.Resource.Kind, value: String) {
        val attributes = pendingResource
        pendingResource = emptyMap()
        if (!insideVerifications) return
        val url = VastUrls.from(value) ?: return
        verificationResources += VastVerification.Resource(
            kind = kind,
            url = url,
            apiFramework = attributes["apiFramework"],
            browserOptional = bool(attributes["browserOptional"], false),
            type = attributes["type"],
        )
    }

    /** The only tracker a `<Verification>` may carry (§3.16). */
    private fun appendVerificationTracking(value: String) {
        val event = pendingTrackingEvent
        pendingTrackingEvent = null
        pendingTrackingOffset = null
        if (event != "verificationNotExecuted") return
        verificationNotExecuted += VastUrls.from(value) ?: return
    }

    /**
     * A `<Verification>` with no resource at all asks for nothing and is dropped;
     * one with only an unusable resource is kept, because the vendor still expects
     * to hear why it did not run.
     */
    private fun finishVerification() {
        if (verificationResources.isNotEmpty()) {
            verifications += VastVerification(
                vendor = verificationVendor,
                resources = verificationResources.toList(),
                parameters = verificationParameters,
                notExecutedTrackers = verificationNotExecuted.toList(),
            )
        }
        verificationVendor = null
        verificationResources.clear()
        verificationParameters = null
        verificationNotExecuted.clear()
    }

    /** A `<ViewableImpression>` with no URI at all asked for nothing. */
    private fun finishedViewableImpression(): VastAd.ViewableImpression? {
        if (!sawViewableImpression) return null
        val impression = VastAd.ViewableImpression(
            id = viewableImpressionId,
            viewable = viewable.toList(),
            notViewable = notViewable.toList(),
            viewUndetermined = viewUndetermined.toList(),
        )
        return if (impression.isEmpty) null else impression
    }

    /**
     * An icon with nothing to draw is dropped: a host cannot render an absent
     * image, and keeping it would look like an AdChoices mark that failed.
     */
    private fun finishIcon() {
        val attributes = pendingIcon
        val resource = iconStaticResource
        if (resource != null) {
            icons += VastAd.Icon(
                program = attributes["program"],
                // Real tags send width="" — an empty attribute is not a zero.
                width = attributes["width"]?.let(::integer),
                height = attributes["height"]?.let(::integer),
                xPosition = attributes["xPosition"],
                yPosition = attributes["yPosition"],
                offset = attributes["offset"]?.let(::seconds),
                duration = attributes["duration"]?.let(::seconds),
                staticResource = resource,
                staticResourceType = iconStaticType,
                clickThrough = iconClickThrough,
                clickTracking = iconClickTracking.toList(),
                viewTracking = iconViewTracking.toList(),
            )
        }
        insideIcon = false
        pendingIcon = emptyMap()
        iconStaticResource = null
        iconStaticType = null
        iconClickThrough = null
        iconClickTracking.clear()
        iconViewTracking.clear()
    }

    @Suppress("LongMethod") // Every field of an ad, assembled in one place.
    private fun finishAd() {
        val entry: VastDocument.Entry

        if (isWrapper) {
            val tagUri = tagUri ?: return resetAd()
            entry = VastDocument.Entry(
                adId,
                adSequence,
                VastDocument.Body.Wrapper(
                    VastDocument.Wrapper(
                        tagUri = tagUri,
                        impressions = impressions.toList(),
                        errors = errors.toList(),
                        trackingEvents = frozenTracking(),
                        clickTracking = clickTracking.toList(),
                        clickThrough = clickThrough,
                        extensions = extensions.toList(),
                        verifications = verifications.toList(),
                        viewableImpression = finishedViewableImpression(),
                        followAdditionalWrappers = followAdditionalWrappers,
                        allowMultipleAds = allowMultipleAds,
                        fallbackOnNoAd = fallbackOnNoAd,
                    ),
                ),
            )
        } else {
            // An InLine with no playable Linear is not an ad. If it offered a
            // NonLinear or Companion creative the slot was filled — the response is
            // simply the wrong linearity, and §2.3.6 reserves 201 for that. Dropping
            // it silently would report "no fill" and throw away the `<Error>` URI
            // the server expected to hear on.
            if (mediaFiles.isEmpty() && duration <= 0) {
                if (sawUnplayableCreative) {
                    entries += VastDocument.Entry(adId, adSequence, VastDocument.Body.UnplayableCreative(errors.toList()))
                }
                return resetAd()
            }
            entry = VastDocument.Entry(
                adId,
                adSequence,
                VastDocument.Body.InLine(
                    VastAd(
                        id = adId,
                        sequence = adSequence,
                        adSystem = adSystem,
                        title = adTitle,
                        linear = VastAd.Linear(
                            duration = duration,
                            skipOffset = skipOffset,
                            mediaFiles = mediaFiles.toList(),
                            clickThrough = clickThrough,
                            clickTracking = clickTracking.toList(),
                            customClicks = customClicks.toList(),
                            trackingEvents = frozenTracking(),
                            progressEvents = progress.toList(),
                        ),
                        impressions = impressions.toList(),
                        errors = errors.toList(),
                        extensions = extensions.toList(),
                        adVerifications = verifications.toList(),
                        adServingId = adServingId,
                        universalAdIds = universalAdIds.toList(),
                        viewableImpression = finishedViewableImpression(),
                        icons = icons.toList(),
                        advertiser = advertiser,
                        pricing = pricing,
                        categories = categories.toList(),
                        expires = expires,
                    ),
                ),
            )
        }

        entries += entry
        resetAd()
    }

    private fun frozenTracking(): Map<VastTrackingEvent, List<String>> =
        tracking.mapValuesTo(LinkedHashMap()) { it.value.toList() }

    private fun resetAd() {
        adId = ""; adSequence = null; isWrapper = false
        impressions.clear(); errors.clear(); tagUri = null
        adSystem = null; adTitle = null
        followAdditionalWrappers = true; allowMultipleAds = false; fallbackOnNoAd = null
        duration = 0.0; skipOffset = null; mediaFiles.clear()
        clickThrough = null; clickTracking.clear(); customClicks.clear()
        tracking.clear(); progress.clear()
        adServingId = null; universalAdIds.clear(); pendingUniversalAdIdRegistry = null
        advertiser = null; pricing = null; pendingPricing = emptyMap()
        categories.clear(); pendingCategoryAuthority = null; expires = null
        sawViewableImpression = false; viewableImpressionId = null
        viewable.clear(); notViewable.clear(); viewUndetermined.clear()
        icons.clear(); insideIcon = false; pendingIcon = emptyMap()
        iconStaticResource = null; iconStaticType = null; iconClickThrough = null
        iconClickTracking.clear(); iconViewTracking.clear()
        extensions.clear(); sawUnplayableCreative = false
        verifications.clear(); insideVerifications = false; extensionIsVerifications = false
        verificationVendor = null; verificationResources.clear()
        verificationParameters = null; verificationNotExecuted.clear(); pendingResource = emptyMap()
    }

    private fun append(value: String, list: MutableList<String>) {
        list += VastUrls.from(value) ?: return
    }
}

// MARK: - Scalars

/** VAST booleans appear as `true`/`false` and as `1`/`0`, interchangeably. */
private fun bool(value: String?, default: Boolean): Boolean = when (value?.trim(' ', '\t')?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> default
}

/**
 * Swift's `Int(_:)`: an optional sign and digits, nothing else — no whitespace,
 * which is what makes `width=""` and `width=" 640"` absent rather than zero.
 */
private fun integer(value: String): Int? =
    if (Regex("[+-]?[0-9]+").matches(value)) value.toIntOrNull() else null

/**
 * Swift's `Double(_:)` for the decimal forms a response uses. Stricter than
 * `toDoubleOrNull`, which also takes whitespace, hex and a trailing `d` or `f`.
 */
private fun decimal(value: String): Double? =
    if (Regex("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?").matches(value)) value.toDouble() else null

/** Hours, minutes and seconds. */
private const val TIMESTAMP_PARTS = 3

/** `HH:MM:SS` or `HH:MM:SS.mmm`. */
private fun seconds(value: String): Double? {
    // Swift's `split` drops empty pieces, so `00::10` is two parts, not three.
    val parts = value.split(':').filter { it.isNotEmpty() }
    if (parts.size != TIMESTAMP_PARTS) return null
    val hours = decimal(parts[0]) ?: return null
    val minutes = decimal(parts[1]) ?: return null
    val seconds = decimal(parts[2]) ?: return null
    return hours * 3600 + minutes * 60 + seconds
}

/** A `skipoffset` or `progress` offset: a timestamp or a percentage. */
private fun offset(value: String): VastAd.SkipOffset? {
    val trimmed = value.trim(' ', '\t')
    if (trimmed.endsWith("%")) {
        decimal(trimmed.dropLast(1))?.let { return VastAd.SkipOffset.Percent(it) }
    }
    return seconds(trimmed)?.let { VastAd.SkipOffset.Time(it) }
}

private fun attributeMap(attributes: Attributes): Map<String, String> {
    val map = LinkedHashMap<String, String>(attributes.length)
    for (index in 0 until attributes.length) {
        val name = attributes.getQName(index)?.takeIf { it.isNotEmpty() } ?: attributes.getLocalName(index)
        map[name] = attributes.getValue(index)
    }
    return map
}

/**
 * Attributes of a raw-captured element, in a stable order. Sorted by name, as
 * the Apple parser writes them, so both SDKs hand a host the same string.
 */
private fun attributeString(attributes: Map<String, String>): String =
    attributes.entries.sortedBy { it.key }.joinToString("") { " ${it.key}=\"${it.value}\"" }

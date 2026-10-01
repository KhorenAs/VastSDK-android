package com.kinodaran.vast.kit

import android.content.Context
import android.net.Uri
import androidx.core.app.OnPictureInPictureModeChangedProvider
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.Player
import com.kinodaran.vast.core.VastAd
import com.kinodaran.vast.core.VastBeacon
import com.kinodaran.vast.core.VastError
import com.kinodaran.vast.core.VastException
import com.kinodaran.vast.core.VastMacroExpander
import com.kinodaran.vast.core.VastMediaFileSelector
import com.kinodaran.vast.core.VastPodScheduler
import com.kinodaran.vast.core.VastResourceLoader
import com.kinodaran.vast.core.VastTagResolver
import com.kinodaran.vast.core.VastTick
import com.kinodaran.vast.core.VastTrackingEngine
import com.kinodaran.vast.core.VastTrackingEvent
import com.kinodaran.vast.core.VastVerification
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.math.max

/**
 * Plays one VAST break inside a Media3 player and reports tracking.
 *
 * The break is inserted by [adsLoader], a Media3 `AdsLoader`: the host hands it
 * to its `DefaultMediaSourceFactory` and gives the content `MediaItem` an
 * `AdsConfiguration` whose tag is the ad server's URL. Media3 then plays the
 * creatives in the host's own player and timeline — the content buffer, its
 * position and `isPlayingAd` are all Media3's — and this session decides what
 * is reported and when.
 *
 * The session owns no UI. It publishes state as flows for the ad surface, and
 * forwards the same transitions to [listener]. Main thread only, as Media3's
 * own player is.
 *
 * Keep one session, and the player it is attached to, for the life of the
 * screen — across configuration changes too, in a `ViewModel` or an activity
 * that handles its own. Rebuilt on rotation, they restart the break, and the ad
 * server is sent the same impression twice.
 */
public class VastAdSession internal constructor(
    public val configuration: VastConfiguration,
    private val environment: Environment,
    dispatcher: CoroutineDispatcher,
) {

    public constructor(context: Context, configuration: VastConfiguration = VastConfiguration()) :
        this(configuration, Environment.of(context, configuration), Dispatchers.Main.immediate)

    /** What the session reads from the device, kept apart so a JVM test can supply it. */
    internal class Environment(
        val transport: VastBeaconTransport,
        val loader: VastResourceLoader,
        val appBundle: String?,
        /** Pixels of the screen, for media selection before a surface has been measured. */
        val screenSize: () -> VastMacroExpander.Size?,
        /** Monotonic seconds, for the stall watchdog. */
        val uptimeSeconds: () -> Double,
        /** A leanback device, where a transparent click layer can never take focus. */
        val isTelevision: Boolean = false,
        /** Whether the app is on screen. `null` in a test that does not care. */
        val appLifecycle: Lifecycle? = null,
        /** The media controls' name for an ad with no `<AdTitle>`, in the device's language. */
        val nowPlayingTitle: String = "Advertisement",
    ) {
        fun isAppVisible(): Boolean = appLifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true

        companion object {
            fun of(context: Context, configuration: VastConfiguration): Environment {
                val application = context.applicationContext
                val transport = configuration.transport ?: VastHttpTransport(VastBeaconQueue.shared(application))
                return Environment(
                    transport = transport,
                    loader = configuration.loader ?: VastHttpLoader(),
                    appBundle = application.packageName,
                    screenSize = {
                        val metrics = application.resources.displayMetrics
                        VastMacroExpander.Size(metrics.widthPixels, metrics.heightPixels)
                    },
                    uptimeSeconds = { android.os.SystemClock.elapsedRealtime() / 1000.0 },
                    isTelevision = application.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK),
                    appLifecycle = ProcessLifecycleOwner.get().lifecycle,
                    nowPlayingTitle = application.getString(R.string.vast_nowplaying_title),
                )
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // MARK: - Published state

    private val mutableState = MutableStateFlow<VastAdState>(VastAdState.Idle)
    public val state: StateFlow<VastAdState> = mutableState.asStateFlow()

    private val mutableCurrentAd = MutableStateFlow<VastAd?>(null)
    public val currentAd: StateFlow<VastAd?> = mutableCurrentAd.asStateFlow()

    private val mutableAdPosition = MutableStateFlow(VastAdPosition.SINGLE)
    public val adPosition: StateFlow<VastAdPosition> = mutableAdPosition.asStateFlow()

    private val mutableRemainingTime = MutableStateFlow(0.0)
    public val remainingTime: StateFlow<Double> = mutableRemainingTime.asStateFlow()

    private val mutableCanSkip = MutableStateFlow(false)

    /** `true` once `skipoffset` has elapsed on a skippable ad. */
    public val canSkip: StateFlow<Boolean> = mutableCanSkip.asStateFlow()

    private val mutableTimeUntilSkip = MutableStateFlow<Double?>(null)

    /** Seconds until the skip control unlocks; `null` when the ad is not skippable or already is. */
    public val timeUntilSkip: StateFlow<Double?> = mutableTimeUntilSkip.asStateFlow()

    /**
     * Whether the host is willing to draw the whole ad UI itself when a response
     * asks it to.
     *
     * `false`, the default, means the SDK draws its own UI whatever the response
     * says. That is deliberate: `<Extensions>` is vendor territory, and a vendor
     * key must not be able to take the §2.3 skip promise away from a host that
     * never agreed to honour it. Set it to `true` and a response carrying the
     * `uiSettings` UI-hidden key gets no SDK UI at all — skip included — for that
     * creative; the host must then provide its controls.
     */
    public val isHiddenUi: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Whether the ad on screen right now must be drawn by the host: the host allowed it *and* this response asked for it. */
    public val suppressesAdUi: Boolean get() = suppressesAdUi(mutableCurrentAd.value)

    /** Hidden UI hands the current creative's controls to the host. Otherwise the configured policy stands. */
    public val effectiveSkipPresentation: VastSkipPresentation get() = skipPresentation(mutableCurrentAd.value)

    /**
     * Whether the host's own transport controls should be offered right now.
     *
     * `false` for the length of a break, because an ad the viewer can scrub is an
     * ad the viewer can get past: VAST defines no attribute that would permit
     * seeking inside a linear creative.
     */
    public val permitsPlaybackControls: Boolean get() = mutableState.value.isOutsideBreak

    public var listener: VastAdSessionListener? = null

    /**
     * Third-party measurement, if anything is going to execute the ad's
     * `<AdVerifications>`. Set it before the break starts.
     *
     * Leaving this `null` is not a gap: the SDK then reports
     * `verificationNotExecuted` to every vendor that asked, which is the honest
     * answer and the whole reason the field is optional.
     */
    public var measurement: VastMeasurement? = null

    /** The Media3 `AdsLoader` that inserts this session's break into the host's player. */
    public val adsLoader: VastAdsLoader = VastAdsLoader(this)

    private val resolver = VastTagResolver(
        loader = environment.loader,
        maxDepth = configuration.maxWrapperDepth,
        timeoutSeconds = configuration.wrapperTimeoutSeconds,
    )
    private val macros = VastMacroExpander()
    private val selector = VastMediaFileSelector()

    // MARK: - The break in progress

    private var scheduler: VastPodScheduler? = null
    private var breakPlayer: VastBreakPlayer? = null
    private var activeSlot: VastAdSlot? = null
    private var tickJob: Job? = null
    private var lastOutcome: VastAdOutcome = VastAdOutcome.Failed(VastError.UNDEFINED)

    /** The last tick seen, so a beacon can report where the playhead was. */
    private var lastTick: VastTick? = null

    /**
     * `[TRANSACTIONID]` — one value for one break, so an ad server can tie a
     * break's beacons together. Regenerated per break: two breaks on one screen
     * are two transactions.
     */
    private var transactionId: String? = null

    /** The ad surface's size in pixels, as the surface last reported it. */
    private var surfaceSize: VastMacroExpander.Size? = null

    private val compliance = VastCompliance(this)

    /**
     * Why the ad is being held, as distinct from a pause anyone chose — so it is
     * neither reported nor shown as one. Two can overlap: the app leaving the
     * screen while the ad is held for the Picture in Picture window, say.
     */
    private val holds = mutableSetOf<Hold>()

    /** Whether the session paused the player for a hold, and so owes it a restart. */
    private var heldPlayback = false

    private val suspendedBySystem: Boolean get() = heldPlayback

    private enum class Hold { OFF_SCREEN, PICTURE_IN_PICTURE }

    /**
     * An ad nobody can see must not go on playing. AVFoundation stops decoding
     * when an app leaves the foreground; Media3 does not, so a break left running
     * behind the home screen earned quartiles from a viewer who was not there.
     * The session holds the ad instead, the way the system does on Apple
     * platforms: not a §3.14.1 `pause`, because nobody paused, and resumed on its
     * own when the app comes back.
     */
    private val appVisibility = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = noteAppVisible(false)

        override fun onStart(owner: LifecycleOwner) = noteAppVisible(true)
    }

    init {
        environment.appLifecycle?.let { lifecycle -> scope.launch { lifecycle.addObserver(appVisibility) } }
    }

    internal fun noteAppVisible(visible: Boolean) {
        if (visible) release(Hold.OFF_SCREEN) else hold(Hold.OFF_SCREEN)
    }

    private fun hold(reason: Hold) {
        val slot = activeSlot ?: return
        if (slot.finished) return
        holds += reason
        val player = breakPlayer ?: return
        if (heldPlayback || mutableState.value != VastAdState.Playing || !player.wantsPlayback) return
        heldPlayback = true
        player.pause()
    }

    private fun release(reason: Hold) {
        holds -= reason
        if (holds.isNotEmpty() || !heldPlayback) return
        heldPlayback = false
        breakPlayer?.play()
    }

    // MARK: - Picture in Picture

    private val mutableInPictureInPicture = MutableStateFlow(false)

    /**
     * Whether the activity is in the Picture in Picture window, as the host
     * reported it — through [observePictureInPicture] or [notePictureInPicture].
     * The SDK has no other way to know, and does not guess.
     */
    public val isInPictureInPicture: StateFlow<Boolean> = mutableInPictureInPicture.asStateFlow()

    /**
     * Whether the host's own way into Picture in Picture should be offered right
     * now — its button, and `setAutoEnterEnabled`. False while a break runs under
     * [VastPictureInPicturePolicy.SUSPENDED], true the rest of the time.
     */
    public val permitsPictureInPicture: StateFlow<Boolean> = mutableState
        .map { state -> configuration.pictureInPicture != VastPictureInPicturePolicy.SUSPENDED || state.isOutsideBreak }
        .stateIn(scope, SharingStarted.Eagerly, true)

    /**
     * Keeps [isInPictureInPicture] current from the activity's own callbacks — any
     * `ComponentActivity` provides them. Close the result when the screen goes.
     */
    public fun observePictureInPicture(activity: OnPictureInPictureModeChangedProvider): AutoCloseable {
        val listener = Consumer<PictureInPictureModeChangedInfo> { notePictureInPicture(it.isInPictureInPictureMode) }
        activity.addOnPictureInPictureModeChangedListener(listener)
        return AutoCloseable { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }

    /** The activity entered or left Picture in Picture. For a host that tracks it itself. */
    public fun notePictureInPicture(isActive: Boolean) {
        if (mutableInPictureInPicture.value == isActive) return
        mutableInPictureInPicture.value = isActive
        listener?.onPictureInPictureChanged(isActive)
        applyPictureInPicturePolicy()
    }

    /**
     * Outside a break there is no ad to protect and no policy to apply: the window
     * is the host's own. Asked again when a creative starts, because a window that
     * was already open raises no callback of its own.
     */
    private fun applyPictureInPicturePolicy() {
        val active = mutableInPictureInPicture.value
        when (configuration.pictureInPicture) {
            VastPictureInPicturePolicy.ALLOWED -> if (active) activeSlot?.let { compliance.reportPictureInPictureUnreachableUi(it.ad) }
            VastPictureInPicturePolicy.PAUSES_AD -> if (active) pause() else resume()
            VastPictureInPicturePolicy.SUSPENDED -> if (active) hold(Hold.PICTURE_IN_PICTURE) else release(Hold.PICTURE_IN_PICTURE)
        }
    }

    // MARK: - Host actions

    /**
     * Skips the ad on screen.
     *
     * @throws VastSkipException when no ad is playing, the ad is not skippable, or
     *   `skipoffset` has not elapsed yet. Never silently ignores the request —
     *   playing a skippable ad without honouring its skip control violates §2.3.
     */
    public fun skip() {
        val slot = activeSlot ?: throw VastSkipException.NoActiveAd()
        if (!slot.ad.isSkippable) throw VastSkipException.NotSkippable()
        if (!mutableCanSkip.value) throw VastSkipException.NotYetAvailable(mutableTimeUntilSkip.value ?: 0.0)

        slot.skipped = true
        send(slot.engine.userDidSkip(), slot)
        endSlot(slot)
        breakPlayer?.skip(slot)
    }

    /**
     * Records a click on the creative.
     *
     * Fires every `<ClickTracking>` URI and returns the `<ClickThrough>`
     * destination. Opening it is the host's decision: only the host knows whether
     * that means a browser, a Custom Tab, or nothing at all on a TV.
     */
    public fun click(): String? {
        val slot = activeSlot ?: return null
        send(slot.ad.linear.clickTracking.map { VastBeacon(VastBeacon.Kind.ClickTracking, it, slot.ad.id) }, slot)
        breakPlayer?.onAdClicked()
        return slot.ad.linear.clickThrough
    }

    /**
     * Reports an interaction that opens nothing — `<CustomClick>` (§3.10.3).
     * Separate from [click] on purpose: real tags carry both, and firing one for
     * the other misreports both.
     */
    public fun reportCustomClick() {
        val slot = activeSlot ?: return
        send(slot.engine.reportCustomClick(), slot)
    }

    /**
     * Reports an event the SDK cannot observe for itself — mute, fullscreen and
     * the rest of §3.14.1's player operation metrics.
     *
     * Quartiles, `start` and `complete` are derived from observed playback and are
     * refused here, so a host cannot fabricate a billable event.
     */
    public fun report(event: VastTrackingEvent) {
        val slot = activeSlot ?: return
        send(slot.engine.report(event), slot)
    }

    /** Pauses the ad on screen. Reported as §3.14.1 `pause` when the player confirms it. */
    public fun pause() {
        if (mutableState.value == VastAdState.Playing) breakPlayer?.pause()
    }

    /** Resumes an ad paused by [pause] or by the viewer. Reported as `resume`. */
    public fun resume() {
        if (mutableState.value == VastAdState.Paused) breakPlayer?.play()
    }

    /** Reports a vendor's resource as having failed to load — only measurement can know this. */
    public fun reportVerificationNotExecuted(
        verification: VastVerification,
        reason: VastVerification.NotExecutedReason = VastVerification.NotExecutedReason.RESOURCE_LOAD_ERROR,
    ) {
        val slot = activeSlot ?: return
        send(verification.notExecutedTrackers.map { VastBeacon(VastBeacon.Kind.VerificationNotExecuted(reason), it, slot.ad.id) }, slot)
    }

    /**
     * The ad surface was laid out. Called by `VastAdSurface`; a host drawing its
     * own surface calls it too. The size in pixels ranks media files and answers
     * `[PLAYERSIZE]`; in dp it decides whether a skip control fits.
     */
    public fun noteSurfaceSize(widthPixels: Int, heightPixels: Int, density: Float) {
        surfaceSize = if (widthPixels > 1 && heightPixels > 1) VastMacroExpander.Size(widthPixels, heightPixels) else null
        compliance.noteSurface(widthPixels / density, heightPixels / density)
    }

    /** The SDK's skip control was laid out at this size; only then does its real size exist. */
    public fun noteSkipControlSize(widthPixels: Int, heightPixels: Int, density: Float) {
        compliance.noteSkipControl(widthPixels / density, heightPixels / density)
    }

    /**
     * The player to give the host's `MediaSession` instead of the player itself,
     * so the notification, the lock screen and a headset cannot skip or speed up
     * the creative, and say what is playing while it does:
     *
     * ```kotlin
     * val mediaSession = MediaSession.Builder(context, session.forMediaSession(player)).build()
     * ```
     *
     * What it shows and locks is [VastConfiguration.nowPlaying].
     */
    public fun forMediaSession(player: Player): Player =
        VastMediaSessionPlayer(player, this, configuration.nowPlaying, environment.nowPlayingTitle)

    internal fun launchOnMain(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    // MARK: - Breaks on demand

    private val mutablePendingBreaks = MutableStateFlow(0)

    /** Breaks asked for with [insertBreak] that are waiting for the one before them. */
    public val pendingBreakCount: StateFlow<Int> = mutablePendingBreaks.asStateFlow()

    /**
     * Plays a break during the content: right away when the content is what is
     * playing, and after the break on screen when it is not. Several asked for at
     * once play one after another, in the order they were asked for.
     *
     * The break goes into the content's own timeline at the playhead, so the
     * content stops where it was and resumes from there, and
     * every rule a pre-roll keeps — skip, tracking, the media controls, the
     * window — holds for it the same way.
     *
     * @param adTag an ad server URL, or a response in hand from
     *   [VastAdsLoader.adTagUriForResponse].
     */
    public fun insertBreak(adTag: Uri) {
        adsLoader.insertBreak(adTag)
    }

    internal fun notePendingBreaks(count: Int) {
        mutablePendingBreaks.value = count
    }

    /** Ends the session: the break in progress, the player listener, and every coroutine. */
    public fun release() {
        stopTicking()
        environment.appLifecycle?.removeObserver(appVisibility)
        adsLoader.release()
        scope.cancel()
        breakPlayer = null
        activeSlot = null
        scheduler = null
        resetPublishedAd()
        mutableState.value = VastAdState.Idle
    }

    // MARK: - Resolution (called by the loader)

    /**
     * Resolves the break's tag, giving up after the resolution budget. Calls
     * [onResolved] with the planned slots — empty when nothing will play — on the
     * main thread.
     */
    internal fun resolve(tag: VastAdTag, blocking: Boolean, onResolved: (List<VastAdSlot>) -> Unit): Job = scope.launch {
        // A pre-roll holds the content until it resolves, so the session is loading.
        // A break asked for during the content is not: the content plays on while
        // its response comes back, and nothing should look as though it stopped.
        if (blocking) mutableState.value = VastAdState.Loading
        transactionId = UUID.randomUUID().toString()
        val ads = try {
            withResolutionBudget {
                when (tag) {
                    is VastAdTag.Url -> resolver.resolveTag(tag.url)
                    is VastAdTag.Xml -> resolver.resolveXml(tag.xml, tag.baseUrl)
                }
            }.ads
        } catch (failure: VastTagResolver.Failure) {
            // The wrappers traversed are owed an error request, sent without
            // waiting: the content is waiting on this break.
            send(failure.beacons, null)
            failBreak(failure.error, blocking)
            onResolved(emptyList())
            return@launch
        } catch (_: TimeoutCancellationException) {
            // No beacons: only the chain knows which URIs are owed, and cutting it
            // off from outside is exactly the case where it cannot say.
            failBreak(VastError.WRAPPER_TIMEOUT, blocking)
            onResolved(emptyList())
            return@launch
        }
        if (ads.isEmpty()) {
            failBreak(VastError.NO_VAST_RESPONSE_AFTER_WRAPPERS, blocking)
            onResolved(emptyList())
            return@launch
        }
        val scheduler = VastPodScheduler(ads)
        this@VastAdSession.scheduler = scheduler
        val slots = mutableListOf<VastAdSlot>()
        while (true) {
            val ad = scheduler.next() ?: break
            plan(ad, slots.size)?.let { slots += it } ?: substitute(slots.size)?.let { slots += it }
        }
        if (slots.isEmpty()) {
            if (blocking) finishBreak()
            onResolved(emptyList())
            return@launch
        }
        mutableAdPosition.value = VastAdPosition(1, slots.size)
        onResolved(slots)
    }

    /**
     * The whole response, not one hop: `wrapperTimeoutSeconds` already bounds a
     * single fetch, and five of those in series is long past the point where a
     * viewer stops believing an ad is coming.
     */
    private suspend fun <T> withResolutionBudget(work: suspend () -> T): T {
        val budget = configuration.resolutionTimeoutSeconds
        if (budget <= 0) return work()
        return withTimeout((budget * 1000).toLong()) { work() }
    }

    /**
     * What one ad needs before it can be put in the timeline: a creative this
     * player can decode, and — when it is skippable — somebody to draw the skip.
     * `null` when it cannot play; its `<Error>` URIs have then been told why.
     */
    private fun plan(ad: VastAd, index: Int): VastAdSlot? {
        // §2.3: "nor should the media player play the Skippable Ad as a Linear Ad
        // (without skip controls)". A host that cannot offer the control is told
        // to expect a trafficking error, not handed a compliance breach.
        if (ad.isSkippable && skipPresentation(ad) == VastSkipPresentation.UNSUPPORTED) {
            failUnplayed(ad, VastError.TRAFFICKING)
            return null
        }
        val file = try {
            selector.select(ad.linear.mediaFiles, capabilities())
        } catch (_: VastException) {
            failUnplayed(ad, VastError.NO_SUPPORTED_MEDIA_FILE)
            return null
        }
        return VastAdSlot(index, ad, file, VastTrackingEngine(ad, measurementWillRun = measurement != null))
    }

    /** §3.3.1: a failed pod member is replaced by an unplayed stand-alone ad, if the response had one. */
    internal fun substitute(index: Int): VastAdSlot? {
        val scheduler = scheduler ?: return null
        while (true) {
            val spare = scheduler.substituteForFailure() ?: return null
            plan(spare, index)?.let { return it }
        }
    }

    private fun failUnplayed(ad: VastAd, error: VastError) {
        val engine = VastTrackingEngine(ad, measurementWillRun = measurement != null)
        send(engine.fail(error), null, ad)
        listener?.onAdFailed(error, ad)
    }

    /** The tag could not even be read, so there is nothing to resolve and nobody's `<Error>` to tell. */
    internal fun resolveFailedBeforeLoading(error: VastError) {
        failBreak(error, blocking = true)
        finishBreak()
    }

    /**
     * The response produced no ad. A pre-roll that fails finishes the session's
     * state; a break asked for during the content leaves the state as the content
     * has it — only the listener hears, because nothing on screen changed.
     */
    private fun failBreak(error: VastError, blocking: Boolean) {
        if (blocking) {
            lastOutcome = VastAdOutcome.Failed(error)
            mutableState.value = VastAdState.Finished(lastOutcome)
        }
        listener?.onAdFailed(error, null)
    }

    // MARK: - Playback events (called by the loader)

    internal fun attach(player: VastBreakPlayer?) {
        breakPlayer = player
    }

    /** Media3 prepared the creative: it is ready, which is what `loaded` means (§3.14.1). */
    internal fun slotPrepared(slot: VastAdSlot) {
        if (slot.loadedReported) return
        slot.loadedReported = true
        send(slot.engine.creativeDidLoad(), slot)
    }

    /** Media3 could not prepare the creative. */
    internal fun slotFailed(slot: VastAdSlot, error: VastError) {
        if (slot.finished) return
        if (activeSlot === slot) stopTicking()
        send(slot.engine.fail(error), slot)
        slot.finished = true
        slot.outcome = VastAdOutcome.Failed(error)
        lastOutcome = slot.outcome!!
        listener?.onAdFailed(error, slot.ad)
        if (activeSlot === slot) {
            listener?.onAdFinished(slot.ad, slot.outcome!!)
            measurement?.finish()
            activeSlot = null
        }
    }

    /** The player is now showing this creative. */
    internal fun slotStarted(slot: VastAdSlot, total: Int, clock: VastClock) {
        activeSlot = slot
        compliance.reset()
        mutableCurrentAd.value = slot.ad
        mutableAdPosition.value = VastAdPosition(slot.index + 1, total)
        mutableCanSkip.value = false
        mutableTimeUntilSkip.value = slot.ad.linear.resolvedSkipOffset()
        // Seeded from <Duration> so the overlay reads the creative's length while
        // it buffers, instead of showing 0s.
        mutableRemainingTime.value = slot.ad.linear.duration
        lastTick = null
        slotPrepared(slot)

        mutableState.value = VastAdState.Playing
        compliance.verifyClickPath(slot.ad)
        // Before the impression: a measurement session has to exist for the
        // impression it is being asked to attest to.
        measurement?.begin(VastMeasurementContext(slot.ad, adView = null))
        listener?.onAdStarted(slot.ad, mutableAdPosition.value)
        if (!environment.isAppVisible()) hold(Hold.OFF_SCREEN)
        if (mutableInPictureInPicture.value) applyPictureInPicturePolicy()

        val activeClock = configuration.clock ?: clock
        slot.clock = activeClock
        var lastAdvance = environment.uptimeSeconds()
        var lastPosition = -1.0
        tickJob = scope.launch {
            activeClock.ticks().collect { tick ->
                if (activeSlot !== slot || slot.finished) return@collect
                lastTick = tick
                send(slot.engine.advance(tick), slot)
                update(tick, slot.ad)

                val now = environment.uptimeSeconds()
                val player = breakPlayer
                if (tick.adTime > lastPosition + 0.01) {
                    lastPosition = tick.adTime
                    lastAdvance = now
                } else if (player == null || !player.wantsPlayback) {
                    // Paused, or held by the system: the playhead is meant to be
                    // still, so the clock for "stuck" does not run. Without this a
                    // viewer who paused for longer than the stall timeout had the ad
                    // written off as unplayable.
                    lastAdvance = now
                } else if (now - lastAdvance > STALL_TIMEOUT_SECONDS) {
                    stall(slot)
                    return@collect
                }

                // Finished by coverage: the engine has reported `complete`. The
                // player plays the last frames on its own and moves on; nothing
                // further needs a tick.
                if (slot.engine.isFinished) stopTicking()
            }
        }
    }

    /**
     * The player left this creative. `naturally` is the creative playing to its
     * end, which is the authoritative signal that it is over — watch time alone
     * cannot be trusted for this, because stalls are excluded from it by design.
     */
    internal fun slotLeft(slot: VastAdSlot, naturally: Boolean) {
        if (slot.finished) {
            if (activeSlot === slot) activeSlot = null
            return
        }
        stopTicking()
        // A creative left by anything but its own end — the host seeking out of it,
        // or replacing the media — is not ours to account for any more (405).
        val beacons = if (naturally) slot.engine.playbackDidReachEnd() else slot.engine.fail(VastError.MEDIA_FILE_DISPLAY_PROBLEM)
        send(beacons, slot)
        endSlot(slot)
    }

    /** Every slot is over and the content is back. */
    internal fun breakEnded() {
        stopTicking()
        activeSlot?.let { if (!it.finished) slotLeft(it, naturally = false) }
        finishBreak()
    }

    /**
     * The player started or stopped. A stop the session did not cause is still a
     * pause the ad server is owed (§3.14.1) — the viewer's own control, a
     * notification button, losing audio focus. Media3 does not resume after a
     * permanent loss of focus, so unlike AVFoundation's system pauses these are
     * real pauses, and reported as such.
     */
    internal fun notePlayback(wantsPlayback: Boolean) {
        val slot = activeSlot ?: return
        if (slot.finished || heldPlayback) return
        if (wantsPlayback) {
            if (mutableState.value != VastAdState.Paused) return
            send(slot.engine.report(VastTrackingEvent.RESUME), slot)
            mutableState.value = VastAdState.Playing
            return
        }
        if (mutableState.value != VastAdState.Playing) return
        // The creative running out stops the player like anything else does, and
        // `pause` after `complete` is a sequence no ad server should be sent.
        if (slot.engine.isFinished || mutableRemainingTime.value <= END_OF_CREATIVE_MARGIN_SECONDS) return
        send(slot.engine.report(VastTrackingEvent.PAUSE), slot)
        mutableState.value = VastAdState.Paused
    }

    private fun stall(slot: VastAdSlot) {
        stopTicking()
        val beacons = slot.engine.playbackDidStall()
        send(beacons, slot)
        if (beacons.any { it.kind is VastBeacon.Kind.Error }) listener?.onAdFailed(VastError.MEDIA_FILE_TIMEOUT, slot.ad)
        endSlot(slot)
        breakPlayer?.abandon(slot)
    }

    private fun endSlot(slot: VastAdSlot) {
        if (slot.finished) return
        stopTicking()
        // A hold belongs to the creative it was put on: the next one starts clean
        // and is held again only if the reason still stands.
        holds.clear()
        heldPlayback = false
        slot.finished = true
        val outcome = outcomeFor(slot)
        slot.outcome = outcome
        lastOutcome = outcome
        // Exactly once per `begin`, whatever the outcome: a measurement session
        // left open counts against the vendor, not against us.
        measurement?.finish()
        listener?.onAdFinished(slot.ad, outcome)
        if (activeSlot === slot) activeSlot = null
    }

    /** A skip beats everything; otherwise the creative counts as completed only if the engine actually finished it. */
    private fun outcomeFor(slot: VastAdSlot): VastAdOutcome {
        if (slot.skipped) return VastAdOutcome.Skipped
        val engine = slot.engine
        if (!engine.isFinished) return VastAdOutcome.Failed(VastError.MEDIA_FILE_TIMEOUT)
        return if (engine.watched >= engine.duration * VastTrackingEngine.COMPLETION_THRESHOLD) {
            VastAdOutcome.Completed
        } else {
            VastAdOutcome.Failed(VastError.MEDIA_FILE_DISPLAY_PROBLEM)
        }
    }

    private fun finishBreak() {
        mutableState.value = VastAdState.Finished(lastOutcome)
        scheduler = null
        activeSlot = null
        resetPublishedAd()
        listener?.onAllAdsFinished()
    }

    private fun resetPublishedAd() {
        mutableCurrentAd.value = null
        mutableCanSkip.value = false
        mutableTimeUntilSkip.value = null
        mutableRemainingTime.value = 0.0
        lastTick = null
    }

    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
        activeSlot?.clock?.stop()
    }

    /** Publishes the state the ad surface and the listener render from. */
    private fun update(tick: VastTick, ad: VastAd) {
        val duration = tick.duration ?: ad.linear.duration
        mutableRemainingTime.value = max(0.0, duration - tick.adTime)
        listener?.onAdProgress(ad, tick.adTime, duration)

        val unlockAt = ad.linear.resolvedSkipOffset()
        if (unlockAt == null) {
            mutableTimeUntilSkip.value = null
            return
        }
        val remainingUntilSkip = max(0.0, unlockAt - tick.adTime)
        mutableTimeUntilSkip.value = if (remainingUntilSkip > 0) remainingUntilSkip else null

        if (remainingUntilSkip <= 0 && !mutableCanSkip.value) {
            mutableCanSkip.value = true
            compliance.verifySkipSurface(ad)
            listener?.onSkipAvailable(ad)
        }
    }

    // MARK: - Policy

    internal fun suppressesAdUi(ad: VastAd?): Boolean = isHiddenUi.value && ad?.isUiHidden == true

    internal fun skipPresentation(ad: VastAd?): VastSkipPresentation =
        if (suppressesAdUi(ad) && configuration.skipPresentation == VastSkipPresentation.SDK) VastSkipPresentation.HOST else configuration.skipPresentation

    /**
     * The surface the creative will actually be shown on, used to rank media
     * files. The ad surface is the player area, so asking it is asking the right
     * thing; the whole screen is only the fallback for before it has been laid out.
     */
    private fun capabilities(): VastMediaFileSelector.Capabilities {
        val size = surfaceSize ?: environment.screenSize() ?: VastMacroExpander.Size(1280, 720)
        return VastMediaFileSelector.Capabilities(width = size.width, height = size.height)
    }

    internal val isTelevision: Boolean get() = environment.isTelevision

    internal val currentSlot: VastAdSlot? get() = activeSlot

    // MARK: - Beacons

    /**
     * Hands beacons to the transport without waiting for them.
     *
     * Never awaited: a beacon is a request to somebody else's server, and
     * awaiting it would let one slow tracking host freeze the countdown and the
     * skip control. Macros are substituted here rather than where each beacon is
     * created, because the values they need belong to the session — and doing it
     * in one place is what stops a `[ERRORCODE]` going out as literal text.
     */
    private fun send(beacons: List<VastBeacon>, slot: VastAdSlot?, ad: VastAd? = slot?.ad) {
        if (beacons.isEmpty()) return
        notifyMeasurement(beacons, slot)
        notifyListener(beacons, ad)
        val expanded = beacons.map { expandMacros(it, slot, ad) }
        val transport = environment.transport
        DELIVERY.launch { transport.fire(expanded) }
    }

    /**
     * Measurement is driven from the beacons rather than from separate call sites,
     * so what a vendor observes is the same sequence, in the same order, that the
     * ad server is told about.
     */
    private fun notifyMeasurement(beacons: List<VastBeacon>, slot: VastAdSlot?) {
        val measurement = measurement ?: return
        var last: VastMeasurementEvent? = null
        for (beacon in beacons) {
            val event = measurementEvent(beacon.kind, lastTick?.isMuted ?: false, slot?.engine?.duration ?: 0.0) ?: continue
            // One `<Impression>` element per vendor is normal; one impression is what happened.
            if (event == last) continue
            last = event
            measurement.record(event)
        }
    }

    /** The listener hears the same sequence, in the same order, for the same reason measurement does. */
    private fun notifyListener(beacons: List<VastBeacon>, ad: VastAd?) {
        val listener = listener ?: return
        var last: VastBeacon.Kind? = null
        for (beacon in beacons) {
            // Three `<Impression>` URLs are three beacons and one impression.
            if (beacon.kind == last) continue
            last = beacon.kind
            listener.onReported(beacon.kind, ad)
        }
    }

    private fun expandMacros(beacon: VastBeacon, slot: VastAdSlot?, ad: VastAd?): VastBeacon {
        val kind = beacon.kind
        val context = VastMacroExpander.Context(
            // Only an error beacon carries a code, and it is the code for *this*
            // failure. `[REASON]` works the same way.
            errorCode = (kind as? VastBeacon.Kind.Error)?.error,
            verificationNotExecutedReason = (kind as? VastBeacon.Kind.VerificationNotExecuted)?.reason,
            adPlayhead = lastTick?.adTime,
            assetUri = slot?.mediaFile?.url,
            playerSize = surfaceSize,
            isMuted = lastTick?.isMuted,
            appBundle = environment.appBundle,
            verificationVendors = ad?.adVerifications?.mapNotNull { it.vendor }.orEmpty(),
            omidPartner = measurement?.omidPartner,
            host = configuration.macroValues,
            mediaMimeType = slot?.mediaFile?.mimeType,
            transactionId = transactionId,
            executesOmid = measurement != null,
        )
        return beacon.copy(url = macros.expandUrl(beacon.url, context))
    }

    internal companion object {
        /**
         * How long the playhead may sit still, while the player claims to be
         * playing, before the creative is written off as unplayable (402).
         */
        const val STALL_TIMEOUT_SECONDS: Double = 10.0

        /**
         * How close to the end of a creative a stop stops being a pause: wider than
         * the engine's own completion margin and than one tick, so it holds
         * whichever of the end and the stop arrives first.
         */
        const val END_OF_CREATIVE_MARGIN_SECONDS: Double = 0.5

        /**
         * Beacons outlive the session that sent them: a screen closing the moment
         * an ad completes must not take the `complete` request down with it.
         */
        private val DELIVERY = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun measurementEvent(kind: VastBeacon.Kind, isMuted: Boolean, duration: Double): VastMeasurementEvent? = when (kind) {
            VastBeacon.Kind.Impression -> VastMeasurementEvent.Impression
            is VastBeacon.Kind.Progress -> VastMeasurementEvent.Progress(kind.offset)
            VastBeacon.Kind.ClickTracking, VastBeacon.Kind.CustomClick -> VastMeasurementEvent.Clicked
            is VastBeacon.Kind.Error -> VastMeasurementEvent.Failed(kind.error)
            // Both are only fired when nothing is measuring, so nobody is listening for them.
            is VastBeacon.Kind.VerificationNotExecuted, VastBeacon.Kind.ViewUndetermined -> null
            is VastBeacon.Kind.Tracking -> when (kind.event) {
                VastTrackingEvent.START -> VastMeasurementEvent.Start(duration, isMuted)
                VastTrackingEvent.FIRST_QUARTILE, VastTrackingEvent.MIDPOINT,
                VastTrackingEvent.THIRD_QUARTILE, VastTrackingEvent.COMPLETE,
                -> VastMeasurementEvent.Quartile(kind.event)
                VastTrackingEvent.PAUSE -> VastMeasurementEvent.Pause
                VastTrackingEvent.RESUME -> VastMeasurementEvent.Resume
                VastTrackingEvent.SKIP -> VastMeasurementEvent.Skipped
                else -> null
            }
        }
    }
}

/** The tag a break was asked to play, as the content's `AdsConfiguration` named it. */
internal sealed interface VastAdTag {
    data class Url(val url: String) : VastAdTag

    /** A response already in hand, handed over as a `data:` URI. */
    data class Xml(val xml: String, val baseUrl: String?) : VastAdTag
}

/** One ad given a place in the timeline. */
internal class VastAdSlot(
    val index: Int,
    val ad: VastAd,
    val mediaFile: VastAd.MediaFile,
    val engine: VastTrackingEngine,
) {
    var loadedReported = false
    var skipped = false
    var finished = false
    var outcome: VastAdOutcome? = null
    var clock: VastClock? = null

    /** Where the slot's ad group sits in the content period, set when it is given one. */
    var groupTimeUs: Long = 0
}

/** What the session needs from whatever is actually playing the break. */
internal interface VastBreakPlayer {
    /** Whether the player is trying to play — false while paused by anyone. */
    val wantsPlayback: Boolean

    fun skip(slot: VastAdSlot)

    /** The creative stalled for good; move on without it. */
    fun abandon(slot: VastAdSlot)

    fun pause()

    fun play()

    fun onAdClicked()
}

/** `.loading` counts as inside: the break takes the controls a moment early, which is the safe direction. */
internal val VastAdState.isOutsideBreak: Boolean
    get() = when (this) {
        VastAdState.Loading, VastAdState.Playing, VastAdState.Paused -> false
        VastAdState.Idle, is VastAdState.Finished -> true
    }

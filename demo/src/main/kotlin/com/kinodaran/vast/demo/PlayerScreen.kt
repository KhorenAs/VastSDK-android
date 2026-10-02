package com.kinodaran.vast.demo

import android.app.PictureInPictureParams
import android.graphics.Rect
import android.os.Build
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.compose.ContentFrame
import com.kinodaran.vast.compose.VastAdSurface
import com.kinodaran.vast.core.VastAd
import com.kinodaran.vast.core.VastBeacon
import com.kinodaran.vast.core.VastError
import com.kinodaran.vast.kit.VastAdOutcome
import com.kinodaran.vast.kit.VastAdPosition
import com.kinodaran.vast.kit.VastAdSession
import com.kinodaran.vast.kit.VastAdSessionListener
import com.kinodaran.vast.kit.VastAdsLoader
import com.kinodaran.vast.kit.VastConfiguration
import com.kinodaran.vast.kit.VastSkipPresentation
import com.kinodaran.vast.kit.withVastAds
import kotlin.math.ceil

/** How many player screens are alive, shown on the list: it must return to zero. */
object LiveScreens {
    var count by mutableIntStateOf(0)
}

/**
 * One scenario: the content with its break in front of it, the SDK's ad UI over
 * the player, and a log of what the session reported — the one thing a viewer
 * cannot see for themselves.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(scenario: DemoScenario, onClose: () -> Unit) {
    val context = LocalContext.current
    val log = remember { mutableStateListOf<String>() }

    val session = remember(scenario.id) {
        val configuration = if (scenario.hostDrawsUi) VastConfiguration(skipPresentation = VastSkipPresentation.HOST) else VastConfiguration()
        VastAdSession(context, configuration).apply {
            isHiddenUi.value = scenario.hostDrawsUi
            listener = DemoListener(log)
        }
    }
    val player = remember(scenario.id) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).withVastAds(session))
            .build()
            .also { player ->
                session.adsLoader.setPlayer(player)
                val tag = when (val source = scenario.source) {
                    is DemoScenario.Source.Tag -> DemoCatalog.requestReady(source.url).toUri()
                    is DemoScenario.Source.Xml -> VastAdsLoader.adTagUriForResponse(source.xml)
                }
                player.setMediaItem(
                    MediaItem.Builder()
                        .setUri(DemoCatalog.CONTENT_STREAM)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("Big Buck Bipbop").setArtist("Kinodaran demo").build())
                        .setAdsConfiguration(MediaItem.AdsConfiguration.Builder(tag).setAdsId(scenario.id).build())
                        .build(),
                )
                player.playWhenReady = true
                player.prepare()
            }
    }

    // The system's media controls drive the player through the SDK's wrapper, so
    // the notification and the lock screen cannot skip or speed up the creative,
    // and name it while it plays.
    val mediaSession = remember(scenario.id) {
        MediaSession.Builder(context, session.forMediaSession(player)).setId("demo-${scenario.id}").build()
    }

    val activity = context as ComponentActivity
    val inPictureInPicture by session.isInPictureInPicture.collectAsState()
    val permitsPictureInPicture by session.permitsPictureInPicture.collectAsState()
    // Not every device has the window: most televisions, this emulator's among them,
    // do not, and there entering it does nothing.
    val supportsPictureInPicture = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    var stageBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val pendingBreaks by session.pendingBreakCount.collectAsState()
    var inserted by remember { mutableIntStateOf(0) }

    // Leaving the app opens the window by itself, where the system allows it —
    // and not while the session says the break may not follow the viewer there.
    LaunchedEffect(permitsPictureInPicture, stageBounds) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.setPictureInPictureParams(pictureInPictureParams(stageBounds, permitsPictureInPicture))
        }
    }

    DisposableEffect(scenario.id) {
        LiveScreens.count += 1
        val pictureInPicture = session.observePictureInPicture(activity)
        onDispose {
            pictureInPicture.close()
            mediaSession.release()
            session.release()
            player.release()
            LiveScreens.count -= 1
        }
    }

    // Landscape is the player and nothing else, the way a viewer turning the phone
    // means it. The player stays at the same place in the composition either way:
    // moved to another parent it would get a new surface, and a playing video
    // handed a new surface mid-stream shows black until something redraws it.
    // A television is always landscape and has nowhere else to put the buttons,
    // so it keeps them under the player instead.
    val television = isTelevision()
    val landscape = (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE && !television) || inPictureInPicture
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(
            (if (landscape || television) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                .background(Color.Black)
                .onGloballyPositioned { stageBounds = it.boundsInWindow() },
        ) {
            ContentFrame(player, Modifier.fillMaxSize())
            VastAdSurface(session)
            if (scenario.hostDrawsUi) HostSkip(session, Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
        if (landscape) return@Column
        Row {
            TextButton(onClick = onClose) { Text("‹ Scenarios") }
            // A break on demand: now, if the content is playing, or after the break
            // on screen — the session queues it.
            TextButton(onClick = {
                inserted += 1
                session.insertBreak(VastAdsLoader.adTagUriForResponse(DemoVast.inLine(skipOffset = "00:00:02", id = "midroll-$inserted")))
                log += "＋ asked for midroll-$inserted"
            }) { Text(if (pendingBreaks > 0) "Insert ad ($pendingBreaks waiting)" else "Insert ad") }
            // Offered only when the session says the window is allowed — under
            // SUSPENDED it is not, for the length of the break.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && supportsPictureInPicture) {
                TextButton(
                    onClick = { activity.enterPictureInPictureMode(pictureInPictureParams(stageBounds, permitsPictureInPicture)) },
                    enabled = permitsPictureInPicture,
                ) { Text("Picture in Picture") }
            }
        }
        if (television) return@Column
        Text(scenario.title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** The window's shape and where it grows from, and whether leaving the app may open it. */
@RequiresApi(Build.VERSION_CODES.O)
private fun pictureInPictureParams(bounds: androidx.compose.ui.geometry.Rect?, autoEnter: Boolean): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
    bounds?.let { builder.setSourceRectHint(Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(autoEnter)
    return builder.build()
}

/**
 * The host's own skip control, for the scenario that takes the ad UI over. The
 * §2.3 obligation came with it: the SDK draws nothing, so this has to be here.
 */
@Composable
private fun HostSkip(session: VastAdSession, modifier: Modifier) {
    val ad by session.currentAd.collectAsState()
    val canSkip by session.canSkip.collectAsState()
    val timeUntilSkip by session.timeUntilSkip.collectAsState()
    if (ad?.isSkippable != true) return
    Button(onClick = { runCatching { session.skip() } }, enabled = canSkip, modifier = modifier) {
        Text(if (canSkip) "Host skip ›" else "Host skip in ${ceil(timeUntilSkip ?: 0.0).toInt()}")
    }
}

private class DemoListener(private val log: MutableList<String>) : VastAdSessionListener {
    override fun onAdStarted(ad: VastAd, position: VastAdPosition) {
        log += "▶ ${ad.id} (${position.index}/${position.total})"
    }

    override fun onReported(kind: VastBeacon.Kind, ad: VastAd?) {
        log += "  reported ${describe(kind)}"
    }

    override fun onAdFinished(ad: VastAd, outcome: VastAdOutcome) {
        log += "■ ${ad.id}: $outcome"
    }

    override fun onAdFailed(error: VastError, ad: VastAd?) {
        log += "✕ ${ad?.id ?: "response"}: ${error.code} ${error.name}"
    }

    override fun onAllAdsFinished() {
        log += "break over — content resumes"
    }

    override fun onSkipControlUnavailable(ad: VastAd, reason: String) {
        log += "⚠ $reason"
    }

    override fun onPictureInPictureChanged(isActive: Boolean) {
        log += if (isActive) "⧉ entered Picture in Picture" else "⧉ left Picture in Picture"
    }

    private fun describe(kind: VastBeacon.Kind) = when (kind) {
        is VastBeacon.Kind.Tracking -> kind.event.vastName
        is VastBeacon.Kind.Error -> "error ${kind.error.code}"
        is VastBeacon.Kind.Progress -> "progress ${kind.offset}s"
        else -> kind.toString()
    }
}

package com.kinodaran.vast.demo

import android.content.res.Configuration
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
                        .setAdsConfiguration(MediaItem.AdsConfiguration.Builder(tag).setAdsId(scenario.id).build())
                        .build(),
                )
                player.playWhenReady = true
                player.prepare()
            }
    }

    DisposableEffect(scenario.id) {
        LiveScreens.count += 1
        onDispose {
            session.release()
            player.release()
            LiveScreens.count -= 1
        }
    }

    // Landscape is the player and nothing else, the way a viewer turning the phone
    // means it. The player stays at the same place in the composition either way:
    // moved to another parent it would get a new surface, and a playing video
    // handed a new surface mid-stream shows black until something redraws it.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(
            (if (landscape) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                .background(Color.Black),
        ) {
            ContentFrame(player, Modifier.fillMaxSize())
            VastAdSurface(session)
            if (scenario.hostDrawsUi) HostSkip(session, Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
        if (landscape) return@Column
        TextButton(onClick = onClose) { Text("‹ Scenarios") }
        Text(scenario.title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxSize().padding(16.dp)) {
            items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
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

    private fun describe(kind: VastBeacon.Kind) = when (kind) {
        is VastBeacon.Kind.Tracking -> kind.event.vastName
        is VastBeacon.Kind.Error -> "error ${kind.error.code}"
        is VastBeacon.Kind.Progress -> "progress ${kind.offset}s"
        else -> kind.toString()
    }
}
